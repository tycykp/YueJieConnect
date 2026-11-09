//! Session-owned media rendezvous and local PFD pumps. Only the authenticated parent control
//! owner may construct offers/register native consent. No peer address or PSK enters an OEM pipe.
use crate::media_channel::{
    CloseReason, MediaChannel, OneTimeKey, Purpose, Role, SessionAuthorization, VideoPacket,
};
#[cfg(any(unix, test))]
use crate::media_channel::{MAX_ACCESS_UNIT, MAX_QUEUED_BYTES, MAX_QUEUED_FRAMES, PACKET_HEADER};
use std::collections::{HashMap, HashSet};
use std::io;
#[cfg(any(unix, test))]
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

pub type Result<T> = std::result::Result<T, &'static str>;
const POLL: Duration = Duration::from_millis(25);
const OPEN_TIMEOUT: Duration = Duration::from_secs(8);
#[cfg(any(unix, test))]
const PIPE_STALL: Duration = Duration::from_secs(5);
const MAX_LIFETIME: Duration = Duration::from_secs(3600);
const MAX_ACTIVE: usize = 8;
const MAX_HISTORY: usize = 64;

fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value.lock().unwrap_or_else(|poison| poison.into_inner())
}
fn index(purpose: Purpose) -> usize {
    purpose as usize - 1
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Registered,
    Opening,
    Ready,
    Closed,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RuntimeCloseReason {
    LocalClosed,
    SessionEnded,
    PermissionRevoked,
    Expired,
    OpenTimeout,
    TransportFailed,
    ProtocolViolation,
    LocalPipeClosed,
    LocalPipeFailed,
    Media(CloseReason),
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct StreamStatus {
    pub stream_id: u64,
    pub purpose: Purpose,
    pub role: Role,
    pub phase: Phase,
    pub reason: Option<RuntimeCloseReason>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RuntimeEvent {
    Ready { stream_id: u64 },
    Closed { stream_id: u64, reason: RuntimeCloseReason },
}

/// Control-plane-only, no Debug/Clone/Serde. Never expose to a generic OEM request or log.
pub struct MediaOffer {
    session: [u8; 32],
    stream: u64,
    purpose: Purpose,
    port: u16,
    secret: [u8; 32],
    lifetime: Duration,
}
impl MediaOffer {
    pub fn from_authenticated_control(
        session: [u8; 32], stream: u64, purpose: Purpose, port: u16,
        secret: [u8; 32], lifetime: Duration,
    ) -> Result<Self> {
        if session == [0; 32] || stream == 0 || stream > i64::MAX as u64 || port == 0
            || secret == [0; 32] || lifetime.is_zero() || lifetime > MAX_LIFETIME
        { return Err("media_offer_invalid"); }
        Ok(Self { session, stream, purpose, port, secret, lifetime })
    }
    pub fn session_id(&self) -> [u8; 32] { self.session }
    pub fn stream_id(&self) -> u64 { self.stream }
    pub fn purpose(&self) -> Purpose { self.purpose }
    pub fn port(&self) -> u16 { self.port }
    pub fn lifetime(&self) -> Duration { self.lifetime }
    pub fn key_for_authenticated_control(&self) -> [u8; 32] { self.secret }
}
impl Drop for MediaOffer {
    fn drop(&mut self) { self.secret.fill(0); }
}
struct SlotState {
    phase: Phase,
    reason: Option<RuntimeCloseReason>,
    opening_socket: Option<TcpStream>,
    channel: Option<Arc<MediaChannel>>,
    pipe: Option<Arc<PipeSocket>>,
}
struct Slot {
    id: u64,
    purpose: Purpose,
    role: Role,
    deadline: Instant,
    state: Mutex<SlotState>,
    changed: Condvar,
}
impl Slot {
    fn status(&self) -> StreamStatus {
        let state = lock(&self.state);
        StreamStatus { stream_id: self.id, purpose: self.purpose, role: self.role,
            phase: state.phase, reason: state.reason }
    }
    fn live(&self) -> bool {
        lock(&self.state).phase != Phase::Closed && Instant::now() < self.deadline
    }
}
struct Registry {
    history: HashSet<u64>,
    streams: HashMap<u64, Arc<Slot>>,
    granted: [bool; 2],
}
struct Inner {
    session: [u8; 32],
    peer: SocketAddr,
    authorization: SessionAuthorization,
    ended: AtomicBool,
    registry: Mutex<Registry>,
    events: mpsc::Sender<RuntimeEvent>,
}
impl Inner {
    fn slot(&self, stream: u64) -> Result<Arc<Slot>> {
        if self.ended.load(Ordering::SeqCst) { return Err("media_session_ended"); }
        lock(&self.registry).streams.get(&stream).cloned().ok_or("media_not_registered")
    }
    fn close_slot(&self, slot: &Arc<Slot>, reason: RuntimeCloseReason) {
        let channel = {
            let mut state = lock(&slot.state);
            if state.phase == Phase::Closed { return; }
            state.phase = Phase::Closed; state.reason = Some(reason);
            if let Some(socket) = state.opening_socket.take() { let _ = socket.shutdown(Shutdown::Both); }
            if let Some(pipe) = state.pipe.take() { pipe.shutdown(); }
            state.channel.take()
        };
        if let Some(channel) = channel {
            channel.close();
            // MediaChannel joins its bounded IO workers on Drop; keep that off Binder callers.
            thread::spawn(move || drop(channel));
        }
        slot.changed.notify_all();
        let _ = self.events.send(RuntimeEvent::Closed { stream_id: slot.id, reason });
    }
    fn ready(self: &Arc<Self>, slot: Arc<Slot>, socket: TcpStream, ticket: crate::media_channel::MediaTicket) {
        let cloned = match socket.try_clone() {
            Ok(cloned) => cloned,
            Err(_) => { self.close_slot(&slot, RuntimeCloseReason::TransportFailed); return; }
        };
        {
            let mut state = lock(&slot.state);
            if state.phase != Phase::Opening || !slot.live_unlocked(&state) { return; }
            state.opening_socket = Some(cloned);
        }
        match MediaChannel::establish(socket, ticket) {
            Ok((channel, notifications)) => {
                let channel = Arc::new(channel);
                {
                    let mut state = lock(&slot.state);
                    state.opening_socket = None;
                    if state.phase != Phase::Opening || self.ended.load(Ordering::SeqCst) || Instant::now() >= slot.deadline {
                        channel.close(); return;
                    }
                    state.channel = Some(channel); state.phase = Phase::Ready;
                    // Serialize Ready before a concurrent close can emit Closed for this stream.
                    let _ = self.events.send(RuntimeEvent::Ready { stream_id: slot.id });
                }
                slot.changed.notify_all();
                let weak = Arc::downgrade(self);
                thread::spawn(move || {
                    if let Ok(reason) = notifications.recv() {
                        if let Some(inner) = weak.upgrade() { inner.close_slot(&slot, RuntimeCloseReason::Media(reason)); }
                    }
                });
            }
            Err(_) => self.close_slot(&slot, RuntimeCloseReason::TransportFailed),
        }
    }
}
impl Slot {
    fn live_unlocked(&self, state: &SlotState) -> bool {
        state.phase != Phase::Closed && Instant::now() < self.deadline
    }
}

/// Construct exactly once per confirmed parent session. Drop/close_session revoke all streams.
/// Individual native grants are registered separately; pairing alone cannot open any media.
pub struct MediaRuntime { inner: Arc<Inner> }
impl MediaRuntime {
    pub fn confirmed(
        session_id: [u8; 32], local_peer: [u8; 32], remote_peer: [u8; 32], authenticated_peer: SocketAddr,
    ) -> Result<(Self, mpsc::Receiver<RuntimeEvent>)> {
        if authenticated_peer.ip().is_unspecified() || authenticated_peer.ip().is_multicast() { return Err("media_peer_invalid"); }
        let authorization = SessionAuthorization::confirmed(session_id, local_peer, remote_peer).map_err(|_| "media_scope_invalid")?;
        let (events, receiver) = mpsc::channel();
        Ok((Self { inner: Arc::new(Inner { session: session_id, peer: authenticated_peer, authorization,
            ended: AtomicBool::new(false), registry: Mutex::new(Registry { history: HashSet::new(), streams: HashMap::new(), granted: [false; 2] }), events }) }, receiver))
    }
    /// Called by the verified native control owner only AFTER this stream's fresh native consent.
    pub fn register_local(&self, stream: u64, purpose: Purpose, role: Role, lifetime: Duration) -> Result<()> {
        if stream == 0 || stream > i64::MAX as u64 || lifetime.is_zero() || lifetime > MAX_LIFETIME { return Err("media_scope_invalid"); }
        if self.inner.ended.load(Ordering::SeqCst) { return Err("media_session_ended"); }
        let mut registry = lock(&self.inner.registry);
        if self.inner.ended.load(Ordering::SeqCst) { return Err("media_session_ended"); }
        if registry.history.contains(&stream) { return Err("media_stream_reused"); }
        if registry.history.len() >= MAX_HISTORY { return Err("media_history_capacity"); }
        if registry.streams.values().filter(|slot| slot.status().phase != Phase::Closed).count() >= MAX_ACTIVE { return Err("media_active_capacity"); }
        if !registry.granted[index(purpose)] {
            self.inner.authorization.grant_after_consent(purpose).map_err(|_| "media_permission_revoked")?;
            registry.granted[index(purpose)] = true;
        }
        registry.history.insert(stream);
        registry.streams.insert(stream, Arc::new(Slot { id: stream, purpose, role, deadline: Instant::now() + lifetime,
            state: Mutex::new(SlotState { phase: Phase::Registered, reason: None, opening_socket: None, channel: None, pipe: None }), changed: Condvar::new() }));
        Ok(())
    }
    fn opening(&self, stream: u64, role: Role) -> Result<Arc<Slot>> {
        let slot = self.inner.slot(stream)?;
        if slot.role != role { return Err("media_role_mismatch"); }
        let mut state = lock(&slot.state);
        if state.phase != Phase::Registered { return Err("media_stream_already_opened"); }
        if Instant::now() >= slot.deadline { drop(state); self.inner.close_slot(&slot, RuntimeCloseReason::Expired); return Err("media_stream_expired"); }
        state.phase = Phase::Opening; drop(state); Ok(slot)
    }
    pub fn listen_offer(&self, stream: u64) -> Result<MediaOffer> {
        let slot = self.opening(stream, Role::Sender)?;
        let result = self.make_offer(slot.clone());
        if result.is_err() { self.inner.close_slot(&slot, RuntimeCloseReason::TransportFailed); }
        result
    }
    fn make_offer(&self, slot: Arc<Slot>) -> Result<MediaOffer> {
        let bind_ip = match self.inner.peer.ip() { IpAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED), IpAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED) };
        let listener = TcpListener::bind(SocketAddr::new(bind_ip, 0)).map_err(|_| "media_listen_failed")?;
        listener.set_nonblocking(true).map_err(|_| "media_listen_failed")?;
        let port = listener.local_addr().map_err(|_| "media_listen_failed")?.port();
        let key = OneTimeKey::generate().map_err(|_| "media_entropy_failed")?;
        let lifetime = slot.deadline.saturating_duration_since(Instant::now());
        let offer = MediaOffer::from_authenticated_control(self.inner.session, slot.id, slot.purpose, port, key.for_authenticated_control(), lifetime)?;
        let ticket = self.inner.authorization.authorize_stream(slot.id, slot.purpose, Role::Sender, key, lifetime).map_err(|_| "media_authorization_failed")?;
        let inner = self.inner.clone();
        thread::Builder::new().name("yj-media-listen".into()).spawn(move || {
            let deadline = Instant::now() + OPEN_TIMEOUT;
            while slot.live() && !inner.ended.load(Ordering::SeqCst) {
                if Instant::now() >= deadline { inner.close_slot(&slot, RuntimeCloseReason::OpenTimeout); return; }
                match listener.accept() {
                    Ok((socket, address)) if address.ip() == inner.peer.ip() => { inner.ready(slot, socket, ticket); return; }
                    Ok((socket, _)) => { let _ = socket.shutdown(Shutdown::Both); }
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(POLL),
                    Err(_) => { inner.close_slot(&slot, RuntimeCloseReason::TransportFailed); return; }
                }
            }
            inner.close_slot(&slot, RuntimeCloseReason::Expired);
        }).map_err(|_| "media_worker_failed")?;
        Ok(offer)
    }
    pub fn accept_offer(&self, offer: MediaOffer) -> Result<()> {
        if offer.session != self.inner.session { return Err("media_session_mismatch"); }
        let registered = self.inner.slot(offer.stream)?;
        if registered.purpose != offer.purpose { return Err("media_purpose_mismatch"); }
        let slot = self.opening(offer.stream, Role::Receiver)?;
        let result = self.connect_offer(slot.clone(), offer);
        if result.is_err() { self.inner.close_slot(&slot, RuntimeCloseReason::TransportFailed); }
        result
    }
    fn connect_offer(&self, slot: Arc<Slot>, offer: MediaOffer) -> Result<()> {
        let lifetime = slot.deadline.saturating_duration_since(Instant::now()).min(offer.lifetime);
        let key = OneTimeKey::from_authenticated_control(offer.secret).map_err(|_| "media_offer_invalid")?;
        let ticket = self.inner.authorization.authorize_stream(slot.id, slot.purpose, Role::Receiver, key, lifetime).map_err(|_| "media_authorization_failed")?;
        let mut address = self.inner.peer; address.set_port(offer.port);
        let inner = self.inner.clone();
        thread::Builder::new().name("yj-media-connect".into()).spawn(move || {
            if !slot.live() { inner.close_slot(&slot, RuntimeCloseReason::Expired); return; }
            match TcpStream::connect_timeout(&address, OPEN_TIMEOUT.min(lifetime)) {
                Ok(socket) => inner.ready(slot, socket, ticket),
                Err(_) => inner.close_slot(&slot, RuntimeCloseReason::TransportFailed),
            }
        }).map_err(|_| "media_worker_failed")?;
        Ok(())
    }
    pub fn status(&self, stream: u64) -> Result<StreamStatus> { Ok(self.inner.slot(stream)?.status()) }
    pub fn offer(&self, stream: u64, packet: VideoPacket) -> bool {
        let Ok(slot) = self.inner.slot(stream) else { return false; };
        let state = lock(&slot.state);
        if slot.role != Role::Sender || packet.stream_id() != stream || state.pipe.is_some() { return false; }
        state.channel.as_ref().is_some_and(|channel| channel.offer(packet))
    }
    pub fn read_packet(&self, stream: u64, timeout: Duration) -> Result<Option<VideoPacket>> {
        let slot = self.inner.slot(stream)?;
        if slot.role != Role::Receiver { return Err("media_role_mismatch"); }
        let channel = {
            let state = lock(&slot.state);
            if state.pipe.is_some() { return Err("media_pipe_attached"); }
            state.channel.clone().ok_or("media_not_ready")?
        };
        channel.read_packet(timeout).map_err(|_| "media_channel_closed")
    }
    pub fn poll_packet(&self, stream: u64) -> Result<Option<VideoPacket>> { self.read_packet(stream, Duration::ZERO) }
    pub fn close_stream(&self, stream: u64) -> Result<()> {
        if stream == 0 || stream > i64::MAX as u64 { return Err("media_scope_invalid"); }
        let slot = {
            let mut registry = lock(&self.inner.registry);
            if !registry.history.contains(&stream) && registry.history.len() >= MAX_HISTORY { return Err("media_history_capacity"); }
            registry.history.insert(stream); registry.streams.get(&stream).cloned()
        };
        if let Some(slot) = slot { self.inner.close_slot(&slot, RuntimeCloseReason::LocalClosed); }
        Ok(())
    }
    pub fn revoke_purpose(&self, purpose: Purpose) {
        let slots = {
            let mut registry = lock(&self.inner.registry);
            registry.granted[index(purpose)] = false;
            self.inner.authorization.revoke(purpose);
            registry.streams.values().filter(|slot| slot.purpose == purpose).cloned().collect::<Vec<_>>()
        };
        for slot in slots { self.inner.close_slot(&slot, RuntimeCloseReason::PermissionRevoked); }
    }
    pub fn tick(&self) {
        let slots = lock(&self.inner.registry).streams.values().cloned().collect::<Vec<_>>();
        for slot in slots {
            if Instant::now() >= slot.deadline { self.inner.close_slot(&slot, RuntimeCloseReason::Expired); }
        }
    }
    pub fn close_session(&self) {
        if self.inner.ended.swap(true, Ordering::SeqCst) { return; }
        self.inner.authorization.end_session();
        let slots = lock(&self.inner.registry).streams.values().cloned().collect::<Vec<_>>();
        for slot in slots { self.inner.close_slot(&slot, RuntimeCloseReason::SessionEnded); }
    }
    /// Consumes the Unix socketpair fd passed by JNI. It is never duplicated into a new owner.
    #[cfg(unix)]
    pub fn attach_local_pipe(&self, stream: u64, fd: std::os::fd::OwnedFd) -> Result<()> {
        self.attach_pipe(stream, PipeSocket::Unix(std::os::unix::net::UnixStream::from(fd)))
    }
    #[cfg(any(unix, test))]
    fn attach_pipe(&self, stream: u64, pipe: PipeSocket) -> Result<()> {
        let slot = self.inner.slot(stream)?;
        pipe.configure().map_err(|_| "media_pipe_invalid")?;
        let shutdown = Arc::new(pipe.try_clone().map_err(|_| "media_pipe_invalid")?);
        {
            let mut state = lock(&slot.state);
            if state.phase == Phase::Closed || state.pipe.is_some() { return Err("media_pipe_already_attached"); }
            state.pipe = Some(shutdown);
        }
        if slot.role == Role::Receiver {
            let mut monitor = pipe.try_clone().map_err(|_| "media_pipe_invalid")?;
            let inner = self.inner.clone(); let observed = slot.clone();
            thread::Builder::new().name("yj-media-pfd-death".into()).spawn(move || {
                let mut byte = [0; 1];
                while observed.live() {
                    match monitor.read(&mut byte) {
                        Ok(0) => { inner.close_slot(&observed, RuntimeCloseReason::LocalPipeClosed); return; }
                        Ok(_) => { inner.close_slot(&observed, RuntimeCloseReason::ProtocolViolation); return; }
                        Err(error) if pending_io(&error) => {},
                        Err(_) => { inner.close_slot(&observed, RuntimeCloseReason::LocalPipeFailed); return; }
                    }
                }
            }).map_err(|_| { self.inner.close_slot(&slot, RuntimeCloseReason::LocalPipeFailed); "media_worker_failed" })?;
        }
        let inner = self.inner.clone(); let selected = slot.clone();
        thread::Builder::new().name("yj-media-pfd".into()).spawn(move || pump(inner, selected, pipe)).map_err(|_| {
            self.inner.close_slot(&slot, RuntimeCloseReason::LocalPipeFailed); "media_worker_failed"
        })?;
        Ok(())
    }
}
impl Drop for MediaRuntime { fn drop(&mut self) { self.close_session(); } }

// Production supports only kernel-created Unix socketpairs. TCP is available exclusively to
// isolated Windows host tests, not an unauthenticated media entry point in a shipped build.
#[cfg(any(unix, test))]
enum PipeSocket {
    #[cfg(unix)] Unix(std::os::unix::net::UnixStream),
    #[cfg(test)] Tcp(TcpStream),
}
#[cfg(not(any(unix, test)))]
struct PipeSocket;
#[cfg(not(any(unix, test)))]
impl PipeSocket { fn shutdown(&self) {} }
#[cfg(any(unix, test))]
impl PipeSocket {
    fn configure(&self) -> io::Result<()> {
        match self {
            #[cfg(unix)] Self::Unix(pipe) => { pipe.set_read_timeout(Some(POLL))?; pipe.set_write_timeout(Some(POLL)) },
            #[cfg(test)] Self::Tcp(pipe) => { pipe.set_read_timeout(Some(POLL))?; pipe.set_write_timeout(Some(POLL)) },
        }
    }
    fn try_clone(&self) -> io::Result<Self> {
        match self {
            #[cfg(unix)] Self::Unix(pipe) => pipe.try_clone().map(Self::Unix),
            #[cfg(test)] Self::Tcp(pipe) => pipe.try_clone().map(Self::Tcp),
        }
    }
    fn shutdown(&self) {
        match self {
            #[cfg(unix)] Self::Unix(pipe) => { let _ = pipe.shutdown(Shutdown::Both); },
            #[cfg(test)] Self::Tcp(pipe) => { let _ = pipe.shutdown(Shutdown::Both); },
        }
    }
}
#[cfg(any(unix, test))]
impl Read for PipeSocket {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        match self {
            #[cfg(unix)] Self::Unix(pipe) => pipe.read(bytes),
            #[cfg(test)] Self::Tcp(pipe) => pipe.read(bytes),
        }
    }
}
#[cfg(any(unix, test))]
impl Write for PipeSocket {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        match self {
            #[cfg(unix)] Self::Unix(pipe) => pipe.write(bytes),
            #[cfg(test)] Self::Tcp(pipe) => pipe.write(bytes),
        }
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}
#[cfg(any(unix, test))]
fn pending_io(error: &io::Error) -> bool {
    matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted)
}
#[cfg(any(unix, test))]
fn pipe_read(pipe: &mut PipeSocket, buffer: &mut [u8], slot: &Slot, deadline: Instant) -> std::result::Result<(), RuntimeCloseReason> {
    let mut offset = 0;
    while offset < buffer.len() {
        if !slot.live() { return Err(RuntimeCloseReason::Expired); }
        if Instant::now() >= deadline { return Err(RuntimeCloseReason::LocalPipeFailed); }
        match pipe.read(&mut buffer[offset..]) {
            Ok(0) => return Err(RuntimeCloseReason::LocalPipeClosed),
            Ok(size) => offset += size,
            Err(error) if pending_io(&error) => {},
            Err(_) => return Err(RuntimeCloseReason::LocalPipeFailed),
        }
    }
    Ok(())
}
#[cfg(any(unix, test))]
fn pipe_write(pipe: &mut PipeSocket, buffer: &[u8], slot: &Slot, deadline: Instant) -> std::result::Result<(), RuntimeCloseReason> {
    let mut offset = 0;
    while offset < buffer.len() {
        if !slot.live() { return Err(RuntimeCloseReason::Expired); }
        if Instant::now() >= deadline { return Err(RuntimeCloseReason::LocalPipeFailed); }
        match pipe.write(&buffer[offset..]) {
            Ok(0) => return Err(RuntimeCloseReason::LocalPipeClosed),
            Ok(size) => offset += size,
            Err(error) if pending_io(&error) => {},
            Err(_) => return Err(RuntimeCloseReason::LocalPipeFailed),
        }
    }
    Ok(())
}
#[cfg(any(unix, test))]
fn pump(inner: Arc<Inner>, slot: Arc<Slot>, mut pipe: PipeSocket) {
    let result: std::result::Result<(), RuntimeCloseReason> = (|| {
        let ready_deadline = Instant::now() + OPEN_TIMEOUT;
        let channel = loop {
            if !slot.live() { return Err(RuntimeCloseReason::Expired); }
            if Instant::now() >= ready_deadline { return Err(RuntimeCloseReason::OpenTimeout); }
            let state = lock(&slot.state);
            if let Some(channel) = state.channel.clone() { break channel; }
            let _ = slot.changed.wait_timeout(state, POLL);
        };
        let mut last_sequence = 0;
        loop {
            if !slot.live() { return Err(RuntimeCloseReason::Expired); }
            if slot.role == Role::Sender {
                let mut length = [0; 4];
                // Silence between frames is handled by the authenticated media channel; one
                // in-progress local frame still has its own bounded stall deadline.
                loop {
                    match pipe.read(&mut length[..1]) {
                        Ok(0) => return Err(RuntimeCloseReason::LocalPipeClosed),
                        Ok(_) => break,
                        Err(error) if pending_io(&error) => {
                            if !slot.live() { return Err(RuntimeCloseReason::Expired); }
                            if channel.close_reason().is_some() { return Err(RuntimeCloseReason::TransportFailed); }
                        },
                        Err(_) => return Err(RuntimeCloseReason::LocalPipeFailed),
                    }
                }
                let deadline = Instant::now() + PIPE_STALL;
                pipe_read(&mut pipe, &mut length[1..], &slot, deadline)?;
                let length = u32::from_le_bytes(length) as usize;
                if !(PACKET_HEADER..=PACKET_HEADER + MAX_ACCESS_UNIT).contains(&length) { return Err(RuntimeCloseReason::ProtocolViolation); }
                while {
                    let usage = channel.queue_usage();
                    usage.frames >= MAX_QUEUED_FRAMES || usage.bytes > MAX_QUEUED_BYTES - length
                } {
                    if !slot.live() || Instant::now() >= deadline { return Err(RuntimeCloseReason::LocalPipeFailed); }
                    thread::sleep(POLL);
                }
                let mut wire = vec![0; length]; pipe_read(&mut pipe, &mut wire, &slot, deadline)?;
                let packet = VideoPacket::from_wire(wire).map_err(|_| RuntimeCloseReason::ProtocolViolation)?;
                if packet.stream_id() != slot.id || packet.sequence() <= last_sequence { return Err(RuntimeCloseReason::ProtocolViolation); }
                last_sequence = packet.sequence();
                if !channel.offer(packet) { return Err(RuntimeCloseReason::TransportFailed); }
            } else {
                let packet = match channel.read_packet(POLL).map_err(|_| RuntimeCloseReason::TransportFailed)? { Some(packet) => packet, None => continue };
                let wire = packet.as_wire(); let deadline = Instant::now() + PIPE_STALL;
                pipe_write(&mut pipe, &(wire.len() as u32).to_le_bytes(), &slot, deadline)?;
                pipe_write(&mut pipe, wire, &slot, deadline)?;
            }
        }
    })();
    pipe.shutdown();
    if let Err(reason) = result { inner.close_slot(&slot, reason); }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runtime(local: u8, remote: u8) -> (MediaRuntime, mpsc::Receiver<RuntimeEvent>) {
        MediaRuntime::confirmed([1; 32], [local; 32], [remote; 32], "127.0.0.1:1234".parse().unwrap()).unwrap()
    }
    fn register(runtime: &MediaRuntime, stream: u64, role: Role) {
        runtime.register_local(stream, Purpose::ScreenVideo, role, Duration::from_secs(20)).unwrap();
    }
    fn ready(events: &mpsc::Receiver<RuntimeEvent>, stream: u64) {
        assert_eq!(events.recv_timeout(Duration::from_secs(2)).unwrap(), RuntimeEvent::Ready { stream_id: stream });
    }
    fn closed(events: &mpsc::Receiver<RuntimeEvent>, stream: u64) -> RuntimeCloseReason {
        match events.recv_timeout(Duration::from_secs(2)).unwrap() {
            RuntimeEvent::Closed { stream_id, reason } => { assert_eq!(stream_id, stream); reason },
            _ => panic!("closed stream reported ready"),
        }
    }
    fn packet(stream: u64, sequence: u64, size: usize) -> VideoPacket {
        let mut wire = Vec::with_capacity(40 + size);
        wire.extend_from_slice(b"YJV1"); wire.extend_from_slice(&[0, 1, 0, 1]);
        wire.extend_from_slice(&stream.to_be_bytes()); wire.extend_from_slice(&sequence.to_be_bytes());
        wire.extend_from_slice(&(sequence * 100).to_be_bytes()); wire.extend_from_slice(&(size as u32).to_be_bytes());
        wire.extend_from_slice(&[0; 4]); wire.resize(40 + size, 0x5a); VideoPacket::from_wire(wire).unwrap()
    }
    fn pair() -> (MediaRuntime, mpsc::Receiver<RuntimeEvent>, MediaRuntime, mpsc::Receiver<RuntimeEvent>) {
        let (sender, tx) = runtime(2, 3); let (receiver, rx) = runtime(3, 2);
        register(&sender, 7, Role::Sender); register(&receiver, 7, Role::Receiver);
        let offer = sender.listen_offer(7).unwrap(); receiver.accept_offer(offer).unwrap();
        ready(&tx, 7); ready(&rx, 7); (sender, tx, receiver, rx)
    }
    fn local_pipe() -> (PipeSocket, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (server, _) = listener.accept().unwrap();
        client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        client.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
        (PipeSocket::Tcp(server), client)
    }
    #[test]
    fn consent_registration_and_actual_handshake_are_separate_from_ready() {
        let (sender, tx) = runtime(2, 3); let (receiver, rx) = runtime(3, 2);
        assert!(sender.listen_offer(7).is_err());
        register(&sender, 7, Role::Sender); assert_eq!(sender.status(7).unwrap().phase, Phase::Registered);
        let offer = sender.listen_offer(7).unwrap(); assert_eq!(sender.status(7).unwrap().phase, Phase::Opening);
        assert!(tx.try_recv().is_err()); assert!(!sender.offer(7, packet(7, 1, 3)));
        register(&receiver, 7, Role::Receiver); receiver.accept_offer(offer).unwrap(); ready(&tx, 7); ready(&rx, 7);
        let wire = packet(7, 1, 1234).as_wire().to_vec(); assert!(sender.offer(7, VideoPacket::from_wire(wire.clone()).unwrap()));
        assert_eq!(receiver.read_packet(7, Duration::from_secs(2)).unwrap().unwrap().as_wire(), wire);
    }
    #[test]
    fn local_scope_role_and_control_offer_are_checked_before_open() {
        let (sender, _) = runtime(2, 3); let (receiver, rx) = runtime(3, 2);
        register(&sender, 7, Role::Sender); register(&receiver, 7, Role::Receiver);
        assert!(receiver.listen_offer(7).is_err());
        let offer = sender.listen_offer(7).unwrap();
        let wrong = MediaOffer::from_authenticated_control([9; 32], 7, Purpose::ScreenVideo, offer.port(), offer.key_for_authenticated_control(), offer.lifetime()).unwrap();
        assert_eq!(receiver.accept_offer(wrong), Err("media_session_mismatch"));
        let wrong = MediaOffer::from_authenticated_control([1; 32], 7, Purpose::CameraVideo, offer.port(), offer.key_for_authenticated_control(), offer.lifetime()).unwrap();
        assert_eq!(receiver.accept_offer(wrong), Err("media_purpose_mismatch"));
        receiver.accept_offer(offer).unwrap(); ready(&rx, 7);
        assert!(receiver.accept_offer(MediaOffer::from_authenticated_control([1; 32], 7, Purpose::ScreenVideo, 1, [8; 32], Duration::from_secs(1)).unwrap()).is_err());
    }
    #[test]
    fn capacity_history_and_close_before_registration_cannot_be_bypassed() {
        let (runtime, _) = runtime(2, 3);
        runtime.close_stream(99).unwrap(); assert_eq!(runtime.register_local(99, Purpose::ScreenVideo, Role::Sender, Duration::from_secs(1)), Err("media_stream_reused"));
        for id in 1..=8 { register(&runtime, id, Role::Sender); }
        assert_eq!(runtime.register_local(9, Purpose::ScreenVideo, Role::Sender, Duration::from_secs(1)), Err("media_active_capacity"));
        for id in 1..=8 { runtime.close_stream(id).unwrap(); }
        for id in 9..=63 { runtime.close_stream(id).unwrap(); }
        assert_eq!(runtime.register_local(100, Purpose::ScreenVideo, Role::Sender, Duration::from_secs(1)), Err("media_history_capacity"));
    }
    #[test]
    fn stopping_one_stream_does_not_revoke_another_and_purposes_stay_separate() {
        let (runtime, _) = runtime(2, 3); register(&runtime, 7, Role::Sender); register(&runtime, 8, Role::Sender);
        runtime.register_local(9, Purpose::CameraVideo, Role::Sender, Duration::from_secs(20)).unwrap();
        runtime.close_stream(7).unwrap(); assert_eq!(runtime.status(8).unwrap().phase, Phase::Registered);
        runtime.revoke_purpose(Purpose::ScreenVideo); assert_eq!(runtime.status(8).unwrap().phase, Phase::Closed);
        assert_eq!(runtime.status(9).unwrap().phase, Phase::Registered);
        runtime.register_local(10, Purpose::ScreenVideo, Role::Sender, Duration::from_secs(20)).unwrap();
        assert!(runtime.listen_offer(10).is_ok()); assert_eq!(runtime.status(7).unwrap().phase, Phase::Closed);
    }
    #[test]
    fn session_end_revokes_ready_and_opening_streams_and_rejects_late_revival() {
        let (sender, tx, receiver, _) = pair(); register(&sender, 8, Role::Sender); let _offer = sender.listen_offer(8).unwrap();
        sender.close_session();
        let mut ids = HashSet::new();
        for _ in 0..2 { match tx.recv_timeout(Duration::from_secs(2)).unwrap() { RuntimeEvent::Closed { stream_id, .. } => { ids.insert(stream_id); }, _ => panic!("late ready") } }
        assert_eq!(ids, HashSet::from([7, 8]));
        assert!(!sender.offer(7, packet(7, 1, 1))); assert!(sender.listen_offer(8).is_err());
        assert!(sender.register_local(9, Purpose::ScreenVideo, Role::Sender, Duration::from_secs(1)).is_err());
        receiver.close_session();
    }
    #[test]
    fn local_lifetime_expiry_is_enforced_without_media_arriving() {
        let (runtime, events) = runtime(2, 3);
        runtime.register_local(7, Purpose::ScreenVideo, Role::Sender, Duration::from_millis(10)).unwrap();
        thread::sleep(Duration::from_millis(20)); runtime.tick(); assert_eq!(closed(&events, 7), RuntimeCloseReason::Expired);
        assert!(runtime.listen_offer(7).is_err());
    }
    #[test]
    fn real_pumps_round_trip_little_endian_length_and_one_mib_yjv1_without_json() {
        let (sender, _, receiver, _) = pair(); let (source_pipe, mut source) = local_pipe(); let (sink_pipe, mut sink) = local_pipe();
        sender.attach_pipe(7, source_pipe).unwrap(); receiver.attach_pipe(7, sink_pipe).unwrap();
        assert!(!sender.offer(7, packet(7, 1, 1))); assert_eq!(receiver.poll_packet(7).err(), Some("media_pipe_attached"));
        let wire = packet(7, 1, MAX_ACCESS_UNIT).into_wire(); let expected = wire.clone();
        let writer = thread::spawn(move || { source.write_all(&(wire.len() as u32).to_le_bytes()).unwrap(); source.write_all(&wire).unwrap(); source });
        let mut size = [0; 4]; sink.read_exact(&mut size).unwrap(); assert_eq!(u32::from_le_bytes(size) as usize, expected.len());
        let mut result = vec![0; expected.len()]; sink.read_exact(&mut result).unwrap(); assert_eq!(result, expected);
        let _source = writer.join().unwrap(); sender.close_session(); receiver.close_session();
    }
    #[test]
    fn malformed_local_frame_length_closes_stream_without_body_allocation() {
        let (sender, tx, receiver, _) = pair(); let (pipe, mut source) = local_pipe(); sender.attach_pipe(7, pipe).unwrap();
        source.write_all(&u32::MAX.to_le_bytes()).unwrap(); assert_eq!(closed(&tx, 7), RuntimeCloseReason::ProtocolViolation);
        assert_eq!(sender.status(7).unwrap().phase, Phase::Closed); receiver.close_session();
    }
    #[test]
    fn local_frame_cannot_select_a_different_stream() {
        let (sender, tx, receiver, _) = pair(); let (pipe, mut source) = local_pipe(); sender.attach_pipe(7, pipe).unwrap();
        let wire = packet(8, 1, 10).into_wire(); source.write_all(&(wire.len() as u32).to_le_bytes()).unwrap(); source.write_all(&wire).unwrap();
        assert_eq!(closed(&tx, 7), RuntimeCloseReason::ProtocolViolation); receiver.close_session();
    }
    #[test]
    fn sink_pipe_death_revokes_silent_capture_and_duplicate_attachment_is_rejected() {
        let (sender, _, receiver, rx) = pair(); let (pipe, client) = local_pipe(); receiver.attach_pipe(7, pipe).unwrap();
        let (duplicate, _) = local_pipe(); assert_eq!(receiver.attach_pipe(7, duplicate), Err("media_pipe_already_attached"));
        drop(client); assert_eq!(closed(&rx, 7), RuntimeCloseReason::LocalPipeClosed); sender.close_session();
    }
    #[test]
    fn listener_accepts_only_the_actual_parent_peer_address() {
        let (sender, events) = MediaRuntime::confirmed([1; 32], [2; 32], [3; 32], "127.0.0.2:1234".parse().unwrap()).unwrap();
        register(&sender, 7, Role::Sender); let offer = sender.listen_offer(7).unwrap();
        let mut intruder = TcpStream::connect((Ipv4Addr::LOCALHOST, offer.port())).unwrap(); intruder.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
        let mut byte = [0; 1]; assert_eq!(intruder.read(&mut byte).unwrap(), 0); assert!(events.try_recv().is_err());
        assert_eq!(sender.status(7).unwrap().phase, Phase::Opening); sender.close_stream(7).unwrap();
    }
}
