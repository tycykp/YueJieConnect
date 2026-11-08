//! Parent-authorized, bidirectional CONTROL ONLY child channel. No discovery or pairing.
//! NNpsk0 uses a one-use secret delivered over the current confirmed parent Noise session.
//! A physical RFCOMM/FD connection is never sufficient: identity-bound handshake plus READY /
//! ACK / COMMIT / ACK must complete. The owner performs the atomic parent-thread migration.
use sha2::{Digest, Sha256};
use std::collections::{HashSet, VecDeque};
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, Weak};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const MAX_FRAME: usize = 16_384;
pub const MAX_QUEUED_BYTES: usize = 262_144;
pub const MAX_QUEUED_FRAMES: usize = 32;
const POLL: Duration = Duration::from_millis(50);
const HANDSHAKE: Duration = Duration::from_secs(8);
const STALL: Duration = Duration::from_secs(5);
const PING: Duration = Duration::from_secs(1);
const MAX_RECORD: usize = MAX_FRAME + 26;
const PATTERN: &str = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";

fn lock<T>(v: &Mutex<T>) -> MutexGuard<'_, T> { v.lock().unwrap_or_else(|p| p.into_inner()) }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role { Initiator, Responder }
impl Role { fn direction(self) -> u8 { if self == Self::Initiator { 0 } else { 1 } } }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CloseReason { LocalClosed, ParentEnded, Revoked, Expired, AuthenticationFailed, ProtocolViolation, TransportFailed, PeerClosed, IdleTimeout, Backpressure }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error { InvalidScope, AlreadyUsed, Capacity, EntropyUnavailable, Full, InvalidFrame, Closed(CloseReason) }
impl std::fmt::Display for Error {
    fn fmt(&self, out: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(out, "{self:?}") }
}
impl std::error::Error for Error {}
type Result<T> = std::result::Result<T, Error>;

/// A pollable duplex stream. Android JNI transfers the socketpair FD into UnixStream. Tests use
/// TcpStream on Windows. Implementations MUST honor read/write timeouts and shutdown both halves.
pub trait ControlIo: Read + Write + Send + Sync {
    fn timeouts(&self, poll: Duration) -> io::Result<()>;
    fn clone_shutdown(&self) -> io::Result<Box<dyn ControlIo>>;
    fn shutdown_both(&self);
}
impl ControlIo for TcpStream {
    fn timeouts(&self, poll: Duration) -> io::Result<()> { self.set_read_timeout(Some(poll))?; self.set_write_timeout(Some(poll)) }
    fn clone_shutdown(&self) -> io::Result<Box<dyn ControlIo>> { Ok(Box::new(self.try_clone()?)) }
    fn shutdown_both(&self) { let _ = self.shutdown(Shutdown::Both); }
}
#[cfg(unix)]
impl ControlIo for std::os::unix::net::UnixStream {
    fn timeouts(&self, poll: Duration) -> io::Result<()> { self.set_read_timeout(Some(poll))?; self.set_write_timeout(Some(poll)) }
    fn clone_shutdown(&self) -> io::Result<Box<dyn ControlIo>> { Ok(Box::new(self.try_clone()?)) }
    fn shutdown_both(&self) { let _ = self.shutdown(Shutdown::Both); }
}
/// No Clone/Debug/Deserialize. Even a failed ticket/handshake consumes this key's ledger entry.
pub struct OneTimeKey { bytes: [u8; 32], exported: bool }
impl OneTimeKey {
    pub fn generate() -> Result<Self> {
        let mut bytes = [0; 32]; getrandom::getrandom(&mut bytes).map_err(|_| Error::EntropyUnavailable)?;
        Self::from_authenticated_control(bytes)
    }
    pub fn from_authenticated_control(bytes: [u8; 32]) -> Result<Self> {
        if bytes == [0; 32] { return Err(Error::InvalidScope); }
        Ok(Self { bytes, exported: false })
    }
    /// Owner transmits ONCE over the current confirmed authenticated parent. Never log it.
    pub fn for_authenticated_control(&mut self) -> Result<[u8; 32]> {
        if self.exported { return Err(Error::AlreadyUsed); }
        self.exported = true; Ok(self.bytes)
    }
}
impl Drop for OneTimeKey { fn drop(&mut self) { self.bytes.fill(0); } }
struct Authority {
    session: [u8; 32], local: [u8; 32], remote: [u8; 32], state: Mutex<AuthorityState>,
}
#[derive(Default)]
struct AuthorityState {
    ended: bool, granted: bool, epoch: u64, highest_generation: u64,
    streams: HashSet<u64>, keys: HashSet<[u8; 32]>, children: Vec<Weak<Shared>>,
}
/// Only the owner of the CURRENT both-confirmed parent session may construct this. There is no
/// deserializer: public payloads/claimed peer names cannot create authority. Keep ONE per session.
#[derive(Clone)]
pub struct SessionAuthorization(Arc<Authority>);
impl SessionAuthorization {
    pub fn confirmed(session: [u8; 32], local: [u8; 32], remote: [u8; 32]) -> Result<Self> {
        if session == [0; 32] || local == [0; 32] || remote == [0; 32] || local == remote { return Err(Error::InvalidScope); }
        Ok(Self(Arc::new(Authority { session, local, remote, state: Mutex::new(AuthorityState::default()) })))
    }
    /// Separate current explicit control/network authorization; confirmation alone grants nothing.
    pub fn grant_after_consent(&self) -> Result<()> {
        let children = {
            let mut state = lock(&self.0.state);
            if state.ended { return Err(Error::Closed(CloseReason::ParentEnded)); }
            state.epoch = state.epoch.checked_add(1).ok_or(Error::InvalidScope)?; state.granted = true;
            state.children.iter().filter_map(Weak::upgrade).collect::<Vec<_>>()
        };
        for child in children { child.close(CloseReason::Revoked); } Ok(())
    }
    pub fn authorize(&self, generation: u64, stream_id: u64, role: Role, key: OneTimeKey, lifetime: Duration) -> Result<ControlTicket> {
        if generation == 0 || generation > i64::MAX as u64 || stream_id == 0 || stream_id > i64::MAX as u64
            || lifetime.is_zero() || lifetime > Duration::from_secs(900) { return Err(Error::InvalidScope); }
        let mut state = lock(&self.0.state);
        self.0.check_state(&state, state.epoch)?;
        let digest: [u8; 32] = Sha256::digest(key.bytes).into();
        if generation <= state.highest_generation || state.streams.contains(&stream_id) || state.keys.contains(&digest) { return Err(Error::AlreadyUsed); }
        if state.streams.len() >= 64 { return Err(Error::Capacity); }
        state.highest_generation = generation; state.streams.insert(stream_id); state.keys.insert(digest);
        Ok(ControlTicket { authority: self.0.clone(), epoch: state.epoch, generation, stream_id, role, key, deadline: Instant::now() + lifetime })
    }
    pub fn revoke(&self) { self.invalidate(false); }
    pub fn end_session(&self) { self.invalidate(true); }
    fn invalidate(&self, end: bool) {
        let children = {
            let mut state = lock(&self.0.state); state.granted = false; state.ended |= end;
            state.children.iter().filter_map(Weak::upgrade).collect::<Vec<_>>()
        };
        for child in children { child.close(if end { CloseReason::ParentEnded } else { CloseReason::Revoked }); }
    }
}
impl Authority {
    fn check_state(&self, state: &AuthorityState, epoch: u64) -> Result<()> {
        if state.ended { Err(Error::Closed(CloseReason::ParentEnded)) }
        else if !state.granted || state.epoch != epoch { Err(Error::Closed(CloseReason::Revoked)) } else { Ok(()) }
    }
    fn check(&self, epoch: u64) -> Result<()> { self.check_state(&lock(&self.state), epoch) }
    fn check_ticket(&self, epoch: u64, generation: u64) -> Result<()> {
        let state = lock(&self.state); self.check_state(&state, epoch)?;
        if state.highest_generation != generation { Err(Error::AlreadyUsed) } else { Ok(()) }
    }
}
pub struct ControlTicket { authority: Arc<Authority>, epoch: u64, generation: u64, stream_id: u64, role: Role, key: OneTimeKey, deadline: Instant }
impl ControlTicket {
    fn prologue(&self) -> Vec<u8> {
        let mut out = b"qiangjian-connect/stable-control/v1\0".to_vec();
        out.extend_from_slice(&self.authority.session); out.extend_from_slice(&self.generation.to_be_bytes()); out.extend_from_slice(&self.stream_id.to_be_bytes());
        let (initiator, responder) = if self.role == Role::Initiator { (self.authority.local, self.authority.remote) } else { (self.authority.remote, self.authority.local) };
        out.extend_from_slice(&initiator); out.extend_from_slice(&responder); out
    }
}
#[derive(Default)]
struct Queues { outgoing: VecDeque<Vec<u8>>, incoming: VecDeque<Vec<u8>>, out_bytes: usize, out_frames: usize, in_bytes: usize, closed: Option<CloseReason> }
struct Shared {
    authority: Arc<Authority>, epoch: u64, deadline: Instant, generation: u64, stream_id: u64,
    shutdown: Box<dyn ControlIo>, queues: Mutex<Queues>, changed: Condvar,
}
impl Shared {
    fn close(&self, reason: CloseReason) {
        { let mut q = lock(&self.queues); if q.closed.is_some() { return; } q.closed = Some(reason); q.incoming.clear(); q.outgoing.clear(); q.in_bytes = 0; q.out_bytes = 0; q.out_frames = 0; }
        self.shutdown.shutdown_both(); self.changed.notify_all();
    }
    fn check(&self) -> Result<()> {
        if let Some(reason) = lock(&self.queues).closed { return Err(Error::Closed(reason)); }
        let result = self.authority.check(self.epoch).and_then(|_| if Instant::now() < self.deadline { Ok(()) } else { Err(Error::Closed(CloseReason::Expired)) });
        if let Err(Error::Closed(reason)) = result { self.close(reason); } result
    }
}
pub struct ChildControl { shared: Arc<Shared>, worker: Option<JoinHandle<()>> }
impl ChildControl {
    /// Blocking <=8s setup: run off Binder/UI and the parent frame-processing thread. Ownership of
    /// the transferred FD is consumed on every path. Owner MUST keep parent processing during setup.
    pub fn establish(mut socket: Box<dyn ControlIo>, ticket: ControlTicket) -> Result<Self> {
        socket.timeouts(POLL).map_err(|_| Error::Closed(CloseReason::TransportFailed))?;
        let shared = Arc::new(Shared { authority: ticket.authority.clone(), epoch: ticket.epoch, deadline: ticket.deadline,
            generation: ticket.generation, stream_id: ticket.stream_id, shutdown: socket.clone_shutdown().map_err(|_| Error::Closed(CloseReason::TransportFailed))?,
            queues: Mutex::new(Queues::default()), changed: Condvar::new() });
        { let mut a = lock(&ticket.authority.state); ticket.authority.check_state(&a, ticket.epoch)?; a.children.retain(|w| w.strong_count() != 0); a.children.push(Arc::downgrade(&shared)); }
        let setup = (|| {
            ticket.authority.check_ticket(ticket.epoch, ticket.generation)?;
            let prologue = ticket.prologue();
            let builder = snow::Builder::new(PATTERN.parse().map_err(|_| Error::InvalidScope)?).prologue(&prologue).psk(0, &ticket.key.bytes);
            let mut hs = if ticket.role == Role::Initiator { builder.build_initiator() } else { builder.build_responder() }.map_err(|_| Error::InvalidScope)?;
            let until = (Instant::now() + HANDSHAKE).min(ticket.deadline);
            let mut reader = Records::default(); let mut buf = [0; 256];
            while !hs.is_handshake_finished() {
                check_deadline(&shared, until)?;
                if hs.is_my_turn() {
                    let size = hs.write_message(&[], &mut buf).map_err(|_| auth())?;
                    write_record(socket.as_mut(), &buf[..size], &shared, until)?;
                } else if let Some(bytes) = reader.poll(socket.as_mut(), 256, &shared, until)? {
                    if hs.read_message(&bytes, &mut buf).map_err(|_| auth())? != 0 { return Err(protocol()); }
                }
            }
            let mut noise = hs.into_transport_mode().map_err(|_| auth())?;
            // The responder also requires the initiator's authenticated ACK of its ready message.
            if ticket.role == Role::Initiator {
                send(&mut noise, socket.as_mut(), b"QJC_READY", &shared, until)?;
                expect(&mut noise, socket.as_mut(), &mut reader, b"QJC_READY_ACK", &shared, until)?;
                send(&mut noise, socket.as_mut(), b"QJC_COMMIT", &shared, until)?;
                expect(&mut noise, socket.as_mut(), &mut reader, b"QJC_COMMIT_ACK", &shared, until)?;
            } else {
                expect(&mut noise, socket.as_mut(), &mut reader, b"QJC_READY", &shared, until)?;
                send(&mut noise, socket.as_mut(), b"QJC_READY_ACK", &shared, until)?;
                expect(&mut noise, socket.as_mut(), &mut reader, b"QJC_COMMIT", &shared, until)?;
                send(&mut noise, socket.as_mut(), b"QJC_COMMIT_ACK", &shared, until)?;
            }
            shared.check()?; ticket.authority.check_ticket(ticket.epoch, ticket.generation)?; Ok(noise)
        })();
        let mut noise = match setup { Ok(noise) => noise, Err(error) => { shared.close(reason(error)); return Err(error); } };
        let role = ticket.role; drop(ticket);
        let work = shared.clone();
        let worker = thread::Builder::new().name("qj-stable-control".into()).spawn(move || {
            let result = run(socket.as_mut(), &mut noise, &work, role);
            work.close(result.err().map(reason).unwrap_or(CloseReason::PeerClosed));
        }).map_err(|_| { shared.close(CloseReason::TransportFailed); Error::Closed(CloseReason::TransportFailed) })?;
        let child = Self { shared, worker: Some(worker) }; child.shared.check()?; Ok(child)
    }
    pub fn ready(&self) -> bool { self.shared.check().is_ok() }
    pub fn generation(&self) -> u64 { self.shared.generation }
    pub fn stream_id(&self) -> u64 { self.shared.stream_id }
    /// Bounded LOCAL ENQUEUE only. This is not an RPC execution or remote acknowledgement.
    pub fn write_frame(&self, bytes: Vec<u8>) -> Result<()> {
        if bytes.is_empty() || bytes.len() > MAX_FRAME { return Err(Error::InvalidFrame); } self.shared.check()?;
        let mut q = lock(&self.shared.queues);
        if let Some(reason) = q.closed { return Err(Error::Closed(reason)); }
        if q.out_frames >= MAX_QUEUED_FRAMES || q.out_bytes + bytes.len() > MAX_QUEUED_BYTES { return Err(Error::Full); }
        q.out_bytes += bytes.len(); q.out_frames += 1; q.outgoing.push_back(bytes); Ok(())
    }
    pub fn read_frame(&self, timeout: Duration) -> Result<Option<Vec<u8>>> {
        let until = Instant::now().checked_add(timeout).ok_or(Error::InvalidScope)?;
        loop {
            self.shared.check()?; let mut q = lock(&self.shared.queues);
            if let Some(reason) = q.closed { return Err(Error::Closed(reason)); }
            if let Some(bytes) = q.incoming.pop_front() { q.in_bytes -= bytes.len(); return Ok(Some(bytes)); }
            if Instant::now() >= until { return Ok(None); }
            let _guard = self.shared.changed.wait_timeout(q, until.saturating_duration_since(Instant::now()).min(POLL)).unwrap_or_else(|p| p.into_inner());
        }
    }
    pub fn closed_reason(&self) -> Option<CloseReason> { let _ = self.shared.check(); lock(&self.shared.queues).closed }
    pub fn close(&self) { self.shared.close(CloseReason::LocalClosed); }
}
impl Drop for ChildControl { fn drop(&mut self) { self.close(); if let Some(worker) = self.worker.take() { let _ = worker.join(); } } }
fn reason(error: Error) -> CloseReason { if let Error::Closed(reason) = error { reason } else { CloseReason::ProtocolViolation } }
fn auth() -> Error { Error::Closed(CloseReason::AuthenticationFailed) }
fn protocol() -> Error { Error::Closed(CloseReason::ProtocolViolation) }
fn check_deadline(shared: &Shared, until: Instant) -> Result<()> { shared.check()?; if Instant::now() >= until { Err(Error::Closed(CloseReason::IdleTimeout)) } else { Ok(()) } }
fn write_record(socket: &mut dyn ControlIo, bytes: &[u8], shared: &Shared, until: Instant) -> Result<()> {
    let mut record = (bytes.len() as u32).to_be_bytes().to_vec(); record.extend_from_slice(bytes); let mut offset = 0;
    while offset < record.len() {
        check_deadline(shared, until)?;
        match socket.write(&record[offset..]) {
            Ok(0) => return Err(Error::Closed(CloseReason::PeerClosed)), Ok(n) => offset += n,
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted) => {},
            Err(_) => return Err(Error::Closed(CloseReason::TransportFailed)),
        }
    } Ok(())
}
#[derive(Default)]
struct Records { bytes: Vec<u8> }
impl Records {
    fn poll(&mut self, socket: &mut dyn ControlIo, maximum: usize, shared: &Shared, until: Instant) -> Result<Option<Vec<u8>>> {
        loop {
            check_deadline(shared, until)?;
            let length = if self.bytes.len() >= 4 {
                let n = u32::from_be_bytes(self.bytes[..4].try_into().unwrap()) as usize;
                if n == 0 || n > maximum { return Err(protocol()); } Some(n)
            } else { None };
            if let Some(n) = length { if self.bytes.len() == n + 4 { return Ok(Some(std::mem::take(&mut self.bytes).split_off(4))); } }
            let need = length.map(|n| n + 4).unwrap_or(4) - self.bytes.len(); let mut buf = [0; 4096]; let count = need.min(buf.len());
            match socket.read(&mut buf[..count]) {
                Ok(0) => return Err(Error::Closed(CloseReason::PeerClosed)), Ok(n) => self.bytes.extend_from_slice(&buf[..n]),
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut | io::ErrorKind::Interrupted) => return Ok(None),
                Err(_) => return Err(Error::Closed(CloseReason::TransportFailed)),
            }
        }
    }
}
fn send(noise: &mut snow::TransportState, socket: &mut dyn ControlIo, plain: &[u8], shared: &Shared, until: Instant) -> Result<()> {
    let mut cipher = vec![0; plain.len() + 16]; let size = noise.write_message(plain, &mut cipher).map_err(|_| auth())?;
    write_record(socket, &cipher[..size], shared, until)
}
fn expect(noise: &mut snow::TransportState, socket: &mut dyn ControlIo, reader: &mut Records, expected: &[u8], shared: &Shared, until: Instant) -> Result<()> {
    loop { check_deadline(shared, until)?; if let Some(bytes) = reader.poll(socket, 256, shared, until)? {
        let mut plain = [0; 256]; let size = noise.read_message(&bytes, &mut plain).map_err(|_| auth())?;
        return if &plain[..size] == expected { Ok(()) } else { Err(protocol()) };
    } }
}
fn packet(role: Role, sequence: &mut u64, kind: u8, bytes: &[u8]) -> Result<Vec<u8>> {
    *sequence = sequence.checked_add(1).ok_or_else(protocol)?;
    let mut out = vec![role.direction(), kind]; out.extend_from_slice(&sequence.to_be_bytes()); out.extend_from_slice(bytes); Ok(out)
}
fn run(socket: &mut dyn ControlIo, noise: &mut snow::TransportState, shared: &Shared, role: Role) -> Result<()> {
    let (mut sent, mut received) = (0_u64, 0_u64); let mut last_read = Instant::now(); let mut last_write = Instant::now(); let mut reader = Records::default();
    loop {
        shared.check()?;
        if last_read.elapsed() >= STALL { return Err(Error::Closed(CloseReason::IdleTimeout)); }
        let outgoing = { lock(&shared.queues).outgoing.pop_front() };
        if let Some(bytes) = outgoing {
            let data = packet(role, &mut sent, 0, &bytes)?;
            send(noise, socket, &data, shared, Instant::now() + STALL)?;
            let mut q = lock(&shared.queues); q.out_bytes = q.out_bytes.saturating_sub(bytes.len()); q.out_frames = q.out_frames.saturating_sub(1); last_write = Instant::now();
        } else if last_write.elapsed() >= PING {
            send(noise, socket, &packet(role, &mut sent, 1, &[])?, shared, Instant::now() + STALL)?; last_write = Instant::now();
        }
        if let Some(cipher) = reader.poll(socket, MAX_RECORD, shared, last_read + STALL)? {
            let mut plain = vec![0; MAX_FRAME + 10]; let size = noise.read_message(&cipher, &mut plain).map_err(|_| auth())?; plain.truncate(size);
            if size < 10 || plain[0] == role.direction() || plain[0] > 1 { return Err(protocol()); }
            let sequence = u64::from_be_bytes(plain[2..10].try_into().unwrap());
            if sequence != received.checked_add(1).ok_or_else(protocol)? { return Err(protocol()); } received = sequence; last_read = Instant::now();
            match plain[1] {
                0 if size > 10 => {
                    let bytes = plain.split_off(10); let mut q = lock(&shared.queues);
                    if q.incoming.len() >= MAX_QUEUED_FRAMES || q.in_bytes + bytes.len() > MAX_QUEUED_BYTES { return Err(Error::Closed(CloseReason::Backpressure)); }
                    q.in_bytes += bytes.len(); q.incoming.push_back(bytes); shared.changed.notify_all();
                },
                1 if size == 10 => { send(noise, socket, &packet(role, &mut sent, 2, &[])?, shared, Instant::now() + STALL)?; last_write = Instant::now(); },
                2 if size == 10 => {},
                _ => return Err(protocol()),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    fn sockets() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let a = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let b = listener.accept().unwrap().0; (a, b)
    }
    fn authority(reverse: bool) -> SessionAuthorization {
        let (a, b) = if reverse { ([3; 32], [2; 32]) } else { ([2; 32], [3; 32]) };
        let auth = SessionAuthorization::confirmed([1; 32], a, b).unwrap(); auth.grant_after_consent().unwrap(); auth
    }
    fn ticket(a: &SessionAuthorization, role: Role, key: u8) -> ControlTicket {
        a.authorize(1, 1, role, OneTimeKey::from_authenticated_control([key; 32]).unwrap(), Duration::from_secs(60)).unwrap()
    }
    fn pair() -> (ChildControl, ChildControl, SessionAuthorization, SessionAuthorization) {
        let a = authority(false); let b = authority(true); let (sa, sb) = sockets();
        let tb = ticket(&b, Role::Responder, 4);
        let handle = thread::spawn(move || ChildControl::establish(Box::new(sb), tb));
        let ca = ChildControl::establish(Box::new(sa), ticket(&a, Role::Initiator, 4)).unwrap();
        let cb = handle.join().unwrap().unwrap(); (ca, cb, a, b)
    }
    #[test] fn confirmation_without_current_consent_cannot_authorize() {
        let a = SessionAuthorization::confirmed([1; 32], [2; 32], [3; 32]).unwrap();
        assert!(matches!(a.authorize(1, 1, Role::Initiator, OneTimeKey::generate().unwrap(), Duration::from_secs(10)), Err(Error::Closed(CloseReason::Revoked))));
        assert!(SessionAuthorization::confirmed([0; 32], [2; 32], [3; 32]).is_err());
        assert!(SessionAuthorization::confirmed([1; 32], [2; 32], [2; 32]).is_err());
    }
    #[test] fn psk_export_and_key_stream_generation_are_one_use() {
        let a = authority(false); let mut key = OneTimeKey::from_authenticated_control([4; 32]).unwrap();
        assert_eq!(key.for_authenticated_control().unwrap(), [4; 32]); assert_eq!(key.for_authenticated_control(), Err(Error::AlreadyUsed));
        drop(a.authorize(1, 1, Role::Initiator, key, Duration::from_secs(10)).unwrap());
        for (generation, stream, key) in [(2, 2, 4), (2, 1, 5), (1, 2, 5)] {
            assert!(matches!(a.authorize(generation, stream, Role::Initiator, OneTimeKey::from_authenticated_control([key; 32]).unwrap(), Duration::from_secs(10)), Err(Error::AlreadyUsed)));
        }
        a.revoke(); a.grant_after_consent().unwrap();
        assert!(matches!(a.authorize(2, 2, Role::Initiator, OneTimeKey::from_authenticated_control([4; 32]).unwrap(), Duration::from_secs(10)), Err(Error::AlreadyUsed)));
    }
    #[test] fn replay_ledger_capacity_never_evicts() {
        let a = authority(false);
        for n in 1..=64 { drop(a.authorize(n, n, Role::Initiator, OneTimeKey::from_authenticated_control([n as u8; 32]).unwrap(), Duration::from_secs(1)).unwrap()); }
        assert!(matches!(a.authorize(65, 65, Role::Initiator, OneTimeKey::from_authenticated_control([65; 32]).unwrap(), Duration::from_secs(1)), Err(Error::Capacity)));
    }
    #[test] fn authenticated_ready_channel_transfers_both_directions_and_max_frame() {
        let (a, b, _, _) = pair(); assert!(a.ready() && b.ready()); assert_eq!(a.generation(), 1); assert_eq!(a.stream_id(), 1);
        a.write_frame(vec![11; MAX_FRAME]).unwrap(); b.write_frame(b"synthetic-control".to_vec()).unwrap();
        assert_eq!(b.read_frame(Duration::from_secs(2)).unwrap().unwrap(), vec![11; MAX_FRAME]);
        assert_eq!(a.read_frame(Duration::from_secs(2)).unwrap().unwrap(), b"synthetic-control");
        assert!(matches!(a.write_frame(vec![0; MAX_FRAME + 1]), Err(Error::InvalidFrame)));
        assert!(matches!(a.write_frame(vec![]), Err(Error::InvalidFrame)));
    }
    #[test] fn wrong_psk_or_parent_session_or_identity_or_generation_fails() {
        for mismatch in 0..4 {
            let a = authority(false); let b = if mismatch == 1 {
                let b = SessionAuthorization::confirmed([9; 32], [3; 32], [2; 32]).unwrap(); b.grant_after_consent().unwrap(); b
            } else if mismatch == 2 {
                let b = SessionAuthorization::confirmed([1; 32], [3; 32], [9; 32]).unwrap(); b.grant_after_consent().unwrap(); b
            } else { authority(true) };
            let ta = ticket(&a, Role::Initiator, 4);
            let tb = b.authorize(if mismatch == 3 { 2 } else { 1 }, 1, Role::Responder, OneTimeKey::from_authenticated_control([if mismatch == 0 { 5 } else { 4 }; 32]).unwrap(), Duration::from_secs(10)).unwrap();
            let (sa, sb) = sockets(); let thread = thread::spawn(move || ChildControl::establish(Box::new(sb), tb));
            assert!(ChildControl::establish(Box::new(sa), ta).is_err()); assert!(thread.join().unwrap().is_err());
        }
    }
    #[test] fn parent_end_interrupts_pending_handshake_and_cannot_regrant() {
        let a = authority(true); let ticket = ticket(&a, Role::Responder, 4); let (_sa, sb) = sockets();
        let handle = thread::spawn(move || ChildControl::establish(Box::new(sb), ticket));
        a.end_session(); assert!(handle.join().unwrap().is_err());
        assert_eq!(a.grant_after_consent(), Err(Error::Closed(CloseReason::ParentEnded)));
    }
    #[test] fn revocation_and_regrant_close_old_child_generation() {
        let (a, b, auth, _) = pair(); auth.grant_after_consent().unwrap();
        assert!(!a.ready()); assert_eq!(a.closed_reason(), Some(CloseReason::Revoked));
        let until = Instant::now() + Duration::from_secs(2);
        while b.ready() && Instant::now() < until { thread::sleep(Duration::from_millis(5)); }
        assert!(!b.ready());
    }
    #[test] fn expired_ticket_and_older_unused_generation_cannot_establish() {
        let a = authority(false); let ta = a.authorize(1, 1, Role::Initiator, OneTimeKey::generate().unwrap(), Duration::from_nanos(1)).unwrap();
        let (sa, _sb) = sockets(); assert!(ChildControl::establish(Box::new(sa), ta).is_err());
        let a = authority(false); let old = ticket(&a, Role::Initiator, 4);
        drop(a.authorize(2, 2, Role::Initiator, OneTimeKey::generate().unwrap(), Duration::from_secs(1)).unwrap());
        let (sa, _sb) = sockets(); assert!(matches!(ChildControl::establish(Box::new(sa), old), Err(Error::AlreadyUsed)));
    }
    #[test] fn oversized_record_header_is_rejected_before_allocation() {
        let a = authority(true); let t = ticket(&a, Role::Responder, 4); let (mut sa, sb) = sockets();
        sa.write_all(&u32::MAX.to_be_bytes()).unwrap();
        assert!(matches!(ChildControl::establish(Box::new(sb), t), Err(Error::Closed(CloseReason::ProtocolViolation))));
    }
    #[test] fn raw_noise_handshake_without_ready_ack_is_not_ready_control() {
        let a = authority(false); let b = authority(true); let ta = ticket(&a, Role::Initiator, 4); let tb = ticket(&b, Role::Responder, 4);
        let (sa, mut sb) = sockets();
        let peer = thread::spawn(move || {
            let prologue = tb.prologue(); let mut hs = snow::Builder::new(PATTERN.parse().unwrap()).prologue(&prologue).psk(0, &tb.key.bytes).build_responder().unwrap();
            let mut prefix = [0; 4]; sb.read_exact(&mut prefix).unwrap(); let mut body = vec![0; u32::from_be_bytes(prefix) as usize]; sb.read_exact(&mut body).unwrap();
            let mut out = [0; 256]; hs.read_message(&body, &mut out).unwrap(); let n = hs.write_message(&[], &mut out).unwrap();
            sb.write_all(&(n as u32).to_be_bytes()).unwrap(); sb.write_all(&out[..n]).unwrap();
            // Correct handshake, then EOF instead of authenticated bilateral ready confirmation.
        });
        assert!(ChildControl::establish(Box::new(sa), ta).is_err()); peer.join().unwrap();
    }
    #[test] fn unread_authenticated_incoming_queue_is_bounded_and_fails_closed() {
        let (a, b, _, _) = pair(); let until = Instant::now() + Duration::from_secs(3);
        while b.ready() && Instant::now() < until {
            if a.write_frame(vec![1; MAX_FRAME]).is_err() { thread::sleep(Duration::from_millis(2)); }
        }
        assert!(!b.ready()); assert!(lock(&b.shared.queues).in_bytes <= MAX_QUEUED_BYTES);
    }
    #[test] fn local_close_terminates_peer_without_claiming_business_completion() {
        let (a, b, _, _) = pair(); a.close(); assert_eq!(a.closed_reason(), Some(CloseReason::LocalClosed));
        assert!(b.read_frame(Duration::from_secs(2)).is_err());
    }
    fn raw_read(socket: &mut TcpStream) -> Vec<u8> {
        let mut prefix = [0; 4]; socket.read_exact(&mut prefix).unwrap();
        let n = u32::from_be_bytes(prefix) as usize; assert!(n <= MAX_RECORD);
        let mut bytes = vec![0; n]; socket.read_exact(&mut bytes).unwrap(); bytes
    }
    fn raw_write(socket: &mut TcpStream, bytes: &[u8]) { socket.write_all(&(bytes.len() as u32).to_be_bytes()).unwrap(); socket.write_all(bytes).unwrap(); }
    fn raw_peer(mut socket: TcpStream, ticket: ControlTicket, plain: Vec<u8>, replay: bool) {
        socket.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let prologue = ticket.prologue();
        let mut hs = snow::Builder::new(PATTERN.parse().unwrap()).prologue(&prologue).psk(0, &ticket.key.bytes).build_responder().unwrap();
        let mut buf = [0; 256]; hs.read_message(&raw_read(&mut socket), &mut buf).unwrap();
        let n = hs.write_message(&[], &mut buf).unwrap(); raw_write(&mut socket, &buf[..n]);
        let mut noise = hs.into_transport_mode().unwrap();
        for (expected, reply) in [(b"QJC_READY".as_slice(), b"QJC_READY_ACK".as_slice()), (b"QJC_COMMIT".as_slice(), b"QJC_COMMIT_ACK".as_slice())] {
            let n = noise.read_message(&raw_read(&mut socket), &mut buf).unwrap(); assert_eq!(&buf[..n], expected);
            let n = noise.write_message(reply, &mut buf).unwrap(); raw_write(&mut socket, &buf[..n]);
        }
        let mut bytes = vec![0; plain.len() + 16]; let n = noise.write_message(&plain, &mut bytes).unwrap();
        raw_write(&mut socket, &bytes[..n]); if replay { raw_write(&mut socket, &bytes[..n]); }
        thread::sleep(Duration::from_millis(100));
    }
    #[test] fn malicious_encrypted_record_replay_fails_authentication() {
        let a = authority(false); let b = authority(true); let (sa, sb) = sockets(); let tb = ticket(&b, Role::Responder, 4);
        let payload = packet(Role::Responder, &mut 0, 0, b"synthetic-control").unwrap();
        let peer = thread::spawn(move || raw_peer(sb, tb, payload, true));
        let child = ChildControl::establish(Box::new(sa), ticket(&a, Role::Initiator, 4)).unwrap();
        let until = Instant::now() + Duration::from_secs(2);
        while child.ready() && Instant::now() < until { thread::sleep(Duration::from_millis(2)); }
        assert_eq!(child.closed_reason(), Some(CloseReason::AuthenticationFailed)); peer.join().unwrap();
    }
    #[test] fn malicious_authenticated_direction_sequence_and_kind_fail_protocol() {
        for (direction, sequence, kind) in [(Role::Initiator, 0, 0), (Role::Responder, 1, 0), (Role::Responder, 0, 99)] {
            let a = authority(false); let b = authority(true); let (sa, sb) = sockets(); let tb = ticket(&b, Role::Responder, 4);
            let payload = packet(direction, &mut {sequence}, kind, b"synthetic-control").unwrap();
            let peer = thread::spawn(move || raw_peer(sb, tb, payload, false));
            let child = ChildControl::establish(Box::new(sa), ticket(&a, Role::Initiator, 4)).unwrap();
            let until = Instant::now() + Duration::from_secs(2);
            while child.ready() && Instant::now() < until { thread::sleep(Duration::from_millis(2)); }
            assert_eq!(child.closed_reason(), Some(CloseReason::ProtocolViolation)); peer.join().unwrap();
        }
    }
}
