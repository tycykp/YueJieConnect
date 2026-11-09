//! One-purpose, one-direction media TCP channel authorized by an existing confirmed control session.
//! This module does not discover/pair peers, deserialize grants, grant capture permission, or report
//! rendering. NNpsk0 authenticates a fresh, per-stream secret exchanged over the control channel.
//! Queue limits include the frame currently being written/reassembled. Noise records are fragmented
//! below 64 KiB; Java ScreenCastPacket access units remain at most 1 MiB.
use sha2::{Digest, Sha256};
use std::collections::{HashSet, VecDeque};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, MutexGuard, Weak};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const MAX_ACCESS_UNIT: usize = 1_048_576;
pub const PACKET_HEADER: usize = 40;
const AUDIO_FLAG: u16 = 8;
const AUDIO_BYTES: usize = 3840; // 20 ms of stereo 48 kHz PCM16
pub const MAX_QUEUED_BYTES: usize = 4 * 1_048_576;
pub const MAX_QUEUED_FRAMES: usize = 8;
const MAX_STREAMS_PER_SESSION: usize = 64;
const MAX_RECORD: usize = 65_535;
const CHUNK: usize = 60_000;
const FRAGMENT_HEADER: usize = 17;
const IO_POLL: Duration = Duration::from_millis(50);
const STALL_TIMEOUT: Duration = Duration::from_secs(5);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(1);
const PATTERN: &str = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";

fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|poison| poison.into_inner())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Purpose {
    ScreenVideo = 1,
    CameraVideo = 2,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Role {
    Sender,
    Receiver,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CloseReason {
    LocalClosed,
    SessionEnded,
    PermissionRevoked,
    Expired,
    PeerClosed,
    TransportFailed,
    AuthenticationFailed,
    ProtocolViolation,
    IdleTimeout,
    BackpressureTimeout,
    LocalEnded,
    RemoteEnded,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    InvalidScope,
    InvalidPacket,
    AlreadyUsed,
    SessionCapacity,
    EntropyUnavailable,
    Closed(CloseReason),
}
impl std::fmt::Display for Error {
    fn fmt(&self, out: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(out, "{self:?}")
    }
}
impl std::error::Error for Error {}

/// Intentionally neither Clone, Debug nor Deserialize. Consumed even when establishment fails.
pub struct OneTimeKey([u8; 32]);
impl OneTimeKey {
    pub fn generate() -> Result<Self, Error> {
        let mut secret = [0; 32];
        getrandom::getrandom(&mut secret).map_err(|_| Error::EntropyUnavailable)?;
        Ok(Self(secret))
    }
    /// Only for an authenticated, currently approved control-session message; never network JSON.
    pub fn from_authenticated_control(secret: [u8; 32]) -> Result<Self, Error> {
        if secret == [0; 32] {
            return Err(Error::InvalidScope);
        }
        Ok(Self(secret))
    }
    /// The control owner may transmit this ONCE to the intended authenticated peer. Never log it.
    pub fn for_authenticated_control(&self) -> [u8; 32] {
        self.0
    }
}
impl Drop for OneTimeKey {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

struct Authorization {
    session_id: [u8; 32],
    local_peer: [u8; 32],
    remote_peer: [u8; 32],
    ended: AtomicBool,
    permissions: Mutex<[Permission; 2]>,
    used: Mutex<GrantLedger>,
    channels: Mutex<Vec<Weak<Shared>>>,
}
#[derive(Clone, Copy)]
struct Permission {
    granted: bool,
    epoch: u64,
}
fn purpose_index(purpose: Purpose) -> usize {
    purpose as usize - 1
}
#[derive(Default)]
struct GrantLedger {
    streams: HashSet<u64>,
    keys: HashSet<[u8; 32]>,
}
/// Created exclusively by the control-session owner after identity comparison and both confirmations.
/// Hold one instance for that actual session; do not recreate it to evade the one-use stream ledger.
#[derive(Clone)]
pub struct SessionAuthorization(Arc<Authorization>);
impl SessionAuthorization {
    pub fn confirmed(
        session_id: [u8; 32],
        local_peer: [u8; 32],
        remote_peer: [u8; 32],
    ) -> Result<Self, Error> {
        if session_id == [0; 32]
            || local_peer == [0; 32]
            || remote_peer == [0; 32]
            || local_peer == remote_peer
        {
            return Err(Error::InvalidScope);
        }
        Ok(Self(Arc::new(Authorization {
            session_id,
            local_peer,
            remote_peer,
            ended: AtomicBool::new(false),
            permissions: Mutex::new(
                [Permission {
                    granted: false,
                    epoch: 0,
                }; 2],
            ),
            used: Mutex::new(GrantLedger::default()),
            channels: Mutex::new(Vec::new()),
        })))
    }
    /// Pairing alone grants no media. Call only after fresh native permission and explicit control
    /// approval for this purpose. Re-grant closes old generations while preserving the replay ledger.
    pub fn grant_after_consent(&self, purpose: Purpose) -> Result<(), Error> {
        if self.0.ended.load(Ordering::SeqCst) {
            return Err(Error::Closed(CloseReason::SessionEnded));
        }
        {
            let mut permissions = lock(&self.0.permissions);
            let permission = &mut permissions[purpose_index(purpose)];
            permission.epoch = permission.epoch.checked_add(1).ok_or(Error::InvalidScope)?;
            permission.granted = true;
        }
        self.close_invalid();
        Ok(())
    }
    /// Caller must have separate, current screen/camera consent. Role explicitly binds the sender
    /// and receiver identities in the prologue. A new stream and new key are required after failure.
    pub fn authorize_stream(
        &self,
        stream_id: u64,
        purpose: Purpose,
        role: Role,
        key: OneTimeKey,
        lifetime: Duration,
    ) -> Result<MediaTicket, Error> {
        if stream_id == 0
            || stream_id > i64::MAX as u64
            || lifetime.is_zero()
            || lifetime > Duration::from_secs(3600)
        {
            return Err(Error::InvalidScope);
        }
        let permission_epoch = lock(&self.0.permissions)[purpose_index(purpose)].epoch;
        if let Some(reason) = self.0.invalid(purpose, permission_epoch) {
            return Err(Error::Closed(reason));
        }
        let mut used = lock(&self.0.used);
        let fingerprint: [u8; 32] = Sha256::digest(ticket_key_bytes(&key)).into();
        if used.streams.contains(&stream_id) || used.keys.contains(&fingerprint) {
            return Err(Error::AlreadyUsed);
        }
        if used.streams.len() >= MAX_STREAMS_PER_SESSION {
            return Err(Error::SessionCapacity);
        }
        used.streams.insert(stream_id);
        used.keys.insert(fingerprint); // No replay-ledger eviction.
        Ok(MediaTicket {
            authorization: self.0.clone(),
            stream_id,
            purpose,
            permission_epoch,
            role,
            key,
            deadline: Instant::now() + lifetime,
        })
    }
    pub fn end_session(&self) {
        self.0.ended.store(true, Ordering::SeqCst);
        self.close_invalid();
    }
    pub fn revoke(&self, purpose: Purpose) {
        {
            let mut permissions = lock(&self.0.permissions);
            let permission = &mut permissions[purpose_index(purpose)];
            permission.granted = false;
            permission.epoch = permission.epoch.saturating_add(1);
        }
        self.close_invalid();
    }
    fn close_invalid(&self) {
        let channels: Vec<_> = lock(&self.0.channels)
            .iter()
            .filter_map(Weak::upgrade)
            .collect();
        for channel in channels {
            if let Some(reason) = self.0.invalid(channel.purpose, channel.permission_epoch) {
                channel.close(reason);
            }
        }
    }
}
fn ticket_key_bytes(key: &OneTimeKey) -> &[u8; 32] {
    &key.0
}
impl Authorization {
    fn invalid(&self, purpose: Purpose, epoch: u64) -> Option<CloseReason> {
        let permission = lock(&self.permissions)[purpose_index(purpose)];
        if self.ended.load(Ordering::SeqCst) {
            Some(CloseReason::SessionEnded)
        } else if !permission.granted || permission.epoch != epoch {
            Some(CloseReason::PermissionRevoked)
        } else {
            None
        }
    }
}
/// Private fields, no deserializer and no Clone. Constructed only with authorize_stream.
pub struct MediaTicket {
    authorization: Arc<Authorization>,
    stream_id: u64,
    purpose: Purpose,
    permission_epoch: u64,
    role: Role,
    key: OneTimeKey,
    deadline: Instant,
}
impl MediaTicket {
    fn prologue(&self) -> Vec<u8> {
        let mut result = b"yuejie-connect/media/v1\0".to_vec();
        result.extend_from_slice(&self.authorization.session_id);
        result.extend_from_slice(&self.stream_id.to_be_bytes());
        result.push(self.purpose as u8);
        let (sender, receiver) = match self.role {
            Role::Sender => (
                &self.authorization.local_peer,
                &self.authorization.remote_peer,
            ),
            Role::Receiver => (
                &self.authorization.remote_peer,
                &self.authorization.local_peer,
            ),
        };
        result.extend_from_slice(sender);
        result.extend_from_slice(receiver);
        result
    }
}

/// Exact Java ScreenCastPacket v1 wire bytes. Validation occurs before enqueue and after decryption.
/// No Debug implementation: access-unit contents must not reach routine diagnostics.
pub struct VideoPacket {
    wire: Box<[u8]>,
    stream_id: u64,
    sequence: u64,
    end: bool,
    audio: bool,
    video_sequence: u32,
}
impl VideoPacket {
    pub fn from_wire(wire: Vec<u8>) -> Result<Self, Error> {
        if wire.len() < PACKET_HEADER || wire.len() > PACKET_HEADER + MAX_ACCESS_UNIT {
            return Err(Error::InvalidPacket);
        }
        let flags = u16::from_be_bytes(wire[6..8].try_into().unwrap());
        let stream_id = u64::from_be_bytes(wire[8..16].try_into().unwrap());
        let sequence = u64::from_be_bytes(wire[16..24].try_into().unwrap());
        let pts = u64::from_be_bytes(wire[24..32].try_into().unwrap());
        let len = u32::from_be_bytes(wire[32..36].try_into().unwrap()) as usize;
        let video_sequence = u32::from_be_bytes(wire[36..40].try_into().unwrap());
        if wire[..4] != [0x59, 0x4a, 0x56, 0x31]
            || wire[4..6] != [0, 1]
            || video_sequence > i32::MAX as u32
            || (video_sequence != 0 && (flags == AUDIO_FLAG || u64::from(video_sequence) >= sequence))
            || stream_id == 0
            || stream_id > i64::MAX as u64
            || sequence == 0
            || sequence > i64::MAX as u64
            || pts > i64::MAX as u64
            || len != wire.len() - PACKET_HEADER
            || !match flags {
                0 | 1 | 2 => len > 0,
                4 => len == 0,
                AUDIO_FLAG => len == AUDIO_BYTES,
                _ => false,
            }
        {
            return Err(Error::InvalidPacket);
        }
        Ok(Self {
            wire: wire.into_boxed_slice(),
            stream_id,
            sequence,
            end: flags == 4,
            audio: flags == AUDIO_FLAG,
            video_sequence,
        })
    }
    pub fn as_wire(&self) -> &[u8] {
        &self.wire
    }
    pub fn into_wire(self) -> Vec<u8> {
        self.wire.into_vec()
    }
    pub fn sequence(&self) -> u64 {
        self.sequence
    }
    pub fn stream_id(&self) -> u64 {
        self.stream_id
    }
    pub(crate) fn is_audio(&self) -> bool {
        self.audio
    }
    pub(crate) fn allowed_for(&self, purpose: Purpose) -> bool {
        (!self.is_audio() && self.video_sequence == 0) || purpose == Purpose::ScreenVideo
    }
}

#[derive(Default)]
struct Queue {
    packets: VecDeque<VideoPacket>,
    bytes: usize,
    frames: usize,
    last_sequence: u64,
    end_queued: bool,
}
impl Queue {
    fn reserve(&mut self, bytes: usize) -> bool {
        if bytes > MAX_QUEUED_BYTES
            || self.bytes > MAX_QUEUED_BYTES - bytes
            || self.frames >= MAX_QUEUED_FRAMES
        {
            return false;
        }
        self.bytes += bytes;
        self.frames += 1;
        true
    }
    fn release(&mut self, bytes: usize) {
        self.bytes -= bytes;
        self.frames -= 1;
    }
    fn clear(&mut self) {
        self.packets.clear();
        self.bytes = 0;
        self.frames = 0;
    }
}
struct State {
    queue: Queue,
    closed: Option<CloseReason>,
    notification: Option<mpsc::Sender<CloseReason>>,
}
struct Shared {
    socket: TcpStream,
    authorization: Arc<Authorization>,
    stream_id: u64,
    purpose: Purpose,
    permission_epoch: u64,
    role: Role,
    deadline: Instant,
    state: Mutex<State>,
    changed: Condvar,
}
impl Shared {
    fn close(&self, reason: CloseReason) {
        let notification = {
            let mut state = lock(&self.state);
            if state.closed.is_some() {
                return;
            }
            state.closed = Some(reason);
            state.queue.clear();
            state.notification.take()
        };
        let _ = self.socket.shutdown(Shutdown::Both);
        self.changed.notify_all();
        if let Some(tx) = notification {
            let _ = tx.send(reason);
        }
    }
    fn check(&self) -> Result<(), Error> {
        if let Some(reason) = lock(&self.state).closed {
            return Err(Error::Closed(reason));
        }
        if let Some(reason) = self
            .authorization
            .invalid(self.purpose, self.permission_epoch)
        {
            self.close(reason);
            return Err(Error::Closed(reason));
        }
        if Instant::now() >= self.deadline {
            self.close(CloseReason::Expired);
            return Err(Error::Closed(CloseReason::Expired));
        }
        Ok(())
    }
    fn finish_sent(&self, bytes: usize) {
        let mut state = lock(&self.state);
        if state.closed.is_none() {
            state.queue.release(bytes);
        }
        self.changed.notify_all();
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct QueueUsage {
    pub frames: usize,
    pub bytes: usize,
}

pub struct MediaChannel {
    shared: Arc<Shared>,
    workers: Vec<JoinHandle<()>>,
}
impl MediaChannel {
    /// Blocking handshake (at most eight seconds), so run off Binder/UI threads. Socket addressing
    /// is supplied by the authenticated control plane. Sender initiates; receiver responds.
    /// Returned notification fires once on actual local/remote termination, never on enqueue/render.
    pub fn establish(
        mut socket: TcpStream,
        ticket: MediaTicket,
    ) -> Result<(Self, mpsc::Receiver<CloseReason>), Error> {
        socket
            .set_nodelay(true)
            .map_err(|_| Error::Closed(CloseReason::TransportFailed))?;
        socket
            .set_read_timeout(Some(IO_POLL))
            .map_err(|_| Error::Closed(CloseReason::TransportFailed))?;
        socket
            .set_write_timeout(Some(IO_POLL))
            .map_err(|_| Error::Closed(CloseReason::TransportFailed))?;
        let (tx, rx) = mpsc::channel();
        let shared = Arc::new(Shared {
            socket: socket
                .try_clone()
                .map_err(|_| Error::Closed(CloseReason::TransportFailed))?,
            authorization: ticket.authorization.clone(),
            stream_id: ticket.stream_id,
            purpose: ticket.purpose,
            permission_epoch: ticket.permission_epoch,
            role: ticket.role,
            deadline: ticket.deadline,
            state: Mutex::new(State {
                queue: Queue::default(),
                closed: None,
                notification: Some(tx),
            }),
            changed: Condvar::new(),
        });
        {
            // Registration and subsequent check cover revoke racing with establish.
            let mut channels = lock(&ticket.authorization.channels);
            channels.retain(|value| value.strong_count() != 0);
            channels.push(Arc::downgrade(&shared));
        }
        let setup = (|| {
            shared.check()?;
            let prologue = ticket.prologue();
            let params = PATTERN.parse().map_err(|_| Error::InvalidScope)?;
            let builder = snow::Builder::new(params)
                .prologue(&prologue)
                .psk(0, &ticket.key.0);
            let mut handshake = match ticket.role {
                Role::Sender => builder.build_initiator(),
                Role::Receiver => builder.build_responder(),
            }
            .map_err(|_| Error::InvalidScope)?;
            let deadline = (Instant::now() + HANDSHAKE_TIMEOUT).min(ticket.deadline);
            let mut reader = RecordReader::default();
            let mut buffer = vec![0; MAX_RECORD];
            while !handshake.is_handshake_finished() {
                shared.check()?;
                if Instant::now() >= deadline {
                    return Err(Error::Closed(CloseReason::IdleTimeout));
                }
                if handshake.is_my_turn() {
                    let size = handshake
                        .write_message(&[], &mut buffer)
                        .map_err(|_| Error::Closed(CloseReason::AuthenticationFailed))?;
                    write_record(&mut socket, &buffer[..size], &shared, deadline)?;
                } else if let Some(record) = reader.poll(&mut socket)? {
                    let size = handshake
                        .read_message(&record, &mut buffer)
                        .map_err(|_| Error::Closed(CloseReason::AuthenticationFailed))?;
                    if size != 0 {
                        return Err(Error::Closed(CloseReason::ProtocolViolation));
                    }
                }
            }
            shared.check()?;
            handshake
                .into_transport_mode()
                .map_err(|_| Error::Closed(CloseReason::AuthenticationFailed))
        })();
        let noise = match setup {
            Ok(noise) => Arc::new(Mutex::new(noise)),
            Err(error) => {
                shared.close(close_reason(error));
                return Err(error);
            }
        };
        drop(ticket); // One-use key is no longer needed; no reusable ticket survives setup.
        let mut read_socket = match socket.try_clone() {
            Ok(value) => value,
            Err(_) => {
                shared.close(CloseReason::TransportFailed);
                return Err(Error::Closed(CloseReason::TransportFailed));
            }
        };
        let write_shared = shared.clone();
        let write_noise = noise.clone();
        let writer = thread::Builder::new()
            .name("yj-media-send".into())
            .spawn(move || {
                let result = send_loop(&mut socket, &write_shared, &write_noise);
                write_shared.close(
                    result
                        .err()
                        .map(close_reason)
                        .unwrap_or(CloseReason::LocalEnded),
                );
            })
            .map_err(|_| {
                shared.close(CloseReason::TransportFailed);
                Error::Closed(CloseReason::TransportFailed)
            })?;
        let read_shared = shared.clone();
        let reader = match thread::Builder::new()
            .name("yj-media-receive".into())
            .spawn(move || {
                let result = receive_loop(&mut read_socket, &read_shared, &noise);
                read_shared.close(
                    result
                        .err()
                        .map(close_reason)
                        .unwrap_or(CloseReason::RemoteEnded),
                );
            }) {
            Ok(value) => value,
            Err(_) => {
                shared.close(CloseReason::TransportFailed);
                let _ = writer.join();
                return Err(Error::Closed(CloseReason::TransportFailed));
            }
        };
        Ok((
            Self {
                shared,
                workers: vec![writer, reader],
            },
            rx,
        ))
    }

    /// True means bounded LOCAL ENQUEUE only. Full, closed, wrong-direction and replay return false.
    /// A packet rejected by backpressure is not partially transmitted. No rendered/business ack.
    pub fn offer(&self, packet: VideoPacket) -> bool {
        if self.shared.role != Role::Sender
            || packet.stream_id != self.shared.stream_id
            || !packet.allowed_for(self.shared.purpose)
            || self.shared.check().is_err()
        {
            return false;
        }
        let mut state = lock(&self.shared.state);
        if state.closed.is_some()
            || state.queue.end_queued
            || packet.sequence <= state.queue.last_sequence
            || !state.queue.reserve(packet.wire.len())
        {
            return false;
        }
        state.queue.last_sequence = packet.sequence;
        state.queue.end_queued = packet.end;
        state.queue.packets.push_back(packet);
        self.shared.changed.notify_all();
        true
    }
    pub fn poll_packet(&self) -> Result<Option<VideoPacket>, Error> {
        self.read_packet(Duration::ZERO)
    }
    /// Consumer timeout returns None; transport timeout/revocation returns Closed. Calling this does
    /// not acknowledge decoding or rendering. Receiver only; total waiting cannot extend authority.
    pub fn read_packet(&self, timeout: Duration) -> Result<Option<VideoPacket>, Error> {
        if self.shared.role != Role::Receiver {
            return Err(Error::InvalidScope);
        }
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or(Error::InvalidScope)?;
        loop {
            self.shared.check()?;
            let mut state = lock(&self.shared.state);
            if let Some(reason) = state.closed {
                return Err(Error::Closed(reason));
            }
            if let Some(packet) = state.queue.packets.pop_front() {
                state.queue.release(packet.wire.len());
                self.shared.changed.notify_all();
                return Ok(Some(packet));
            }
            let now = Instant::now();
            if now >= deadline {
                return Ok(None);
            }
            let wait = (deadline - now).min(IO_POLL);
            let _guard = self
                .shared
                .changed
                .wait_timeout(state, wait)
                .unwrap_or_else(|p| p.into_inner());
        }
    }
    pub fn queue_usage(&self) -> QueueUsage {
        let state = lock(&self.shared.state);
        QueueUsage {
            frames: state.queue.frames,
            bytes: state.queue.bytes,
        }
    }
    pub fn close_reason(&self) -> Option<CloseReason> {
        lock(&self.shared.state).closed
    }
    pub fn close(&self) {
        self.shared.close(CloseReason::LocalClosed);
    }
}
impl Drop for MediaChannel {
    fn drop(&mut self) {
        self.close();
        for worker in self.workers.drain(..) {
            let _ = worker.join();
        }
    }
}
fn close_reason(error: Error) -> CloseReason {
    match error {
        Error::Closed(reason) => reason,
        _ => CloseReason::ProtocolViolation,
    }
}

fn write_record(
    socket: &mut TcpStream,
    record: &[u8],
    shared: &Shared,
    deadline: Instant,
) -> Result<(), Error> {
    if record.is_empty() || record.len() > MAX_RECORD {
        return Err(Error::InvalidPacket);
    }
    for bytes in [&(record.len() as u32).to_be_bytes()[..], record] {
        let mut offset = 0;
        while offset < bytes.len() {
            shared.check()?;
            if Instant::now() >= deadline {
                return Err(Error::Closed(CloseReason::BackpressureTimeout));
            }
            match socket.write(&bytes[offset..]) {
                Ok(0) => return Err(Error::Closed(CloseReason::PeerClosed)),
                Ok(count) => offset += count,
                Err(error)
                    if matches!(
                        error.kind(),
                        std::io::ErrorKind::TimedOut
                            | std::io::ErrorKind::WouldBlock
                            | std::io::ErrorKind::Interrupted
                    ) => {}
                Err(_) => return Err(Error::Closed(CloseReason::TransportFailed)),
            }
        }
    }
    Ok(())
}
#[derive(Default)]
struct RecordReader {
    bytes: Vec<u8>,
}
impl RecordReader {
    fn poll(&mut self, socket: &mut impl Read) -> Result<Option<Vec<u8>>, Error> {
        let target = if self.bytes.len() < 4 {
            4
        } else {
            let size = u32::from_be_bytes(self.bytes[..4].try_into().unwrap()) as usize;
            if size == 0 || size > MAX_RECORD {
                return Err(Error::InvalidPacket);
            }
            size + 4
        };
        let mut buffer = [0; 8192];
        let cap = (target - self.bytes.len()).min(buffer.len());
        match socket.read(&mut buffer[..cap]) {
            Ok(0) => return Err(Error::Closed(CloseReason::PeerClosed)),
            Ok(count) => self.bytes.extend_from_slice(&buffer[..count]),
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::TimedOut
                        | std::io::ErrorKind::WouldBlock
                        | std::io::ErrorKind::Interrupted
                ) =>
            {
                return Ok(None)
            }
            Err(_) => return Err(Error::Closed(CloseReason::TransportFailed)),
        }
        if self.bytes.len() >= 4 {
            let size = u32::from_be_bytes(self.bytes[..4].try_into().unwrap()) as usize;
            if size == 0 || size > MAX_RECORD {
                return Err(Error::InvalidPacket);
            }
            if self.bytes.len() == size + 4 {
                let record = self.bytes.split_off(4);
                self.bytes.clear();
                return Ok(Some(record));
            }
        }
        Ok(None)
    }
}
fn encrypted_write(
    socket: &mut TcpStream,
    plain: &[u8],
    shared: &Shared,
    noise: &Mutex<snow::TransportState>,
) -> Result<(), Error> {
    let mut cipher = vec![0; plain.len() + 16];
    let size = lock(noise)
        .write_message(plain, &mut cipher)
        .map_err(|_| Error::Closed(CloseReason::AuthenticationFailed))?;
    write_record(
        socket,
        &cipher[..size],
        shared,
        Instant::now() + STALL_TIMEOUT,
    )
}
fn send_loop(
    socket: &mut TcpStream,
    shared: &Shared,
    noise: &Mutex<snow::TransportState>,
) -> Result<(), Error> {
    let mut last_write = Instant::now();
    loop {
        shared.check()?;
        let packet = if shared.role == Role::Sender {
            lock(&shared.state).queue.packets.pop_front()
        } else {
            None
        };
        if let Some(packet) = packet {
            for (index, chunk) in packet.wire.chunks(CHUNK).enumerate() {
                shared.check()?;
                let mut plain = Vec::with_capacity(FRAGMENT_HEADER + chunk.len());
                plain.push(1);
                plain.extend_from_slice(&packet.sequence.to_be_bytes());
                plain.extend_from_slice(&(packet.wire.len() as u32).to_be_bytes());
                plain.extend_from_slice(&((index * CHUNK) as u32).to_be_bytes());
                plain.extend_from_slice(chunk);
                encrypted_write(socket, &plain, shared, noise)?;
            }
            shared.finish_sent(packet.wire.len());
            last_write = Instant::now();
            if packet.end {
                return Ok(());
            }
        } else {
            if last_write.elapsed() >= KEEPALIVE_INTERVAL {
                encrypted_write(socket, &[2], shared, noise)?;
                last_write = Instant::now();
            }
            let state = lock(&shared.state);
            let _guard = shared
                .changed
                .wait_timeout(state, IO_POLL)
                .unwrap_or_else(|p| p.into_inner());
        }
    }
}
struct Assembly {
    sequence: u64,
    total: usize,
    bytes: Vec<u8>,
    started: Instant,
}
fn reserve_receive(shared: &Shared, total: usize) -> Result<(), Error> {
    let deadline = Instant::now() + STALL_TIMEOUT;
    loop {
        shared.check()?;
        let mut state = lock(&shared.state);
        if state.closed.is_some() {
            continue;
        }
        if state.queue.reserve(total) {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(Error::Closed(CloseReason::BackpressureTimeout));
        }
        let _guard = shared
            .changed
            .wait_timeout(state, IO_POLL)
            .unwrap_or_else(|p| p.into_inner());
    }
}
fn receive_loop(
    socket: &mut TcpStream,
    shared: &Shared,
    noise: &Mutex<snow::TransportState>,
) -> Result<(), Error> {
    let mut reader = RecordReader::default();
    let mut plain = vec![0; MAX_RECORD];
    let mut assembly: Option<Assembly> = None;
    let mut last_read = Instant::now();
    let mut last_sequence = 0;
    loop {
        shared.check()?;
        if last_read.elapsed() >= STALL_TIMEOUT {
            return Err(Error::Closed(CloseReason::IdleTimeout));
        }
        if assembly
            .as_ref()
            .is_some_and(|frame| frame.started.elapsed() >= STALL_TIMEOUT)
        {
            return Err(Error::Closed(CloseReason::IdleTimeout));
        }
        let Some(record) = reader.poll(socket)? else {
            continue;
        };
        let size = lock(noise)
            .read_message(&record, &mut plain)
            .map_err(|_| Error::Closed(CloseReason::AuthenticationFailed))?;
        last_read = Instant::now();
        let data = &plain[..size];
        if data == [2] {
            continue;
        }
        if shared.role != Role::Receiver || data.len() <= FRAGMENT_HEADER || data[0] != 1 {
            return Err(Error::InvalidPacket);
        }
        let sequence = u64::from_be_bytes(data[1..9].try_into().unwrap());
        let total = u32::from_be_bytes(data[9..13].try_into().unwrap()) as usize;
        let offset = u32::from_be_bytes(data[13..17].try_into().unwrap()) as usize;
        let chunk = &data[FRAGMENT_HEADER..];
        if sequence <= last_sequence
            || !(PACKET_HEADER..=MAX_ACCESS_UNIT + PACKET_HEADER).contains(&total)
            || chunk.len() > CHUNK
            || offset > total
            || chunk.len() > total - offset
        {
            return Err(Error::InvalidPacket);
        }
        if assembly.is_none() {
            if offset != 0 {
                return Err(Error::InvalidPacket);
            }
            reserve_receive(shared, total)?;
            assembly = Some(Assembly {
                sequence,
                total,
                bytes: Vec::with_capacity(total),
                started: Instant::now(),
            });
        }
        let current = assembly.as_mut().unwrap();
        if current.sequence != sequence || current.total != total || current.bytes.len() != offset {
            return Err(Error::InvalidPacket);
        }
        current.bytes.extend_from_slice(chunk);
        if current.bytes.len() == total {
            let packet = VideoPacket::from_wire(assembly.take().unwrap().bytes)?;
            if packet.stream_id != shared.stream_id || packet.sequence != sequence
                || !packet.allowed_for(shared.purpose) {
                return Err(Error::InvalidPacket);
            }
            last_sequence = sequence;
            if packet.end {
                return Ok(());
            }
            let mut state = lock(&shared.state);
            if state.closed.is_none() {
                state.queue.packets.push_back(packet);
                shared.changed.notify_all();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn packet(sequence: u64, size: usize, flags: u16) -> VideoPacket {
        let mut wire = Vec::with_capacity(PACKET_HEADER + size);
        wire.extend_from_slice(&[0x59, 0x4a, 0x56, 0x31, 0, 1]);
        wire.extend_from_slice(&flags.to_be_bytes());
        wire.extend_from_slice(&9u64.to_be_bytes());
        wire.extend_from_slice(&sequence.to_be_bytes());
        wire.extend_from_slice(&(sequence * 33_333).to_be_bytes());
        wire.extend_from_slice(&(size as u32).to_be_bytes());
        wire.extend_from_slice(&[0; 4]);
        wire.resize(PACKET_HEADER + size, 0x5a);
        VideoPacket::from_wire(wire).unwrap()
    }
    fn auth(local: u8, remote: u8) -> SessionAuthorization {
        let auth = SessionAuthorization::confirmed([11; 32], [local; 32], [remote; 32]).unwrap();
        auth.grant_after_consent(Purpose::ScreenVideo).unwrap();
        auth.grant_after_consent(Purpose::CameraVideo).unwrap();
        auth
    }
    fn ticket(
        auth: &SessionAuthorization,
        role: Role,
        purpose: Purpose,
        key: [u8; 32],
        lifetime: Duration,
    ) -> MediaTicket {
        auth.authorize_stream(
            9,
            purpose,
            role,
            OneTimeKey::from_authenticated_control(key).unwrap(),
            lifetime,
        )
        .unwrap()
    }
    struct Pair {
        sender: MediaChannel,
        receiver: MediaChannel,
        sender_auth: SessionAuthorization,
        receiver_auth: SessionAuthorization,
        sent_end: mpsc::Receiver<CloseReason>,
        received_end: mpsc::Receiver<CloseReason>,
    }
    fn pair(lifetime: Duration) -> Pair {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let sender_auth = auth(1, 2);
        let receiver_auth = auth(2, 1);
        let key = OneTimeKey::generate().unwrap().for_authenticated_control();
        let receive = ticket(
            &receiver_auth,
            Role::Receiver,
            Purpose::ScreenVideo,
            key,
            Duration::from_secs(30),
        );
        let accept = thread::spawn(move || {
            MediaChannel::establish(listener.accept().unwrap().0, receive).unwrap()
        });
        let send = ticket(
            &sender_auth,
            Role::Sender,
            Purpose::ScreenVideo,
            key,
            lifetime,
        );
        let (sender, sent_end) =
            MediaChannel::establish(TcpStream::connect(addr).unwrap(), send).unwrap();
        let (receiver, received_end) = accept.join().unwrap();
        Pair {
            sender,
            receiver,
            sender_auth,
            receiver_auth,
            sent_end,
            received_end,
        }
    }

    #[test]
    fn java_wire_bounds_and_flags_are_strict() {
        for (offset, value) in [
            (0, 0),
            (4, 1),
            (7, 3),
            (8, 0x80),
            (16, 0x80),
            (24, 0x80),
            (36, 1),
        ] {
            let mut bad = packet(1, 16, 1).into_wire();
            bad[offset] = value;
            assert!(matches!(
                VideoPacket::from_wire(bad),
                Err(Error::InvalidPacket)
            ));
        }
        assert!(VideoPacket::from_wire(vec![0; MAX_ACCESS_UNIT + PACKET_HEADER + 1]).is_err());
        let mut bad = packet(1, 1, 0).into_wire();
        bad.push(1);
        assert!(VideoPacket::from_wire(bad).is_err());
        assert!(packet(1, MAX_ACCESS_UNIT, 1).wire.len() > MAX_RECORD);
    }
    #[test]
    fn screen_pcm_and_video_share_authenticated_packet_order() {
        let pair = pair(Duration::from_secs(30));
        let audio = packet(2, AUDIO_BYTES, AUDIO_FLAG);
        assert!(!audio.allowed_for(Purpose::CameraVideo));
        let mut bad = packet(2, AUDIO_BYTES - 1, 0).into_wire();
        bad[7] = AUDIO_FLAG as u8;
        assert!(matches!(VideoPacket::from_wire(bad), Err(Error::InvalidPacket)));
        let mut wire = packet(3, 64, 0).into_wire();
        wire[39] = 2;
        let video = VideoPacket::from_wire(wire).unwrap();
        assert!(!video.allowed_for(Purpose::CameraVideo));
        for packet in [packet(1, 64, 1), audio, video] {
            let seq = packet.sequence();
            let expected = packet.as_wire().to_vec();
            assert!(pair.sender.offer(packet));
            let received = pair.receiver.read_packet(Duration::from_secs(2)).unwrap().unwrap();
            assert_eq!(received.sequence(), seq);
            assert_eq!(received.as_wire(), expected);
        }
    }
    #[test]
    fn max_access_unit_crosses_real_noise_tcp_fragments_without_business_ack() {
        let pair = pair(Duration::from_secs(30));
        let expected = packet(1, MAX_ACCESS_UNIT, 1);
        let digest = Sha256::digest(expected.as_wire());
        assert!(pair.sender.offer(expected));
        let actual = pair
            .receiver
            .read_packet(Duration::from_secs(4))
            .unwrap()
            .unwrap();
        assert_eq!(actual.as_wire().len(), MAX_ACCESS_UNIT + PACKET_HEADER);
        assert_eq!(Sha256::digest(actual.as_wire()), digest);
        assert!(pair.sent_end.try_recv().is_err());
        assert!(pair.received_end.try_recv().is_err());
        assert!(pair.receiver.poll_packet().unwrap().is_none());
        assert!(!pair.receiver.offer(packet(2, 16, 0))); // Direction is part of authority.
        assert!(matches!(
            pair.sender.poll_packet(),
            Err(Error::InvalidScope)
        ));
    }
    #[test]
    fn sender_backpressure_and_reserved_inflight_bytes_remain_bounded() {
        let pair = pair(Duration::from_secs(30));
        // Deliberately do not read receiver packets. Local enqueue must refuse without fake success.
        let mut refused = false;
        for sequence in 1..=64 {
            if !pair.sender.offer(packet(sequence, MAX_ACCESS_UNIT, 1)) {
                refused = true;
                break;
            }
            let usage = pair.sender.queue_usage();
            assert!(usage.frames <= MAX_QUEUED_FRAMES && usage.bytes <= MAX_QUEUED_BYTES);
        }
        assert!(refused);
        pair.sender_auth.end_session();
        assert_eq!(
            pair.sent_end.recv_timeout(Duration::from_secs(1)).unwrap(),
            CloseReason::SessionEnded
        );
        assert_eq!(
            pair.sender.queue_usage(),
            QueueUsage {
                frames: 0,
                bytes: 0
            }
        );
        assert!(!pair.sender.offer(packet(100, 16, 1)));
        assert!(pair.sent_end.try_recv().is_err()); // One terminal notification.
    }
    #[test]
    fn packet_count_limit_includes_popped_inflight_frame() {
        let mut queue = Queue::default();
        for i in 1..=MAX_QUEUED_FRAMES {
            let p = packet(i as u64, 1, 0);
            assert!(queue.reserve(p.wire.len()));
            queue.packets.push_back(p);
        }
        let inflight = queue.packets.pop_front().unwrap();
        assert!(!queue.reserve(PACKET_HEADER + 1));
        queue.release(inflight.wire.len());
        assert!(queue.reserve(PACKET_HEADER + 1));
        assert_eq!(queue.frames, MAX_QUEUED_FRAMES);
    }
    #[test]
    fn replay_ledger_never_reuses_stream_or_secret_and_revocation_is_per_purpose() {
        let authority = auth(1, 2);
        drop(ticket(
            &authority,
            Role::Sender,
            Purpose::ScreenVideo,
            [7; 32],
            Duration::from_secs(1),
        ));
        assert!(matches!(
            authority.authorize_stream(
                9,
                Purpose::CameraVideo,
                Role::Sender,
                OneTimeKey([8; 32]),
                Duration::from_secs(1)
            ),
            Err(Error::AlreadyUsed)
        ));
        assert!(matches!(
            authority.authorize_stream(
                10,
                Purpose::ScreenVideo,
                Role::Sender,
                OneTimeKey([7; 32]),
                Duration::from_secs(1)
            ),
            Err(Error::AlreadyUsed)
        ));
        authority.revoke(Purpose::ScreenVideo);
        assert!(matches!(
            authority.authorize_stream(
                10,
                Purpose::ScreenVideo,
                Role::Sender,
                OneTimeKey([9; 32]),
                Duration::from_secs(1)
            ),
            Err(Error::Closed(CloseReason::PermissionRevoked))
        ));
        assert!(authority
            .authorize_stream(
                10,
                Purpose::CameraVideo,
                Role::Sender,
                OneTimeKey([9; 32]),
                Duration::from_secs(1)
            )
            .is_ok());
        authority.end_session();
        assert!(matches!(
            authority.authorize_stream(
                11,
                Purpose::CameraVideo,
                Role::Sender,
                OneTimeKey([10; 32]),
                Duration::from_secs(1)
            ),
            Err(Error::Closed(CloseReason::SessionEnded))
        ));
    }
    #[test]
    fn pairing_alone_is_not_permission_and_regrant_cannot_revive_old_ticket() {
        let authority = SessionAuthorization::confirmed([11; 32], [1; 32], [2; 32]).unwrap();
        assert!(matches!(
            authority.authorize_stream(
                9,
                Purpose::ScreenVideo,
                Role::Sender,
                OneTimeKey([7; 32]),
                Duration::from_secs(5)
            ),
            Err(Error::Closed(CloseReason::PermissionRevoked))
        ));
        authority.grant_after_consent(Purpose::ScreenVideo).unwrap();
        let old = ticket(
            &authority,
            Role::Sender,
            Purpose::ScreenVideo,
            [7; 32],
            Duration::from_secs(5),
        );
        authority.revoke(Purpose::ScreenVideo);
        authority.grant_after_consent(Purpose::ScreenVideo).unwrap();
        assert_eq!(
            old.authorization.invalid(old.purpose, old.permission_epoch),
            Some(CloseReason::PermissionRevoked)
        );
        assert!(authority
            .authorize_stream(
                10,
                Purpose::ScreenVideo,
                Role::Sender,
                OneTimeKey([8; 32]),
                Duration::from_secs(5)
            )
            .is_ok());
        assert!(matches!(
            authority.authorize_stream(
                9,
                Purpose::ScreenVideo,
                Role::Sender,
                OneTimeKey([9; 32]),
                Duration::from_secs(5)
            ),
            Err(Error::AlreadyUsed)
        ));
    }
    #[test]
    fn wrong_psk_session_purpose_and_direction_fail_handshake() {
        for mismatch in 0..4 {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let addr = listener.local_addr().unwrap();
            let remote = if mismatch == 1 {
                let remote = SessionAuthorization::confirmed([12; 32], [2; 32], [1; 32]).unwrap();
                remote.grant_after_consent(Purpose::ScreenVideo).unwrap();
                remote
            } else {
                auth(2, 1)
            };
            let receive = ticket(
                &remote,
                if mismatch == 3 {
                    Role::Sender
                } else {
                    Role::Receiver
                },
                if mismatch == 2 {
                    Purpose::CameraVideo
                } else {
                    Purpose::ScreenVideo
                },
                if mismatch == 0 { [8; 32] } else { [7; 32] },
                Duration::from_secs(5),
            );
            let accept = thread::spawn(move || {
                MediaChannel::establish(listener.accept().unwrap().0, receive).is_err()
            });
            let send = ticket(
                &auth(1, 2),
                Role::Sender,
                Purpose::ScreenVideo,
                [7; 32],
                Duration::from_secs(5),
            );
            assert!(MediaChannel::establish(TcpStream::connect(addr).unwrap(), send).is_err());
            assert!(accept.join().unwrap());
        }
    }
    #[test]
    fn expiry_and_permission_revoke_close_sockets_and_release_queued_payload() {
        let expired = pair(Duration::from_millis(700));
        assert_eq!(
            expired
                .sent_end
                .recv_timeout(Duration::from_secs(2))
                .unwrap(),
            CloseReason::Expired
        );
        assert!(!expired.sender.offer(packet(1, 16, 1)));
        let pair = pair(Duration::from_secs(30));
        pair.receiver_auth.revoke(Purpose::ScreenVideo);
        assert_eq!(
            pair.received_end
                .recv_timeout(Duration::from_secs(1))
                .unwrap(),
            CloseReason::PermissionRevoked
        );
        assert_eq!(
            pair.receiver.queue_usage(),
            QueueUsage {
                frames: 0,
                bytes: 0
            }
        );
        assert!(matches!(
            pair.receiver.poll_packet(),
            Err(Error::Closed(CloseReason::PermissionRevoked))
        ));
        assert!(pair.sent_end.recv_timeout(Duration::from_secs(1)).is_ok());
    }
    #[test]
    fn end_packet_closes_without_becoming_a_render_receipt() {
        let pair = pair(Duration::from_secs(30));
        assert!(pair.sender.offer(packet(1, 0, 4)));
        assert!(!pair.sender.offer(packet(2, 16, 0)));
        assert_eq!(
            pair.sent_end.recv_timeout(Duration::from_secs(1)).unwrap(),
            CloseReason::LocalEnded
        );
        // TCP orderly close may be noticed by the heartbeat writer first; either is a real closure.
        let reason = pair
            .received_end
            .recv_timeout(Duration::from_secs(1))
            .unwrap();
        assert!(matches!(
            reason,
            CloseReason::RemoteEnded | CloseReason::PeerClosed | CloseReason::TransportFailed
        ));
        assert_eq!(
            pair.receiver.queue_usage(),
            QueueUsage {
                frames: 0,
                bytes: 0
            }
        );
    }
    fn raw_sender() -> (
        TcpStream,
        snow::TransportState,
        MediaChannel,
        mpsc::Receiver<CloseReason>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let receive = ticket(
            &auth(2, 1),
            Role::Receiver,
            Purpose::ScreenVideo,
            [7; 32],
            Duration::from_secs(10),
        );
        let accept = thread::spawn(move || {
            MediaChannel::establish(listener.accept().unwrap().0, receive).unwrap()
        });
        let send = ticket(
            &auth(1, 2),
            Role::Sender,
            Purpose::ScreenVideo,
            [7; 32],
            Duration::from_secs(10),
        );
        let prologue = send.prologue();
        let mut handshake = snow::Builder::new(PATTERN.parse().unwrap())
            .prologue(&prologue)
            .psk(0, &send.key.0)
            .build_initiator()
            .unwrap();
        let mut socket = TcpStream::connect(addr).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut buffer = vec![0; MAX_RECORD];
        let size = handshake.write_message(&[], &mut buffer).unwrap();
        socket.write_all(&(size as u32).to_be_bytes()).unwrap();
        socket.write_all(&buffer[..size]).unwrap();
        let mut header = [0; 4];
        socket.read_exact(&mut header).unwrap();
        let size = u32::from_be_bytes(header) as usize;
        socket.read_exact(&mut buffer[..size]).unwrap();
        let mut output = vec![0; MAX_RECORD];
        handshake
            .read_message(&buffer[..size], &mut output)
            .unwrap();
        let (channel, end) = accept.join().unwrap();
        (
            socket,
            handshake.into_transport_mode().unwrap(),
            channel,
            end,
        )
    }
    fn raw_write(
        socket: &mut TcpStream,
        noise: &mut snow::TransportState,
        plain: &[u8],
        tamper: bool,
    ) {
        let mut cipher = vec![0; plain.len() + 16];
        let n = noise.write_message(plain, &mut cipher).unwrap();
        if tamper {
            cipher[n - 1] ^= 1;
        }
        socket.write_all(&(n as u32).to_be_bytes()).unwrap();
        socket.write_all(&cipher[..n]).unwrap();
    }
    #[test]
    fn ciphertext_tamper_and_authenticated_oversize_are_rejected_before_delivery() {
        for tamper in [true, false] {
            let (mut socket, mut noise, channel, end) = raw_sender();
            let mut plain = vec![1];
            plain.extend_from_slice(&1u64.to_be_bytes());
            plain.extend_from_slice(&((MAX_ACCESS_UNIT + PACKET_HEADER + 1) as u32).to_be_bytes());
            plain.extend_from_slice(&0u32.to_be_bytes());
            plain.push(1);
            raw_write(&mut socket, &mut noise, &plain, tamper);
            assert_eq!(
                end.recv_timeout(Duration::from_secs(1)).unwrap(),
                if tamper {
                    CloseReason::AuthenticationFailed
                } else {
                    CloseReason::ProtocolViolation
                }
            );
            assert_eq!(
                channel.queue_usage(),
                QueueUsage {
                    frames: 0,
                    bytes: 0
                }
            );
        }
    }
    #[test]
    fn fragment_overlap_is_rejected_and_reservation_released() {
        let (mut socket, mut noise, channel, end) = raw_sender();
        let mut plain = vec![1];
        plain.extend_from_slice(&1u64.to_be_bytes());
        plain.extend_from_slice(&100u32.to_be_bytes());
        plain.extend_from_slice(&0u32.to_be_bytes());
        plain.extend_from_slice(&[0; 20]);
        raw_write(&mut socket, &mut noise, &plain, false);
        raw_write(&mut socket, &mut noise, &plain, false);
        assert_eq!(
            end.recv_timeout(Duration::from_secs(1)).unwrap(),
            CloseReason::ProtocolViolation
        );
        assert_eq!(
            channel.queue_usage(),
            QueueUsage {
                frames: 0,
                bytes: 0
            }
        );
    }
    #[test]
    fn keepalives_cannot_extend_an_incomplete_frame_deadline() {
        let (mut socket, mut noise, channel, end) = raw_sender();
        let mut plain = vec![1];
        plain.extend_from_slice(&1u64.to_be_bytes());
        plain.extend_from_slice(&100u32.to_be_bytes());
        plain.extend_from_slice(&0u32.to_be_bytes());
        plain.extend_from_slice(&[0; 20]);
        raw_write(&mut socket, &mut noise, &plain, false);
        let deadline = Instant::now() + STALL_TIMEOUT + Duration::from_secs(1);
        loop {
            match end.recv_timeout(Duration::from_millis(200)) {
                Ok(reason) => {
                    assert_eq!(reason, CloseReason::IdleTimeout);
                    break;
                }
                Err(mpsc::RecvTimeoutError::Timeout) if Instant::now() < deadline => {
                    raw_write(&mut socket, &mut noise, &[2], false)
                }
                _ => panic!("incomplete frame did not expire"),
            }
        }
        assert_eq!(
            channel.queue_usage(),
            QueueUsage {
                frames: 0,
                bytes: 0
            }
        );
    }
    #[test]
    fn partial_record_survives_timeouts_and_unchecked_length_never_allocates() {
        struct Trickle {
            data: VecDeque<u8>,
            timeout: bool,
        }
        impl Read for Trickle {
            fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
                self.timeout = !self.timeout;
                if self.timeout {
                    return Err(std::io::ErrorKind::TimedOut.into());
                }
                if let Some(byte) = self.data.pop_front() {
                    out[0] = byte;
                    Ok(1)
                } else {
                    Ok(0)
                }
            }
        }
        let mut input = Trickle {
            data: vec![0, 0, 0, 3, 7, 8, 9].into(),
            timeout: false,
        };
        let mut reader = RecordReader::default();
        let mut result = None;
        for _ in 0..14 {
            if let Some(frame) = reader.poll(&mut input).unwrap() {
                result = Some(frame);
                break;
            }
        }
        assert_eq!(result, Some(vec![7, 8, 9]));
        let mut bad = &[255, 255, 255, 255][..];
        assert!(RecordReader::default().poll(&mut bad).is_err());
    }
}
