//! QiangJian's own authenticated bridge, not an emulation of Lyra/SCP wire protocol.
//! Noise XX + human comparison of a 48-bit transcript code on BOTH devices.
use crate::battery::BatteryStatus;
use qj_core::{
    gateway::{ConfirmedLink, Gateway, GatewayError, Operation, Projection, RealDevice},
    Source,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::{Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    path::Path,
    sync::{
        mpsc::{self, Receiver, SyncSender},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

const PATTERN: &str = "Noise_XX_25519_ChaChaPoly_SHA256";
const PROLOGUE: &[u8] = b"qiangjian-connect/bridge/v2";
const MAX_FRAME: usize = 16_384;
const SESSION_MS: u64 = 900_000;
type Result<T> = std::result::Result<T, &'static str>;
thread_local! { static IO_ERROR: std::cell::Cell<Option<i32>> = const { std::cell::Cell::new(None) }; }

pub struct Identity {
    private: [u8; 32],
    pub public: [u8; 32],
}
impl Identity {
    pub fn generate() -> Result<Self> {
        let mut private = [0; 32];
        getrandom::getrandom(&mut private).map_err(|_| "entropy_unavailable")?;
        Ok(Self::from_private(private))
    }
    fn from_private(private: [u8; 32]) -> Self {
        Self {
            public: x25519_dalek::x25519(private, x25519_dalek::X25519_BASEPOINT_BYTES),
            private,
        }
    }
    pub fn load_or_create(path: &Path) -> Result<Self> {
        if path.exists() {
            let bytes = fs::read(path).map_err(|_| "identity_read_failed")?;
            return Ok(Self::from_private(
                bytes.try_into().map_err(|_| "invalid_identity")?,
            ));
        }
        let key = Self::generate()?;
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(path).map_err(|_| "identity_create_failed")?;
        file.write_all(&key.private)
            .map_err(|_| "identity_write_failed")?;
        file.sync_all().map_err(|_| "identity_write_failed")?;
        Ok(key)
    }
}

#[derive(Clone, Serialize)]
pub struct Snapshot {
    pub phase: &'static str,
    pub session: String,
    pub comparison_code: String,
    pub local_confirmed: bool,
    pub remote_confirmed: bool,
    pub local: RealDevice,
    pub remote: Option<RealDevice>,
    pub projection: Option<Projection>,
    pub listening_port: u16,
    pub requests_sent: u64,
    pub requests_received: u64,
    pub responses_received: u64,
    pub last_error: Option<&'static str>,
    pub socket_errno: Option<i32>,
}
struct State {
    epoch: u64,
    view: Snapshot,
    jobs: Option<SyncSender<Job>>,
    gateway: Gateway,
    next_request: u64,
}
pub struct Bridge {
    identity: Identity,
    profile: RealDevice,
    state: Mutex<State>,
    clock: Instant,
    battery_reader: Option<BatteryReader>,
}
enum Job {
    Confirm(String),
    Request {
        id: u64,
        operation: Operation,
        reply: SyncSender<Result<OperationResult>>,
    },
}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum Wire {
    Confirm,
    Profile {
        device: RealDevice,
    },
    Request {
        id: u64,
        target: [u8; 32],
        operation: Operation,
    },
    Response {
        id: u64,
        result: Option<OperationResult>,
        error: Option<String>,
    },
    Ping,
    Pong,
}

pub type BatteryReader = Arc<dyn Fn() -> Result<BatteryStatus> + Send + Sync>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(
    tag = "operation",
    content = "data",
    rename_all = "snake_case",
    deny_unknown_fields
)]
pub enum OperationResult {
    DeviceProfile(RealDevice),
    BatteryStatus(BatteryStatus),
}

impl OperationResult {
    fn matches(&self, operation: Operation, peer: &[u8; 32]) -> bool {
        match (operation, self) {
            (Operation::DeviceProfile, Self::DeviceProfile(p)) => {
                &p.peer_key == peer && qj_core::gateway::validate(p).is_ok()
            }
            (Operation::BatteryStatus, Self::BatteryStatus(b)) => b.validate().is_ok(),
            _ => false,
        }
    }
}

impl Bridge {
    pub fn new(identity: Identity, profile: RealDevice) -> Result<Arc<Self>> {
        Self::with_battery_reader(identity, profile, None)
    }
    pub fn with_battery_reader(
        identity: Identity,
        mut profile: RealDevice,
        battery_reader: Option<BatteryReader>,
    ) -> Result<Arc<Self>> {
        profile.peer_key = identity.public;
        // Advertise only actual local executors; incoming metadata cannot enable code.
        profile.operations = BTreeSet::from([Operation::DeviceProfile]);
        if battery_reader.is_some() {
            profile.operations.insert(Operation::BatteryStatus);
        }
        qj_core::gateway::validate(&profile).map_err(|_| "invalid_local_profile")?;
        let view = Snapshot {
            phase: "stopped",
            session: String::new(),
            comparison_code: String::new(),
            local_confirmed: false,
            remote_confirmed: false,
            local: profile.clone(),
            remote: None,
            projection: None,
            listening_port: 0,
            requests_sent: 0,
            requests_received: 0,
            responses_received: 0,
            last_error: None,
            socket_errno: None,
        };
        Ok(Arc::new(Self {
            identity,
            profile,
            state: Mutex::new(State {
                epoch: 0,
                view,
                jobs: None,
                gateway: Gateway::default(),
                next_request: 0,
            }),
            clock: Instant::now(),
            battery_reader,
        }))
    }
    fn now(&self) -> u64 {
        self.clock.elapsed().as_millis() as u64
    }
    fn reset(&self, phase: &'static str) -> u64 {
        let mut state = self.state.lock().unwrap();
        state.epoch = state.epoch.wrapping_add(1);
        state.jobs = None;
        state.gateway = Gateway::default();
        state.next_request = 0;
        state.view.phase = phase;
        state.view.session.clear();
        state.view.comparison_code.clear();
        state.view.local_confirmed = false;
        state.view.remote_confirmed = false;
        state.view.remote = None;
        state.view.projection = None;
        state.view.listening_port = 0;
        state.view.requests_sent = 0;
        state.view.requests_received = 0;
        state.view.responses_received = 0;
        state.view.last_error = None;
        state.view.socket_errno = None;
        state.epoch
    }
    pub fn stop(&self) {
        self.reset("stopped");
    }
    pub fn snapshot(&self) -> Snapshot {
        self.state.lock().unwrap().view.clone()
    }
    fn current(&self, epoch: u64) -> bool {
        self.state.lock().unwrap().epoch == epoch
    }
    fn update(&self, epoch: u64, f: impl FnOnce(&mut State)) {
        let mut s = self.state.lock().unwrap();
        if s.epoch == epoch {
            f(&mut s);
        }
    }
    pub fn listen(self: &Arc<Self>, address: SocketAddr) -> Result<u16> {
        // Bind first so a failed start does not discard an existing working session.
        let listener = TcpListener::bind(address).map_err(|_| "listen_failed")?;
        listener
            .set_nonblocking(true)
            .map_err(|_| "listen_failed")?;
        let port = listener.local_addr().map_err(|_| "listen_failed")?.port();
        let epoch = self.reset("listening");
        self.update(epoch, |s| s.view.listening_port = port);
        let bridge = self.clone();
        thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(120);
            while bridge.current(epoch) && Instant::now() < deadline {
                match listener.accept() {
                    Ok((stream, _)) => {
                        drop(listener);
                        bridge.run(epoch, stream, false);
                        return;
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(50))
                    }
                    Err(_) => {
                        bridge.fail(epoch, "accept_failed");
                        return;
                    }
                }
            }
            if bridge.current(epoch) {
                bridge.fail(epoch, "pairing_window_expired");
            }
        });
        Ok(port)
    }
    pub fn connect(self: &Arc<Self>, address: SocketAddr) -> Result<()> {
        let epoch = self.reset("connecting");
        let bridge = self.clone();
        thread::spawn(
            move || match TcpStream::connect_timeout(&address, Duration::from_secs(5)) {
                Ok(stream) => bridge.run(epoch, stream, true),
                Err(_) => bridge.fail(epoch, "connect_failed"),
            },
        );
        Ok(())
    }
    pub fn confirm(&self, session: &str) -> Result<()> {
        let s = self.state.lock().unwrap();
        if s.view.phase != "awaiting_confirmation" || session != s.view.session {
            return Err("stale_confirmation");
        }
        s.jobs
            .as_ref()
            .ok_or("not_connected")?
            .try_send(Job::Confirm(session.to_owned()))
            .map_err(|_| "busy")
    }
    pub fn projection(&self, source: Source) -> Option<Projection> {
        let s = self.state.lock().unwrap();
        if s.view.phase != "active" || source != self.profile.platform {
            return None;
        }
        s.view.projection.clone()
    }
    pub fn request(&self, source: Source, id: &str, operation: Operation) -> Result<RealDevice> {
        let session = self.snapshot().session;
        match self.request_operation(source, id, &session, operation)? {
            OperationResult::DeviceProfile(profile) => Ok(profile),
            _ => Err("unexpected_result"),
        }
    }
    pub fn request_operation(
        &self,
        source: Source,
        id: &str,
        session: &str,
        operation: Operation,
    ) -> Result<OperationResult> {
        let (tx, rx) = mpsc::sync_channel(1);
        {
            let mut s = self.state.lock().unwrap();
            if session.is_empty() || s.view.session != session {
                return Err("stale_session");
            }
            if s.view.phase != "active" {
                return Err("peer_offline");
            }
            s.next_request = s.next_request.checked_add(1).ok_or("request_limit")?;
            let request_id = s.next_request;
            s.gateway
                .route(source, id, operation, request_id, self.now())
                .map_err(|error| match error {
                    GatewayError::PeerOffline => "peer_offline",
                    GatewayError::LinkExpired => "session_expired",
                    GatewayError::UnsupportedOperation => "unsupported_operation",
                    GatewayError::WrongLocalAdapter => "forbidden",
                    _ => "invalid_request",
                })?;
            s.jobs
                .as_ref()
                .ok_or("peer_offline")?
                .try_send(Job::Request {
                    id: request_id,
                    operation,
                    reply: tx,
                })
                .map_err(|_| "busy")?;
        }
        let result = rx
            .recv_timeout(Duration::from_secs(6))
            .map_err(|e| match e {
                mpsc::RecvTimeoutError::Timeout => "request_timeout",
                mpsc::RecvTimeoutError::Disconnected => "peer_offline",
            })?;
        let state = self.state.lock().unwrap();
        if state.view.phase != "active" || state.view.session != session {
            return Err("stale_session");
        }
        result
    }
    fn fail(&self, epoch: u64, reason: &'static str) {
        self.update(epoch, |s| {
            s.view.phase = "disconnected";
            s.view.last_error = Some(reason);
            s.view.socket_errno = IO_ERROR.get();
            s.view.remote = None;
            s.view.projection = None;
            s.view.comparison_code.clear();
            s.jobs = None;
            s.gateway = Gateway::default();
        });
    }
    fn run(self: &Arc<Self>, epoch: u64, mut socket: TcpStream, initiator: bool) {
        self.update(epoch, |s| s.view.phase = "handshaking");
        let result = self.session(epoch, &mut socket, initiator);
        self.fail(epoch, result.err().unwrap_or("peer_closed"));
    }
    fn session(&self, epoch: u64, socket: &mut TcpStream, initiator: bool) -> Result<()> {
        socket.set_nodelay(true).map_err(|_| "socket_failed")?;
        socket
            .set_read_timeout(Some(Duration::from_millis(100)))
            .map_err(|_| "socket_failed")?;
        socket
            .set_write_timeout(Some(Duration::from_secs(2)))
            .map_err(|_| "socket_failed")?;
        let params = PATTERN.parse().map_err(|_| "noise_config")?;
        let builder = snow::Builder::new(params)
            .local_private_key(&self.identity.private)
            .prologue(PROLOGUE);
        let mut hs = if initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .map_err(|_| "noise_config")?;
        let mut reader = FrameReader::default();
        let mut buf = vec![0; MAX_FRAME];
        let handshake_deadline = Instant::now() + Duration::from_secs(8);
        while !hs.is_handshake_finished() {
            if !self.current(epoch) {
                return Err("stopped");
            }
            if hs.is_my_turn() {
                let n = hs
                    .write_message(&[], &mut buf)
                    .map_err(|_| "handshake_failed")?;
                write_frame(socket, &buf[..n])?;
            } else {
                let frame = loop {
                    if Instant::now() >= handshake_deadline || !self.current(epoch) {
                        return Err("handshake_timeout");
                    }
                    if let Some(frame) = reader.poll(socket)? {
                        break frame;
                    }
                };
                let n = hs
                    .read_message(&frame, &mut buf)
                    .map_err(|_| "handshake_failed")?;
                if n != 0 {
                    return Err("unexpected_handshake_payload");
                }
            }
        }
        let peer: [u8; 32] = hs
            .get_remote_static()
            .ok_or("missing_identity")?
            .try_into()
            .map_err(|_| "invalid_identity")?;
        if peer == self.identity.public {
            return Err("self_connection");
        }
        let transcript: [u8; 32] = hs
            .get_handshake_hash()
            .try_into()
            .map_err(|_| "invalid_handshake")?;
        let session = hex(&transcript);
        let code_hash = Sha256::digest([b"qj-compare-v1".as_slice(), &transcript].concat());
        let code = format!("{}-{}", hex(&code_hash[..3]), hex(&code_hash[3..6]));
        let mut noise = hs.into_transport_mode().map_err(|_| "handshake_failed")?;
        let (tx, rx) = mpsc::sync_channel(32);
        self.update(epoch, |s| {
            s.view.phase = "awaiting_confirmation";
            s.view.session = session.clone();
            s.view.comparison_code = code;
            s.jobs = Some(tx);
        });
        self.exchange(
            epoch,
            socket,
            &mut reader,
            &mut noise,
            rx,
            peer,
            transcript,
            &session,
        )
    }
    #[allow(clippy::too_many_arguments)]
    fn exchange(
        &self,
        epoch: u64,
        socket: &mut TcpStream,
        reader: &mut FrameReader,
        noise: &mut snow::TransportState,
        jobs: Receiver<Job>,
        peer: [u8; 32],
        session_key: [u8; 32],
        session: &str,
    ) -> Result<()> {
        let mut local_ok = false;
        let mut remote_ok = false;
        let mut profile_sent = false;
        let mut active = false;
        let began = Instant::now();
        let mut last_rx = Instant::now();
        let mut last_ping = Instant::now();
        let mut last_request = 0;
        let mut pending: BTreeMap<u64, (Instant, Operation, SyncSender<Result<OperationResult>>)> =
            BTreeMap::new();
        while self.current(epoch) {
            if began.elapsed() > Duration::from_millis(SESSION_MS) {
                return Err("session_expired");
            }
            if !active && began.elapsed() > Duration::from_secs(120) {
                return Err("confirmation_timeout");
            }
            if active && last_rx.elapsed() > Duration::from_secs(15) {
                return Err("peer_timeout");
            }
            while let Ok(job) = jobs.try_recv() {
                match job {
                    Job::Confirm(id) if id == session && !local_ok => {
                        send(noise, socket, &Wire::Confirm)?;
                        local_ok = true;
                        self.update(epoch, |s| s.view.local_confirmed = true);
                    }
                    Job::Request {
                        id,
                        operation,
                        reply,
                    } => {
                        if !active || pending.len() >= 32 {
                            let _ = reply.send(Err("not_ready_or_busy"));
                            continue;
                        }
                        send(
                            noise,
                            socket,
                            &Wire::Request {
                                id,
                                target: peer,
                                operation,
                            },
                        )?;
                        pending.insert(
                            id,
                            (Instant::now() + Duration::from_secs(5), operation, reply),
                        );
                        self.update(epoch, |s| s.view.requests_sent += 1);
                    }
                    _ => {}
                }
            }
            if local_ok && remote_ok && !profile_sent {
                send(
                    noise,
                    socket,
                    &Wire::Profile {
                        device: self.profile.clone(),
                    },
                )?;
                profile_sent = true;
            }
            if let Some(frame) = reader.poll(socket)? {
                let mut plaintext = vec![0; MAX_FRAME];
                let n = noise
                    .read_message(&frame, &mut plaintext)
                    .map_err(|_| "authentication_failed")?;
                let message: Wire =
                    serde_json::from_slice(&plaintext[..n]).map_err(|_| "invalid_message")?;
                last_rx = Instant::now();
                match message {
                    Wire::Confirm if !remote_ok => {
                        remote_ok = true;
                        self.update(epoch, |s| s.view.remote_confirmed = true);
                    }
                    Wire::Profile { device } if local_ok && remote_ok && !active => {
                        if device.peer_key != peer {
                            return Err("profile_identity_mismatch");
                        }
                        let mut result = Ok(());
                        self.update(epoch, |s| {
                            let projection = s.gateway.publish(
                                self.profile.platform,
                                device.clone(),
                                ConfirmedLink {
                                    peer_key: peer,
                                    session_id: session_key,
                                    expires_ms: self.now() + SESSION_MS
                                        - began.elapsed().as_millis() as u64,
                                    granted: BTreeSet::from([
                                        Operation::DeviceProfile,
                                        Operation::BatteryStatus,
                                    ]),
                                },
                                &BTreeSet::from([
                                    Operation::DeviceProfile,
                                    Operation::BatteryStatus,
                                ]),
                                self.now(),
                            );
                            match projection {
                                Ok(p) => {
                                    s.view.remote = Some(device);
                                    s.view.projection = Some(p);
                                    s.view.phase = "active";
                                }
                                Err(_) => result = Err("invalid_peer_profile"),
                            }
                        });
                        result?;
                        active = true;
                    }
                    Wire::Request {
                        id,
                        target,
                        operation,
                    } if active => {
                        if id == 0 || id <= last_request || target != self.identity.public {
                            return Err("invalid_request_identity");
                        }
                        last_request = id;
                        self.update(epoch, |s| s.view.requests_received += 1);
                        let outcome = match operation {
                            Operation::DeviceProfile => {
                                Ok(OperationResult::DeviceProfile(self.profile.clone()))
                            }
                            Operation::BatteryStatus => self
                                .battery_reader
                                .as_ref()
                                .ok_or("unsupported_operation")
                                .and_then(|read| read())
                                .and_then(|b| {
                                    b.validate()?;
                                    Ok(OperationResult::BatteryStatus(b))
                                }),
                            _ => Err("unsupported_operation"),
                        };
                        let (result, error) = match outcome {
                            Ok(result) => (Some(result), None),
                            Err(error) => (None, Some(error.to_owned())),
                        };
                        send(noise, socket, &Wire::Response { id, result, error })?;
                    }
                    Wire::Response { id, result, error } if active => {
                        if let Some((deadline, operation, reply)) = pending.remove(&id) {
                            let result = match (result, error) {
                                (Some(value), None) if value.matches(operation, &peer) => Ok(value),
                                (None, Some(error)) => Err(match error.as_str() {
                                    "unsupported_operation" => "unsupported_operation",
                                    "permission_denied" => "permission_denied",
                                    "battery_unavailable" => "battery_unavailable",
                                    _ => "remote_error",
                                }),
                                _ => return Err("invalid_response"),
                            };
                            let _ = reply.send(if Instant::now() >= deadline {
                                Err("request_timeout")
                            } else {
                                result
                            });
                            self.update(epoch, |s| s.view.responses_received += 1);
                        }
                    }
                    Wire::Ping => send(noise, socket, &Wire::Pong)?,
                    Wire::Pong => {}
                    _ => return Err("unexpected_message_state"),
                }
            }
            let expired = pending
                .iter()
                .filter(|(_, v)| Instant::now() >= v.0)
                .map(|(id, _)| *id)
                .collect::<Vec<_>>();
            for id in expired {
                if let Some((_, _, tx)) = pending.remove(&id) {
                    let _ = tx.send(Err("request_timeout"));
                }
            }
            if last_ping.elapsed() > Duration::from_secs(2) {
                send(noise, socket, &Wire::Ping)?;
                last_ping = Instant::now();
            }
        }
        Err("stopped")
    }
}

fn send(noise: &mut snow::TransportState, socket: &mut TcpStream, message: &Wire) -> Result<()> {
    let plain = serde_json::to_vec(message).map_err(|_| "encoding_failed")?;
    if plain.len() > MAX_FRAME - 16 {
        return Err("payload_too_large");
    }
    let mut frame = vec![0; MAX_FRAME];
    let n = noise
        .write_message(&plain, &mut frame)
        .map_err(|_| "encryption_failed")?;
    write_frame(socket, &frame[..n])
}
fn write_frame(socket: &mut impl Write, frame: &[u8]) -> Result<()> {
    if frame.is_empty() || frame.len() > MAX_FRAME {
        return Err("invalid_frame_size");
    }
    socket
        .write_all(&(frame.len() as u32).to_be_bytes())
        .and_then(|_| socket.write_all(frame))
        .map_err(|e| {
            IO_ERROR.set(e.raw_os_error());
            "write_failed"
        })
}
#[derive(Default)]
struct FrameReader {
    bytes: Vec<u8>,
}
impl FrameReader {
    /// Partial headers/bodies survive socket timeouts. Never allocates from unchecked lengths.
    fn poll(&mut self, input: &mut impl Read) -> Result<Option<Vec<u8>>> {
        let need = if self.bytes.len() < 4 {
            4 - self.bytes.len()
        } else {
            let n = u32::from_be_bytes(self.bytes[..4].try_into().unwrap()) as usize;
            if n == 0 || n > MAX_FRAME {
                return Err("invalid_frame_size");
            }
            4 + n - self.bytes.len()
        };
        let mut buf = [0; 4096];
        let cap = need.min(buf.len());
        match input.read(&mut buf[..cap]) {
            Ok(0) => return Err("peer_closed"),
            Ok(n) => self.bytes.extend_from_slice(&buf[..n]),
            Err(e)
                if matches!(
                    e.kind(),
                    std::io::ErrorKind::WouldBlock
                        | std::io::ErrorKind::TimedOut
                        | std::io::ErrorKind::Interrupted
                ) =>
            {
                return Ok(None)
            }
            Err(e) => {
                IO_ERROR.set(e.raw_os_error());
                return Err("read_failed");
            }
        }
        if self.bytes.len() >= 4 {
            let size = u32::from_be_bytes(self.bytes[..4].try_into().unwrap()) as usize;
            if size == 0 || size > MAX_FRAME {
                return Err("invalid_frame_size");
            }
            if self.bytes.len() == size + 4 {
                let body = self.bytes.split_off(4);
                self.bytes.clear();
                return Ok(Some(body));
            }
        }
        Ok(None)
    }
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use qj_core::gateway::DeviceKind;
    fn profile(platform: Source) -> RealDevice {
        RealDevice {
            peer_key: [0; 32],
            platform,
            kind: if platform == Source::HyperOs4 {
                DeviceKind::Tablet
            } else {
                DeviceKind::Phone
            },
            name: "Synthetic 测试设备".into(),
            manufacturer: "Test".into(),
            model: "Test model".into(),
            os_build: "Test build".into(),
            operations: BTreeSet::new(),
        }
    }
    fn wait(bridge: &Bridge, phase: &str) {
        let until = Instant::now() + Duration::from_secs(5);
        while bridge.snapshot().phase != phase {
            assert!(
                Instant::now() < until,
                "phase={} wanted={phase} error={:?}",
                bridge.snapshot().phase,
                bridge.snapshot().last_error
            );
            thread::sleep(Duration::from_millis(20));
        }
    }
    #[test]
    fn two_peers_require_both_confirmations_then_exchange_real_profiles_and_rpc() {
        let a = Bridge::new(Identity::generate().unwrap(), profile(Source::HyperOs4)).unwrap();
        let b = Bridge::new(Identity::generate().unwrap(), profile(Source::ColorOs17)).unwrap();
        let port = a.listen("127.0.0.1:0".parse().unwrap()).unwrap();
        b.connect(format!("127.0.0.1:{port}").parse().unwrap())
            .unwrap();
        wait(&a, "awaiting_confirmation");
        wait(&b, "awaiting_confirmation");
        assert_eq!(a.snapshot().comparison_code, b.snapshot().comparison_code);
        assert!(a.snapshot().remote.is_none());
        assert!(b.projection(Source::ColorOs17).is_none());
        assert_eq!(a.confirm("stale"), Err("stale_confirmation"));
        a.confirm(&a.snapshot().session).unwrap();
        thread::sleep(Duration::from_millis(150));
        assert!(a.snapshot().remote.is_none());
        assert!(b.snapshot().remote.is_none());
        b.confirm(&b.snapshot().session).unwrap();
        wait(&a, "active");
        wait(&b, "active");
        let projection = b.projection(Source::ColorOs17).unwrap();
        let result = b
            .request(Source::ColorOs17, &projection.id, Operation::DeviceProfile)
            .unwrap();
        assert_eq!(result, a.profile);
        assert_eq!(a.snapshot().requests_received, 1);
        assert_eq!(
            b.request(Source::ColorOs17, &projection.id, Operation::FileTransfer),
            Err("unsupported_operation")
        );
        a.stop();
        wait(&b, "disconnected");
        assert!(b.projection(Source::ColorOs17).is_none());
        b.stop();
    }
    #[test]
    fn frame_limits_and_fragmentation() {
        let mut reader = FrameReader::default();
        let mut source = std::io::Cursor::new(vec![0, 0, 0, 3, 7, 8, 9]);
        assert_eq!(reader.poll(&mut source), Ok(None));
        assert_eq!(reader.poll(&mut source), Ok(Some(vec![7, 8, 9])));
        let mut bad = std::io::Cursor::new(u32::MAX.to_be_bytes());
        assert_eq!(reader.poll(&mut bad), Err("invalid_frame_size"));
    }
    #[test]
    fn battery_uses_remote_executor_and_reconnect_invalidates_old_session() {
        use std::sync::atomic::{AtomicU8, Ordering};
        let level = Arc::new(AtomicU8::new(43));
        let sensor = level.clone();
        let a = Bridge::new(Identity::generate().unwrap(), profile(Source::HyperOs4)).unwrap();
        let b = Bridge::with_battery_reader(
            Identity::generate().unwrap(),
            profile(Source::ColorOs17),
            Some(Arc::new(move || {
                Ok(BatteryStatus {
                    level: sensor.load(Ordering::SeqCst),
                    plugged: 2,
                })
            })),
        )
        .unwrap();
        let pair = || {
            let port = a.listen("127.0.0.1:0".parse().unwrap()).unwrap();
            b.connect(format!("127.0.0.1:{port}").parse().unwrap())
                .unwrap();
            wait(&a, "awaiting_confirmation");
            wait(&b, "awaiting_confirmation");
            a.confirm(&a.snapshot().session).unwrap();
            b.confirm(&b.snapshot().session).unwrap();
            wait(&a, "active");
            wait(&b, "active");
        };
        pair();
        let p = a.projection(Source::HyperOs4).unwrap();
        assert!(p.operations.contains(&Operation::BatteryStatus));
        assert!(!b
            .projection(Source::ColorOs17)
            .unwrap()
            .operations
            .contains(&Operation::BatteryStatus));
        let read = || {
            a.request_operation(
                Source::HyperOs4,
                &p.id,
                &p.session_id,
                Operation::BatteryStatus,
            )
        };
        assert_eq!(
            read(),
            Ok(OperationResult::BatteryStatus(BatteryStatus {
                level: 43,
                plugged: 2
            }))
        );
        level.store(91, Ordering::SeqCst);
        assert_eq!(
            read(),
            Ok(OperationResult::BatteryStatus(BatteryStatus {
                level: 91,
                plugged: 2
            }))
        );
        level.store(255, Ordering::SeqCst);
        assert_eq!(read(), Err("remote_error"));
        a.stop();
        wait(&b, "disconnected");
        pair();
        let next = a.projection(Source::HyperOs4).unwrap();
        assert_eq!(p.id, next.id);
        assert_ne!(p.session_id, next.session_id);
        assert_eq!(read(), Err("stale_session"));
        assert_eq!(
            a.request_operation(
                Source::HyperOs4,
                "qj:unregistered",
                &next.session_id,
                Operation::BatteryStatus
            ),
            Err("peer_offline")
        );
        a.stop();
        b.stop();
    }
}
