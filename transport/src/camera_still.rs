//! One authorized JPEG exposure over a fresh NNpsk0 connection. This is not an AVC purpose.
//! The confirmed parent control-session owner supplies identity, per-request consent and the PSK.
//! A complete authenticated frame is transport evidence only, never a native-consumption receipt.
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant};

pub const MAX_JPEG_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_REQUESTS: usize = 128;
const MAX_RECORD: usize = 65_535;
const CHUNK: usize = 60_000;
const POLL: Duration = Duration::from_millis(50);
const MAX_LIFETIME: Duration = Duration::from_secs(30);
const PATTERN: &str = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";

fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Sender,
    Receiver,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    InvalidScope,
    InvalidJpeg,
    InvalidRole,
    InvalidRecord,
    AlreadyUsed,
    Busy,
    Capacity,
    EntropyUnavailable,
    ParentEnded,
    Cancelled,
    Expired,
    WrongPeer,
    AuthenticationFailed,
    TransportFailed,
}
impl std::fmt::Display for Error {
    fn fmt(&self, out: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(out, "{self:?}")
    }
}
impl std::error::Error for Error {}

#[derive(Clone, PartialEq, Eq)]
pub struct StillRequest {
    stream_id: u64,
    request_id: String,
    camera_id: String,
    width: u32,
    height: u32,
    orientation: u16,
}
impl StillRequest {
    pub fn new(
        stream_id: u64,
        request_id: String,
        camera_id: String,
        width: u32,
        height: u32,
        orientation: u16,
    ) -> Result<Self, Error> {
        if stream_id == 0
            || stream_id > i64::MAX as u64
            || !identity(&request_id)
            || !identity(&camera_id)
            || width == 0
            || height == 0
            || width > 8192
            || height > 8192
            || u64::from(width) * u64::from(height) > 32_000_000
            || ![0, 90, 180, 270].contains(&orientation)
        {
            return Err(Error::InvalidScope);
        }
        Ok(Self {
            stream_id,
            request_id,
            camera_id,
            width,
            height,
            orientation,
        })
    }
    pub fn stream_id(&self) -> u64 {
        self.stream_id
    }
    pub fn request_id(&self) -> &str {
        &self.request_id
    }
    pub fn camera_id(&self) -> &str {
        &self.camera_id
    }
    pub fn width(&self) -> u32 {
        self.width
    }
    pub fn height(&self) -> u32 {
        self.height
    }
    pub fn orientation(&self) -> u16 {
        self.orientation
    }
    fn fingerprint(&self) -> [u8; 32] {
        let mut hash = Sha256::new();
        hash.update(self.stream_id.to_be_bytes());
        for value in [&self.request_id, &self.camera_id] {
            hash.update((value.len() as u16).to_be_bytes());
            hash.update(value.as_bytes());
        }
        hash.update(self.width.to_be_bytes());
        hash.update(self.height.to_be_bytes());
        hash.update(self.orientation.to_be_bytes());
        hash.finalize().into()
    }
}
fn identity(value: &str) -> bool {
    !value.trim().is_empty()
        && value.encode_utf16().count() <= 128
        && !value.chars().any(char::is_control)
}

/// No Debug, Clone or deserializer. One control-approved shot consumes it, including on failure.
pub struct OneTimeKey([u8; 32]);
impl OneTimeKey {
    pub fn generate() -> Result<Self, Error> {
        let mut secret = [0; 32];
        getrandom::getrandom(&mut secret).map_err(|_| Error::EntropyUnavailable)?;
        Self::from_authenticated_control(secret)
    }
    pub fn from_authenticated_control(secret: [u8; 32]) -> Result<Self, Error> {
        if secret == [0; 32] {
            return Err(Error::InvalidScope);
        }
        Ok(Self(secret))
    }
    /// Send only inside the actual confirmed parent Noise control connection, never to OEM JSON/logs.
    pub fn for_authenticated_control(&self) -> [u8; 32] {
        self.0
    }
}
impl Drop for OneTimeKey {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

#[derive(Default)]
struct Ledger {
    requests: HashSet<String>,
    keys: HashSet<[u8; 32]>,
    active: Option<Weak<Shot>>,
}
struct Authorization {
    session: [u8; 32],
    local_peer: [u8; 32],
    remote_peer: [u8; 32],
    remote_address: SocketAddr,
    ended: AtomicBool,
    ledger: Mutex<Ledger>,
}
struct Shot {
    request: StillRequest,
    deadline: Instant,
    closed: AtomicBool,
    socket: Mutex<Option<TcpStream>>,
}
impl Shot {
    fn valid(&self, auth: &Authorization) -> Result<(), Error> {
        if auth.ended.load(Ordering::SeqCst) {
            return Err(Error::ParentEnded);
        }
        if self.closed.load(Ordering::SeqCst) {
            return Err(Error::Cancelled);
        }
        if Instant::now() >= self.deadline {
            return Err(Error::Expired);
        }
        Ok(())
    }
    fn close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        if let Some(socket) = lock(&self.socket).as_ref() {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }
}

/// One instance for one real confirmed parent session. Pairing alone cannot construct a StillTicket.
#[derive(Clone)]
pub struct SessionAuthorization(Arc<Authorization>);
impl SessionAuthorization {
    pub fn confirmed(
        session: [u8; 32],
        local_peer: [u8; 32],
        remote_peer: [u8; 32],
        authenticated_peer: SocketAddr,
    ) -> Result<Self, Error> {
        if session == [0; 32]
            || local_peer == [0; 32]
            || remote_peer == [0; 32]
            || local_peer == remote_peer
            || authenticated_peer.ip().is_unspecified()
        {
            return Err(Error::InvalidScope);
        }
        Ok(Self(Arc::new(Authorization {
            session,
            local_peer,
            remote_peer,
            remote_address: authenticated_peer,
            ended: AtomicBool::new(false),
            ledger: Mutex::new(Ledger::default()),
        })))
    }
    /// Call only after the CURRENT native request and both camera business/source permissions passed.
    /// There is no session-wide implicit camera grant. Cancel-before-authorize retains a tombstone.
    pub fn authorize_after_consent(
        &self,
        request: StillRequest,
        role: Role,
        key: OneTimeKey,
        lifetime: Duration,
    ) -> Result<StillTicket, Error> {
        if lifetime.is_zero() || lifetime > MAX_LIFETIME {
            return Err(Error::InvalidScope);
        }
        if self.0.ended.load(Ordering::SeqCst) {
            return Err(Error::ParentEnded);
        }
        let mut ledger = lock(&self.0.ledger);
        let fingerprint: [u8; 32] = Sha256::digest(key.0).into();
        if ledger.requests.contains(&request.request_id) || ledger.keys.contains(&fingerprint) {
            return Err(Error::AlreadyUsed);
        }
        if ledger.requests.len() >= MAX_REQUESTS {
            return Err(Error::Capacity);
        }
        if let Some(active) = ledger.active.as_ref().and_then(Weak::upgrade) {
            if active.valid(&self.0).is_ok() {
                return Err(Error::Busy);
            }
            active.close();
        }
        if self.0.ended.load(Ordering::SeqCst) {
            return Err(Error::ParentEnded);
        }
        let shot = Arc::new(Shot {
            request: request.clone(),
            deadline: Instant::now() + lifetime,
            closed: AtomicBool::new(false),
            socket: Mutex::new(None),
        });
        ledger.requests.insert(request.request_id.clone());
        ledger.keys.insert(fingerprint);
        ledger.active = Some(Arc::downgrade(&shot));
        Ok(StillTicket {
            lease: Lease {
                auth: self.0.clone(),
                shot,
            },
            role,
            key,
        })
    }
    pub fn cancel(&self, request_id: &str) -> Result<(), Error> {
        if !identity(request_id) {
            return Err(Error::InvalidScope);
        }
        let active = {
            let mut ledger = lock(&self.0.ledger);
            if !ledger.requests.contains(request_id) && ledger.requests.len() >= MAX_REQUESTS {
                return Err(Error::Capacity);
            }
            ledger.requests.insert(request_id.to_owned());
            ledger
                .active
                .as_ref()
                .and_then(Weak::upgrade)
                .filter(|s| s.request.request_id == request_id)
        };
        if let Some(shot) = active {
            shot.close();
        }
        Ok(())
    }
    pub fn end_session(&self) {
        self.0.ended.store(true, Ordering::SeqCst);
        let active = lock(&self.0.ledger).active.as_ref().and_then(Weak::upgrade);
        if let Some(shot) = active {
            shot.close();
        }
    }
}
struct Lease {
    auth: Arc<Authorization>,
    shot: Arc<Shot>,
}
impl Lease {
    fn valid(&self) -> Result<(), Error> {
        self.shot.valid(&self.auth)
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        self.shot.close();
        let mut ledger = lock(&self.auth.ledger);
        if ledger
            .active
            .as_ref()
            .is_some_and(|active| active.ptr_eq(&Arc::downgrade(&self.shot)))
        {
            ledger.active = None;
        }
    }
}
pub struct StillTicket {
    lease: Lease,
    role: Role,
    key: OneTimeKey,
}
impl StillTicket {
    fn prologue(&self) -> Vec<u8> {
        let mut bytes = b"yuejie-connect/camera-jpeg/v1\0".to_vec();
        bytes.extend_from_slice(&self.lease.auth.session);
        let (sender, receiver) = match self.role {
            Role::Sender => (self.lease.auth.local_peer, self.lease.auth.remote_peer),
            Role::Receiver => (self.lease.auth.remote_peer, self.lease.auth.local_peer),
        };
        bytes.extend_from_slice(&sender);
        bytes.extend_from_slice(&receiver);
        bytes.extend_from_slice(&self.lease.shot.request.fingerprint());
        bytes
    }
}

pub struct AuthenticatedJpeg {
    request: StillRequest,
    timestamp: u64,
    bytes: Vec<u8>,
    auth: Arc<Authorization>,
    shot: Arc<Shot>,
}
impl AuthenticatedJpeg {
    pub fn request(&self) -> &StillRequest {
        &self.request
    }
    pub fn sensor_timestamp_ns(&self) -> u64 {
        self.timestamp
    }
    pub fn is_current(&self) -> bool {
        self.shot.valid(&self.auth).is_ok()
    }
    /// Keep the channel alive until the actual native result. Caller owns/wipes the returned bytes.
    pub fn into_bytes(mut self) -> Result<Vec<u8>, Error> {
        self.shot.valid(&self.auth)?;
        Ok(std::mem::take(&mut self.bytes))
    }
}
impl Drop for AuthenticatedJpeg {
    fn drop(&mut self) {
        self.bytes.fill(0);
    }
}

pub struct StillChannel {
    lease: Lease,
    role: Role,
    socket: TcpStream,
    noise: snow::TransportState,
    used: bool,
}
impl StillChannel {
    /// Blocking on a dedicated worker only. The actual parent peer IP/scope and all identity fields bind this shot.
    pub fn establish(mut socket: TcpStream, ticket: StillTicket) -> Result<Self, Error> {
        ticket.lease.valid()?;
        if !same_peer(
            socket.peer_addr().map_err(|_| Error::TransportFailed)?,
            ticket.lease.auth.remote_address,
        ) {
            return Err(Error::WrongPeer);
        }
        socket
            .set_read_timeout(Some(POLL))
            .map_err(|_| Error::TransportFailed)?;
        socket
            .set_write_timeout(Some(POLL))
            .map_err(|_| Error::TransportFailed)?;
        socket
            .set_nodelay(true)
            .map_err(|_| Error::TransportFailed)?;
        *lock(&ticket.lease.shot.socket) =
            Some(socket.try_clone().map_err(|_| Error::TransportFailed)?);
        if let Err(error) = ticket.lease.valid() {
            ticket.lease.shot.close();
            return Err(error);
        }
        let prologue = ticket.prologue();
        let builder = snow::Builder::new(PATTERN.parse().map_err(|_| Error::AuthenticationFailed)?)
            .prologue(&prologue)
            .psk(0, &ticket.key.0);
        let mut handshake = match ticket.role {
            Role::Sender => builder.build_initiator(),
            Role::Receiver => builder.build_responder(),
        }
        .map_err(|_| Error::AuthenticationFailed)?;
        let mut buffer = vec![0; MAX_RECORD];
        match ticket.role {
            Role::Sender => {
                let length = handshake
                    .write_message(&[], &mut buffer)
                    .map_err(|_| Error::AuthenticationFailed)?;
                write_record(&mut socket, &buffer[..length], &ticket.lease)?;
                let message = read_record(&mut socket, &ticket.lease)?;
                let length = handshake
                    .read_message(&message, &mut buffer)
                    .map_err(|_| Error::AuthenticationFailed)?;
                if length != 0 {
                    return Err(Error::InvalidRecord);
                }
            }
            Role::Receiver => {
                let message = read_record(&mut socket, &ticket.lease)?;
                let length = handshake
                    .read_message(&message, &mut buffer)
                    .map_err(|_| Error::AuthenticationFailed)?;
                if length != 0 {
                    return Err(Error::InvalidRecord);
                }
                let length = handshake
                    .write_message(&[], &mut buffer)
                    .map_err(|_| Error::AuthenticationFailed)?;
                write_record(&mut socket, &buffer[..length], &ticket.lease)?;
            }
        }
        ticket.lease.valid()?;
        let noise = handshake
            .into_transport_mode()
            .map_err(|_| Error::AuthenticationFailed)?;
        let StillTicket {
            lease,
            role,
            key: _,
        } = ticket;
        Ok(Self {
            lease,
            role,
            socket,
            noise,
            used: false,
        })
    }
    pub fn request(&self) -> &StillRequest {
        &self.lease.shot.request
    }
    pub fn is_current(&self) -> bool {
        self.lease.valid().is_ok()
    }
    pub fn close(&self) {
        self.lease.shot.close();
    }

    /// Authentication/transport completion only. Never completes an OEM capture/consumption callback.
    pub fn send_jpeg(&mut self, sensor_timestamp_ns: u64, jpeg: &[u8]) -> Result<(), Error> {
        let result = self.send_inner(sensor_timestamp_ns, jpeg);
        if result.is_err() {
            self.close();
        }
        result
    }
    fn send_inner(&mut self, timestamp: u64, jpeg: &[u8]) -> Result<(), Error> {
        if self.role != Role::Sender {
            return Err(Error::InvalidRole);
        }
        if self.used {
            return Err(Error::AlreadyUsed);
        }
        self.used = true;
        self.lease.valid()?;
        validate_jpeg(jpeg)?;
        if timestamp == 0 || timestamp > i64::MAX as u64 {
            return Err(Error::InvalidScope);
        }
        let digest: [u8; 32] = Sha256::digest(jpeg).into();
        let mut header = Vec::with_capacity(45);
        header.push(1);
        header.extend_from_slice(&(jpeg.len() as u32).to_be_bytes());
        header.extend_from_slice(&timestamp.to_be_bytes());
        header.extend_from_slice(&digest);
        self.write_plain(&header)?;
        let mut offset = 0;
        for data in jpeg.chunks(CHUNK) {
            let mut chunk = Vec::with_capacity(5 + data.len());
            chunk.push(2);
            chunk.extend_from_slice(&(offset as u32).to_be_bytes());
            chunk.extend_from_slice(data);
            let result = self.write_plain(&chunk);
            chunk.fill(0);
            result?;
            offset += data.len();
        }
        let mut finish = vec![3];
        finish.extend_from_slice(&(jpeg.len() as u32).to_be_bytes());
        finish.extend_from_slice(&digest);
        self.write_plain(&finish)?;
        self.lease.valid()
    }

    /// One return only, after final boundary, exact length, SHA256 and current parent/request checks.
    pub fn receive_jpeg(&mut self) -> Result<AuthenticatedJpeg, Error> {
        let result = self.receive_inner();
        if result.is_err() {
            self.close();
        }
        result
    }
    fn receive_inner(&mut self) -> Result<AuthenticatedJpeg, Error> {
        if self.role != Role::Receiver {
            return Err(Error::InvalidRole);
        }
        if self.used {
            return Err(Error::AlreadyUsed);
        }
        self.used = true;
        let header = self.read_plain()?;
        if header.len() != 45 || header[0] != 1 {
            return Err(Error::InvalidRecord);
        }
        let length =
            u32::from_be_bytes(header[1..5].try_into().map_err(|_| Error::InvalidRecord)?) as usize;
        let timestamp =
            u64::from_be_bytes(header[5..13].try_into().map_err(|_| Error::InvalidRecord)?);
        let digest: [u8; 32] = header[13..45]
            .try_into()
            .map_err(|_| Error::InvalidRecord)?;
        if !(4..=MAX_JPEG_BYTES).contains(&length) || timestamp == 0 || timestamp > i64::MAX as u64
        {
            return Err(Error::InvalidRecord);
        }
        let mut bytes = SensitiveBytes(Vec::with_capacity(length));
        loop {
            let mut record = SensitiveBytes(self.read_plain()?);
            if record.0.first() == Some(&3) {
                if record.0.len() != 37
                    || bytes.0.len() != length
                    || u32::from_be_bytes(
                        record.0[1..5]
                            .try_into()
                            .map_err(|_| Error::InvalidRecord)?,
                    ) as usize
                        != length
                    || record.0[5..] != digest
                {
                    return Err(Error::InvalidRecord);
                }
                break;
            }
            if record.0.len() < 6
                || record.0.len() > CHUNK + 5
                || record.0[0] != 2
                || u32::from_be_bytes(
                    record.0[1..5]
                        .try_into()
                        .map_err(|_| Error::InvalidRecord)?,
                ) as usize
                    != bytes.0.len()
                || record.0.len() - 5 > length - bytes.0.len()
            {
                return Err(Error::InvalidRecord);
            }
            bytes.0.extend_from_slice(&record.0[5..]);
            record.0.fill(0);
        }
        if <[u8; 32]>::from(Sha256::digest(&bytes.0)) != digest {
            return Err(Error::InvalidJpeg);
        }
        validate_jpeg(&bytes.0)?;
        self.lease.valid()?;
        Ok(AuthenticatedJpeg {
            request: self.request().clone(),
            timestamp,
            bytes: std::mem::take(&mut bytes.0),
            auth: self.lease.auth.clone(),
            shot: self.lease.shot.clone(),
        })
    }
    fn write_plain(&mut self, plain: &[u8]) -> Result<(), Error> {
        self.lease.valid()?;
        let mut encrypted = vec![0; plain.len() + 16];
        let size = self
            .noise
            .write_message(plain, &mut encrypted)
            .map_err(|_| Error::AuthenticationFailed)?;
        write_record(&mut self.socket, &encrypted[..size], &self.lease)
    }
    fn read_plain(&mut self) -> Result<Vec<u8>, Error> {
        let encrypted = read_record(&mut self.socket, &self.lease)?;
        let mut plain = SensitiveBytes(vec![0; encrypted.len()]);
        let size = self
            .noise
            .read_message(&encrypted, &mut plain.0)
            .map_err(|_| Error::AuthenticationFailed)?;
        plain.0.truncate(size);
        self.lease.valid()?;
        Ok(std::mem::take(&mut plain.0))
    }
}
impl Drop for StillChannel {
    fn drop(&mut self) {
        self.close();
    }
}
struct SensitiveBytes(Vec<u8>);
impl Drop for SensitiveBytes {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}
fn validate_jpeg(bytes: &[u8]) -> Result<(), Error> {
    if bytes.len() < 4
        || bytes.len() > MAX_JPEG_BYTES
        || bytes[..2] != [255, 216]
        || bytes[bytes.len() - 2..] != [255, 217]
    {
        return Err(Error::InvalidJpeg);
    }
    // Marker/length/digest framing is not image decoding; Java/OEM validates dimensions/actual consumption.
    Ok(())
}
fn same_peer(actual: SocketAddr, expected: SocketAddr) -> bool {
    match (actual, expected) {
        (SocketAddr::V4(a), SocketAddr::V4(b)) => a.ip() == b.ip(),
        (SocketAddr::V6(a), SocketAddr::V6(b)) => a.ip() == b.ip() && a.scope_id() == b.scope_id(),
        _ => false,
    }
}
fn io_error(error: &std::io::Error) -> bool {
    matches!(
        error.kind(),
        std::io::ErrorKind::WouldBlock
            | std::io::ErrorKind::TimedOut
            | std::io::ErrorKind::Interrupted
    )
}
fn read_exact(socket: &mut TcpStream, buffer: &mut [u8], lease: &Lease) -> Result<(), Error> {
    let mut offset = 0;
    while offset < buffer.len() {
        lease.valid()?;
        match socket.read(&mut buffer[offset..]) {
            Ok(0) => return Err(Error::TransportFailed),
            Ok(n) => offset += n,
            Err(error) if io_error(&error) => {}
            Err(_) => return Err(Error::TransportFailed),
        }
    }
    lease.valid()
}
fn write_all(socket: &mut TcpStream, buffer: &[u8], lease: &Lease) -> Result<(), Error> {
    let mut offset = 0;
    while offset < buffer.len() {
        lease.valid()?;
        match socket.write(&buffer[offset..]) {
            Ok(0) => return Err(Error::TransportFailed),
            Ok(n) => offset += n,
            Err(error) if io_error(&error) => {}
            Err(_) => return Err(Error::TransportFailed),
        }
    }
    lease.valid()
}
fn read_record(socket: &mut TcpStream, lease: &Lease) -> Result<Vec<u8>, Error> {
    let mut prefix = [0; 2];
    read_exact(socket, &mut prefix, lease)?;
    let size = u16::from_be_bytes(prefix) as usize;
    if size < 16 {
        return Err(Error::InvalidRecord);
    }
    let mut bytes = vec![0; size];
    read_exact(socket, &mut bytes, lease)?;
    Ok(bytes)
}
fn write_record(socket: &mut TcpStream, bytes: &[u8], lease: &Lease) -> Result<(), Error> {
    if bytes.len() < 16 || bytes.len() > MAX_RECORD {
        return Err(Error::InvalidRecord);
    }
    write_all(socket, &(bytes.len() as u16).to_be_bytes(), lease)?;
    write_all(socket, bytes, lease)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::thread;

    fn request(id: &str) -> StillRequest {
        StillRequest::new(7, id.to_owned(), "physical-rear".to_owned(), 640, 480, 0).unwrap()
    }
    fn auth(local: u8, remote: u8) -> SessionAuthorization {
        SessionAuthorization::confirmed(
            [3; 32],
            [local; 32],
            [remote; 32],
            "127.0.0.1:1".parse().unwrap(),
        )
        .unwrap()
    }
    fn key(value: u8) -> OneTimeKey {
        OneTimeKey::from_authenticated_control([value; 32]).unwrap()
    }
    fn ticket(
        auth: &SessionAuthorization,
        request: StillRequest,
        role: Role,
        value: u8,
    ) -> StillTicket {
        auth.authorize_after_consent(request, role, key(value), Duration::from_secs(3))
            .unwrap()
    }
    fn channels() -> (StillChannel, StillChannel) {
        channels_with_lifetime(Duration::from_secs(3))
    }
    fn channels_with_lifetime(lifetime: Duration) -> (StillChannel, StillChannel) {
        let sender = auth(1, 2)
            .authorize_after_consent(request("exposure"), Role::Sender, key(9), lifetime)
            .unwrap();
        let receiver = auth(2, 1)
            .authorize_after_consent(request("exposure"), Role::Receiver, key(9), lifetime)
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let thread = thread::spawn(move || {
            StillChannel::establish(listener.accept().unwrap().0, sender).unwrap()
        });
        let receiver =
            StillChannel::establish(TcpStream::connect(address).unwrap(), receiver).unwrap();
        (thread.join().unwrap(), receiver)
    }
    fn jpeg(length: usize) -> Vec<u8> {
        let mut bytes = vec![42; length];
        bytes[..2].copy_from_slice(&[255, 216]);
        bytes[length - 2..].copy_from_slice(&[255, 217]);
        bytes
    }
    #[test]
    fn single_complete_fragmented_frame_is_current_not_native_success() {
        let (mut sender, mut receiver) = channels();
        let bytes = jpeg(CHUNK * 2 + 21);
        let expected = bytes.clone();
        let sent = thread::spawn(move || {
            sender.send_jpeg(1234, &bytes).unwrap();
            sender
        });
        let frame = receiver.receive_jpeg().unwrap();
        let sender = sent.join().unwrap();
        assert!(frame.is_current());
        assert_eq!(frame.request().request_id(), "exposure");
        assert_eq!(frame.sensor_timestamp_ns(), 1234);
        assert_eq!(frame.into_bytes().unwrap(), expected);
        assert!(receiver.is_current());
        assert!(sender.is_current()); // Owner still awaits real OEM acquisition.
        assert!(matches!(receiver.receive_jpeg(), Err(Error::AlreadyUsed)));
    }
    #[test]
    fn maximum_frame_is_bounded_and_fragmented() {
        let (mut sender, mut receiver) = channels_with_lifetime(MAX_LIFETIME);
        let sent = thread::spawn(move || {
            sender.send_jpeg(1, &jpeg(MAX_JPEG_BYTES)).unwrap();
            sender
        });
        let frame = receiver.receive_jpeg().unwrap();
        let sender = sent.join().unwrap();
        assert_eq!(frame.into_bytes().unwrap().len(), MAX_JPEG_BYTES);
        sender.close();
        assert!(validate_jpeg(&jpeg(MAX_JPEG_BYTES + 1)).is_err());
    }
    #[test]
    fn request_and_key_replay_tombstones_survive_close_and_failure() {
        let auth = auth(1, 2);
        drop(ticket(&auth, request("one"), Role::Sender, 1));
        assert!(matches!(
            auth.authorize_after_consent(
                request("one"),
                Role::Sender,
                key(2),
                Duration::from_secs(1)
            ),
            Err(Error::AlreadyUsed)
        ));
        assert!(matches!(
            auth.authorize_after_consent(
                request("two"),
                Role::Sender,
                key(1),
                Duration::from_secs(1)
            ),
            Err(Error::AlreadyUsed)
        ));
        auth.cancel("before-authorize").unwrap();
        assert!(matches!(
            auth.authorize_after_consent(
                request("before-authorize"),
                Role::Sender,
                key(3),
                Duration::from_secs(1)
            ),
            Err(Error::AlreadyUsed)
        ));
        let last = ticket(&auth, request("new"), Role::Sender, 4);
        assert_eq!(last.lease.shot.request.stream_id(), 7); // Same camera stream may have a fresh exposure.
    }
    #[test]
    fn only_one_shot_and_old_drop_cannot_remove_replacement() {
        let auth = auth(1, 2);
        let first = ticket(&auth, request("first"), Role::Sender, 1);
        assert!(matches!(
            auth.authorize_after_consent(
                request("second"),
                Role::Sender,
                key(2),
                Duration::from_secs(1)
            ),
            Err(Error::Busy)
        ));
        auth.cancel("first").unwrap();
        let replacement = ticket(&auth, request("second"), Role::Sender, 2);
        drop(first);
        assert!(matches!(
            auth.authorize_after_consent(
                request("third"),
                Role::Sender,
                key(3),
                Duration::from_secs(1)
            ),
            Err(Error::Busy)
        ));
        assert!(replacement.lease.valid().is_ok());
        drop(replacement);
    }
    #[test]
    fn capacity_does_not_evict_old_exposures() {
        let auth = auth(1, 2);
        for i in 0..MAX_REQUESTS {
            let key = OneTimeKey::generate().unwrap();
            drop(
                auth.authorize_after_consent(
                    request(&format!("r{i}")),
                    Role::Sender,
                    key,
                    Duration::from_secs(1),
                )
                .unwrap(),
            );
        }
        assert!(matches!(
            auth.authorize_after_consent(
                request("overflow"),
                Role::Sender,
                key(2),
                Duration::from_secs(1)
            ),
            Err(Error::Capacity)
        ));
        assert!(matches!(
            auth.authorize_after_consent(
                request("r0"),
                Role::Sender,
                key(3),
                Duration::from_secs(1)
            ),
            Err(Error::AlreadyUsed)
        ));
    }
    #[test]
    fn invalid_scopes_and_lifetimes_never_authorize() {
        assert!(StillRequest::new(0, "r".into(), "c".into(), 640, 480, 0).is_err());
        assert!(StillRequest::new(1, "r".into(), "c".into(), 8192, 8192, 0).is_err());
        assert!(StillRequest::new(1, "r".into(), "c".into(), 640, 480, 45).is_err());
        assert!(StillRequest::new(1, "r\0".into(), "c".into(), 640, 480, 0).is_err());
        assert!(SessionAuthorization::confirmed(
            [0; 32],
            [1; 32],
            [2; 32],
            "127.0.0.1:1".parse().unwrap()
        )
        .is_err());
        assert!(SessionAuthorization::confirmed(
            [3; 32],
            [1; 32],
            [1; 32],
            "127.0.0.1:1".parse().unwrap()
        )
        .is_err());
        assert!(OneTimeKey::from_authenticated_control([0; 32]).is_err());
        assert!(auth(1, 2)
            .authorize_after_consent(request("r"), Role::Sender, key(1), Duration::ZERO)
            .is_err());
        assert!(auth(1, 2)
            .authorize_after_consent(
                request("r"),
                Role::Sender,
                key(1),
                MAX_LIFETIME + Duration::from_nanos(1)
            )
            .is_err());
        assert!(!same_peer(
            "127.0.0.1:2".parse().unwrap(),
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), 2)
        ));
    }
    #[test]
    fn cancelled_parent_and_expired_ticket_do_not_revive() {
        let auth = auth(1, 2);
        let pending = ticket(&auth, request("first"), Role::Sender, 1);
        auth.end_session();
        assert_eq!(pending.lease.valid(), Err(Error::ParentEnded));
        assert!(matches!(
            auth.authorize_after_consent(
                request("new"),
                Role::Sender,
                key(2),
                Duration::from_secs(1)
            ),
            Err(Error::ParentEnded)
        ));
        let auth = super::tests::auth(1, 2);
        let expired = auth
            .authorize_after_consent(
                request("expired"),
                Role::Sender,
                key(3),
                Duration::from_millis(1),
            )
            .unwrap();
        thread::sleep(Duration::from_millis(4));
        assert_eq!(expired.lease.valid(), Err(Error::Expired));
        let new = ticket(&auth, request("fresh"), Role::Sender, 4);
        drop(expired);
        assert!(new.lease.valid().is_ok());
    }
    #[test]
    fn handshake_binds_key_session_peers_and_each_request_field() {
        for mutation in 0..8 {
            let sender_auth = auth(1, 2);
            let receiver_auth = if mutation == 1 {
                SessionAuthorization::confirmed(
                    [4; 32],
                    [2; 32],
                    [1; 32],
                    "127.0.0.1:1".parse().unwrap(),
                )
                .unwrap()
            } else if mutation == 2 {
                auth(2, 5)
            } else {
                auth(2, 1)
            };
            let sender = ticket(&sender_auth, request("one"), Role::Sender, 9);
            let mut changed = request("one");
            match mutation {
                3 => changed.request_id = "two".into(),
                4 => changed.camera_id = "front".into(),
                5 => changed.stream_id = 8,
                6 => changed.width = 800,
                7 => changed.orientation = 90,
                _ => {}
            }
            let receiver = ticket(
                &receiver_auth,
                changed,
                Role::Receiver,
                if mutation == 0 { 10 } else { 9 },
            );
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let incoming = thread::spawn(move || {
                StillChannel::establish(listener.accept().unwrap().0, sender).is_err()
            });
            assert!(
                StillChannel::establish(TcpStream::connect(address).unwrap(), receiver).is_err()
            );
            assert!(incoming.join().unwrap());
        }
    }
    fn header(length: usize, digest: [u8; 32]) -> Vec<u8> {
        let mut out = vec![1];
        out.extend_from_slice(&(length as u32).to_be_bytes());
        out.extend_from_slice(&1u64.to_be_bytes());
        out.extend_from_slice(&digest);
        out
    }
    fn chunk(offset: usize, bytes: &[u8]) -> Vec<u8> {
        let mut out = vec![2];
        out.extend_from_slice(&(offset as u32).to_be_bytes());
        out.extend_from_slice(bytes);
        out
    }
    fn finish(length: usize, digest: [u8; 32]) -> Vec<u8> {
        let mut out = vec![3];
        out.extend_from_slice(&(length as u32).to_be_bytes());
        out.extend_from_slice(&digest);
        out
    }
    #[test]
    fn malformed_authenticated_frames_never_deliver_partial_or_wrong_jpeg() {
        for mutation in 0..7 {
            let (mut sender, mut receiver) = channels();
            let bytes = jpeg(19);
            let digest: [u8; 32] = Sha256::digest(&bytes).into();
            let sent = thread::spawn(move || {
                let length = if mutation == 0 {
                    MAX_JPEG_BYTES + 1
                } else {
                    bytes.len()
                };
                let _ = sender.write_plain(&header(length, digest));
                if mutation == 0 {
                    return sender;
                }
                let payload = if mutation == 1 {
                    &bytes[..bytes.len() - 1]
                } else {
                    &bytes[..]
                };
                let _ = sender.write_plain(&chunk(if mutation == 2 { 1 } else { 0 }, payload));
                if mutation == 3 {
                    return sender;
                }
                let _ = sender.write_plain(&finish(
                    if mutation == 4 {
                        bytes.len() + 1
                    } else {
                        bytes.len()
                    },
                    if mutation == 5 { [1; 32] } else { digest },
                ));
                if mutation == 6 {
                    let _ = sender.write_plain(&chunk(bytes.len(), &[1]));
                }
                sender
            });
            if mutation == 3 {
                // EOF at a would-be frame boundary is not completion.
                let sender = sent.join().unwrap();
                drop(sender);
                assert!(receiver.receive_jpeg().is_err());
            } else if mutation == 6 {
                // The first frame is complete; another authenticated record cannot produce a second delivery.
                let frame = receiver.receive_jpeg().unwrap();
                let sender = sent.join().unwrap();
                assert!(frame.is_current());
                assert!(matches!(receiver.receive_jpeg(), Err(Error::AlreadyUsed)));
                drop(sender);
            } else {
                assert!(receiver.receive_jpeg().is_err());
                drop(sent.join().unwrap());
            }
        }
    }
    #[test]
    fn bad_jpeg_digest_and_cancel_after_verified_frame_fail_closed() {
        let (mut sender, mut receiver) = channels();
        let bytes = jpeg(10);
        let digest = [7; 32];
        let sent = thread::spawn(move || {
            let _ = sender.write_plain(&header(bytes.len(), digest));
            let _ = sender.write_plain(&chunk(0, &bytes));
            let _ = sender.write_plain(&finish(bytes.len(), digest));
            sender
        });
        assert!(matches!(receiver.receive_jpeg(), Err(Error::InvalidJpeg)));
        drop(sent.join().unwrap());
        let (mut sender, mut receiver) = channels();
        let sent = thread::spawn(move || {
            sender.send_jpeg(1, &jpeg(9)).unwrap();
            sender
        });
        let frame = receiver.receive_jpeg().unwrap();
        let sender = sent.join().unwrap();
        receiver.close();
        assert!(!frame.is_current());
        assert!(frame.into_bytes().is_err());
        drop(sender);
    }
    #[test]
    fn cancellation_unblocks_partial_noise_record_without_delivery() {
        let source_auth = auth(1, 2);
        let sink_auth = auth(2, 1);
        let source = ticket(&source_auth, request("partial"), Role::Sender, 8);
        let sink = ticket(&sink_auth, request("partial"), Role::Receiver, 8);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let incoming = thread::spawn(move || {
            StillChannel::establish(listener.accept().unwrap().0, source).unwrap()
        });
        let mut receiver =
            StillChannel::establish(TcpStream::connect(address).unwrap(), sink).unwrap();
        let mut sender = incoming.join().unwrap();
        sender.socket.write_all(&100u16.to_be_bytes()).unwrap();
        sender.socket.write_all(&[0; 3]).unwrap();
        let received = thread::spawn(move || receiver.receive_jpeg().is_err());
        let start = Instant::now();
        sink_auth.cancel("partial").unwrap();
        assert!(received.join().unwrap());
        assert!(start.elapsed() < Duration::from_secs(1));
    }
}
