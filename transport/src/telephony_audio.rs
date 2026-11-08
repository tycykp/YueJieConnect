//! Duplex call-audio-only Noise channel. Control owner supplies the fresh native-approved call lease.
//! This cannot construct video/camera grants or obtain native microphone/call permissions.
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex, MutexGuard, Weak};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub const PACKET_BYTES: usize = 744;
const HEADER: usize = 104;
const PATTERN: &str = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
const DOMAIN: &[u8] = b"qiangjian-connect/telephony-audio/v1";
const POLL: Duration = Duration::from_millis(50);
const STALL: Duration = Duration::from_secs(2);
fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> { value.lock().unwrap_or_else(|p| p.into_inner()) }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role { Phone, Pad }
impl Role { fn wire(self) -> u8 { if self == Self::Phone { 0 } else { 1 } } }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error { InvalidScope, AlreadyUsed, Capacity, Authentication, Protocol, Transport, Backpressure, Revoked, Expired, Closed }
impl std::fmt::Display for Error { fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { write!(f, "{self:?}") } }
impl std::error::Error for Error {}
/// One-use secret transferred only through the authenticated call-control session. Never serialize/log.
pub struct OneTimeKey([u8; 32]);
impl OneTimeKey {
    pub fn generate() -> Result<Self, Error> {
        let mut key = [0; 32]; getrandom::getrandom(&mut key).map_err(|_| Error::Authentication)?; Ok(Self(key))
    }
    pub fn from_authenticated_control(key: [u8; 32]) -> Result<Self, Error> {
        if key == [0; 32] { return Err(Error::InvalidScope); } Ok(Self(key))
    }
    pub fn for_authenticated_control(&self) -> [u8; 32] { self.0 }
}
impl Drop for OneTimeKey { fn drop(&mut self) { self.0.fill(0); } }
struct Authorization {
    session: [u8; 32], phone: [u8; 32], pad: [u8; 32], ended: AtomicBool,
    ledger: Mutex<Ledger>,
}
#[derive(Default)]
struct Ledger { streams: HashSet<[u8; 16]>, keys: HashSet<[u8; 32]>, leases: Vec<Weak<Lease>> }
/// Retain exactly one per real confirmed control session; no reconstructing it to erase used IDs.
#[derive(Clone)]
pub struct SessionAuthorization(Arc<Authorization>);
impl SessionAuthorization {
    pub fn confirmed(session: [u8; 32], phone: [u8; 32], pad: [u8; 32]) -> Result<Self, Error> {
        if session == [0; 32] || phone == [0; 32] || pad == [0; 32] || phone == pad { return Err(Error::InvalidScope); }
        Ok(Self(Arc::new(Authorization { session, phone, pad, ended: AtomicBool::new(false), ledger: Mutex::new(Ledger::default()) })))
    }
    /// Call only AFTER the exact active native Call/Connection's separate local and remote approvals.
    /// This method records those decisions; its existence is not evidence that either decision occurred.
    pub fn approve_native_call(&self, call: &str, stream: [u8; 16], role: Role, key: OneTimeKey, ttl: Duration) -> Result<AudioGrant, Error> {
        if self.0.ended.load(Ordering::Acquire) { return Err(Error::Revoked); }
        if call.is_empty() || call.len() > 96 || !call.bytes().all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
            || stream == [0; 16] || ttl.is_zero() || ttl > Duration::from_secs(30) { return Err(Error::InvalidScope); }
        let call_hash: [u8; 32] = Sha256::digest(call.as_bytes()).into();
        let key_hash: [u8; 32] = Sha256::digest(key.0).into();
        let mut ledger = lock(&self.0.ledger);
        if ledger.streams.contains(&stream) || ledger.keys.contains(&key_hash) { return Err(Error::AlreadyUsed); }
        if ledger.streams.len() >= 64 { return Err(Error::Capacity); }
        ledger.streams.insert(stream); ledger.keys.insert(key_hash);
        let lease = Arc::new(Lease { authorization: self.0.clone(), stream, call_hash, role,
            active: AtomicBool::new(true), deadline: Mutex::new(Instant::now() + ttl) });
        ledger.leases.push(Arc::downgrade(&lease));
        Ok(AudioGrant { lease, key })
    }
    /// Refresh only after revalidating both approvals and this same current native active call.
    pub fn renew_native_call(&self, stream: [u8; 16], ttl: Duration) -> Result<(), Error> {
        if ttl.is_zero() || ttl > Duration::from_secs(30) { return Err(Error::InvalidScope); }
        let ledger = lock(&self.0.ledger);
        let lease = ledger.leases.iter().filter_map(Weak::upgrade).find(|l| l.stream == stream).ok_or(Error::Closed)?;
        let mut deadline = lock(&lease.deadline);
        if !lease.active.load(Ordering::Acquire) || self.0.ended.load(Ordering::Acquire) { return Err(Error::Revoked); }
        if Instant::now() >= *deadline { lease.active.store(false, Ordering::Release); return Err(Error::Expired); }
        *deadline = Instant::now() + ttl; Ok(())
    }
    pub fn revoke_native_call(&self, stream: [u8; 16]) {
        for lease in lock(&self.0.ledger).leases.iter().filter_map(Weak::upgrade).filter(|l| l.stream == stream) {
            lease.active.store(false, Ordering::Release);
        }
    }
    pub fn close(&self) { self.0.ended.store(true, Ordering::Release); }
}
struct Lease { authorization: Arc<Authorization>, stream: [u8; 16], call_hash: [u8; 32], role: Role, active: AtomicBool, deadline: Mutex<Instant> }
impl Lease {
    fn check(&self) -> Result<(), Error> {
        if !self.active.load(Ordering::Acquire) || self.authorization.ended.load(Ordering::Acquire) { return Err(Error::Revoked); }
        if Instant::now() >= *lock(&self.deadline) { self.active.store(false, Ordering::Release); return Err(Error::Expired); }
        Ok(())
    }
    fn prologue(&self) -> Vec<u8> {
        let mut out = DOMAIN.to_vec(); out.extend(self.authorization.session); out.extend(self.authorization.phone);
        out.extend(self.authorization.pad); out.extend(self.stream); out.extend(self.call_hash); out
    }
    fn packet(&self, raw: &[u8], role: u8, sequence: u64) -> Result<(), Error> {
        self.check()?;
        if raw.len() != PACKET_BYTES || &raw[..4] != b"QJTA" || raw[4] != 1 || raw[5] != role || raw[6..8] != [0, 0]
            || raw[8..40] != self.authorization.session || raw[40..56] != self.stream || raw[56..88] != self.call_hash
            || u64::from_be_bytes(raw[88..96].try_into().map_err(|_| Error::Protocol)?) != sequence
            || u64::from_be_bytes(raw[96..HEADER].try_into().map_err(|_| Error::Protocol)?) != sequence.checked_mul(20_000).ok_or(Error::Protocol)? {
            return Err(Error::Protocol);
        }
        Ok(())
    }
}
pub struct AudioGrant { lease: Arc<Lease>, key: OneTimeKey }
struct Shared { lease: Arc<Lease>, socket: TcpStream, reason: Mutex<Option<Error>> }
impl Shared {
    fn check(&self) -> Result<(), Error> {
        if let Some(reason) = *lock(&self.reason) { return Err(reason); }
        self.lease.check()
    }
    fn stop(&self, error: Error) {
        let mut reason = lock(&self.reason); if reason.is_some() { return; } *reason = Some(error);
        self.lease.active.store(false, Ordering::Release); let _ = self.socket.shutdown(Shutdown::Both);
    }
}
/// Nonblocking producer/poll interface. Five frames per direction INCLUDING the in-flight record.
pub struct AudioChannel {
    shared: Arc<Shared>, outbound: mpsc::SyncSender<Vec<u8>>, inbound: mpsc::Receiver<Vec<u8>>,
    sequence: Mutex<u64>, threads: Vec<JoinHandle<()>>,
}
impl AudioChannel {
    /// Establish on a worker with the authenticated control peer's selected socket, never on Binder/main.
    pub fn establish(mut socket: TcpStream, grant: AudioGrant) -> Result<Self, Error> {
        grant.lease.check()?;
        socket.set_read_timeout(Some(POLL)).map_err(|_| Error::Transport)?;
        socket.set_write_timeout(Some(POLL)).map_err(|_| Error::Transport)?;
        socket.set_nodelay(true).map_err(|_| Error::Transport)?;
        let builder = snow::Builder::new(PATTERN.parse().map_err(|_| Error::Authentication)?)
            .psk(0, &grant.key.0);
        let prologue = grant.lease.prologue(); let builder = builder.prologue(&prologue);
        let mut handshake = if grant.lease.role == Role::Phone { builder.build_initiator() } else { builder.build_responder() }
            .map_err(|_| Error::Authentication)?;
        let mut buffer = [0; 256];
        let handshake_result = (|| {
            if grant.lease.role == Role::Phone {
                let count = handshake.write_message(&[], &mut buffer).map_err(|_| Error::Authentication)?;
                write_record(&mut socket, &buffer[..count], &grant.lease)?;
                let message = read_record(&mut socket, &grant.lease, 256)?;
                if handshake.read_message(&message, &mut buffer).map_err(|_| Error::Authentication)? != 0 { return Err(Error::Authentication); }
            } else {
                let message = read_record(&mut socket, &grant.lease, 256)?;
                if handshake.read_message(&message, &mut buffer).map_err(|_| Error::Authentication)? != 0 { return Err(Error::Authentication); }
                let count = handshake.write_message(&[], &mut buffer).map_err(|_| Error::Authentication)?;
                write_record(&mut socket, &buffer[..count], &grant.lease)?;
            }
            Ok(())
        })();
        if let Err(error) = handshake_result { grant.lease.active.store(false, Ordering::Release); let _ = socket.shutdown(Shutdown::Both); return Err(error); }
        let transport = Arc::new(Mutex::new(handshake.into_transport_mode().map_err(|_| Error::Authentication)?));
        let shared = Arc::new(Shared { lease: grant.lease.clone(), socket: socket.try_clone().map_err(|_| Error::Transport)?, reason: Mutex::new(None) });
        let mut read_socket = socket.try_clone().map_err(|_| Error::Transport)?;
        let (send, outgoing) = mpsc::sync_channel::<Vec<u8>>(4); let (incoming, receive) = mpsc::sync_channel::<Vec<u8>>(4);
        let writer_state = shared.clone(); let writer_crypto = transport.clone();
        let writer = thread::spawn(move || {
            let mut cipher = [0; PACKET_BYTES + 16];
            loop {
                if let Err(e) = writer_state.check() { writer_state.stop(e); break; }
                let mut packet = match outgoing.recv_timeout(POLL) {
                    Ok(packet) => packet, Err(mpsc::RecvTimeoutError::Timeout) => continue,
                    Err(mpsc::RecvTimeoutError::Disconnected) => { writer_state.stop(Error::Closed); break; }
                };
                let encrypted = lock(&writer_crypto).write_message(&packet, &mut cipher).map_err(|_| Error::Authentication);
                packet.fill(0);
                let result = encrypted.and_then(|n| write_record(&mut socket, &cipher[..n], &writer_state.lease));
                cipher.fill(0);
                if let Err(e) = result { writer_state.stop(e); break; }
            }
            while let Ok(mut packet) = outgoing.try_recv() { packet.fill(0); }
        });
        let reader_state = shared.clone();
        let reader = thread::spawn(move || {
            let mut sequence = 0; let mut plain = [0; PACKET_BYTES];
            loop {
                if let Err(e) = reader_state.check() { reader_state.stop(e); break; }
                let result = read_record(&mut read_socket, &reader_state.lease, PACKET_BYTES + 16).and_then(|cipher| {
                    if cipher.len() != PACKET_BYTES + 16 { return Err(Error::Protocol); }
                    let n = lock(&transport).read_message(&cipher, &mut plain).map_err(|_| Error::Authentication)?;
                    reader_state.lease.packet(&plain[..n], 1 - reader_state.lease.role.wire(), sequence)?;
                    match incoming.try_send(plain.to_vec()) {
                        Ok(()) => {}
                        Err(mpsc::TrySendError::Full(mut p)) => { p.fill(0); return Err(Error::Backpressure); }
                        Err(mpsc::TrySendError::Disconnected(mut p)) => { p.fill(0); return Err(Error::Closed); }
                    }
                    sequence = sequence.checked_add(1).ok_or(Error::Protocol)?; Ok(())
                });
                plain.fill(0);
                if let Err(e) = result { reader_state.stop(e); break; }
            }
        });
        Ok(Self { shared, outbound: send, inbound: receive, sequence: Mutex::new(0), threads: vec![writer, reader] })
    }
    pub fn offer(&self, packet: &[u8]) -> Result<(), Error> {
        self.shared.check()?; let mut sequence = lock(&self.sequence);
        if let Err(e) = self.shared.lease.packet(packet, self.shared.lease.role.wire(), *sequence) { self.shared.stop(e); return Err(e); }
        match self.outbound.try_send(packet.to_vec()) {
            Ok(()) => { *sequence = sequence.checked_add(1).ok_or(Error::Protocol)?; Ok(()) }
            Err(mpsc::TrySendError::Full(mut packet)) => { packet.fill(0); self.shared.stop(Error::Backpressure); Err(Error::Backpressure) }
            Err(mpsc::TrySendError::Disconnected(mut packet)) => { packet.fill(0); self.shared.stop(Error::Closed); Err(Error::Closed) }
        }
    }
    pub fn poll(&mut self) -> Result<Option<Vec<u8>>, Error> { self.shared.check()?; Ok(self.inbound.try_recv().ok()) }
    pub fn status(&self) -> Result<(), Error> { self.shared.check() }
    pub fn close(&self) { self.shared.stop(Error::Closed); }
}
impl Drop for AudioChannel {
    fn drop(&mut self) {
        self.close(); for worker in self.threads.drain(..) { let _ = worker.join(); }
        while let Ok(mut packet) = self.inbound.try_recv() { packet.fill(0); }
    }
}
fn io_error(error: std::io::Error) -> Error { if error.kind() == std::io::ErrorKind::UnexpectedEof { Error::Closed } else { Error::Transport } }
fn read_fully(socket: &mut TcpStream, bytes: &mut [u8], lease: &Lease) -> Result<(), Error> {
    let end = Instant::now() + STALL; let mut offset = 0;
    while offset < bytes.len() {
        lease.check()?; if Instant::now() >= end { return Err(Error::Transport); }
        match socket.read(&mut bytes[offset..]) {
            Ok(0) => return Err(Error::Closed), Ok(n) => offset += n,
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut | std::io::ErrorKind::Interrupted) => {}
            Err(e) => return Err(io_error(e)),
        }
    }
    Ok(())
}
fn read_record(socket: &mut TcpStream, lease: &Lease, max: usize) -> Result<Vec<u8>, Error> {
    let mut header = [0; 2]; read_fully(socket, &mut header, lease)?; let count = u16::from_be_bytes(header) as usize;
    if count == 0 || count > max { return Err(Error::Protocol); }
    let mut bytes = vec![0; count]; read_fully(socket, &mut bytes, lease)?; Ok(bytes)
}
fn write_record(socket: &mut TcpStream, bytes: &[u8], lease: &Lease) -> Result<(), Error> {
    let header = (bytes.len() as u16).to_be_bytes(); let end = Instant::now() + STALL;
    for part in [&header[..], bytes] {
        let mut offset = 0;
        while offset < part.len() {
            lease.check()?; if Instant::now() >= end { return Err(Error::Transport); }
            match socket.write(&part[offset..]) {
                Ok(0) => return Err(Error::Closed), Ok(n) => offset += n,
                Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut | std::io::ErrorKind::Interrupted) => {}
                Err(e) => return Err(io_error(e)),
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    fn auth() -> SessionAuthorization { SessionAuthorization::confirmed([1;32],[2;32],[3;32]).unwrap() }
    fn key(value: u8) -> OneTimeKey { OneTimeKey::from_authenticated_control([value;32]).unwrap() }
    fn pair() -> (SessionAuthorization, SessionAuthorization, AudioChannel, AudioChannel) {
        let phone = auth(); let pad = auth(); let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let grant = phone.approve_native_call("synthetic_call",[4;16],Role::Phone,key(5),Duration::from_secs(5)).unwrap();
        let responder = pad.approve_native_call("synthetic_call",[4;16],Role::Pad,key(5),Duration::from_secs(5)).unwrap();
        let joining = thread::spawn(move || AudioChannel::establish(listener.accept().unwrap().0, responder).unwrap());
        let channel = AudioChannel::establish(TcpStream::connect(address).unwrap(), grant).unwrap();
        (phone,pad,channel,joining.join().unwrap())
    }
    fn packet(role: Role, sequence: u64) -> Vec<u8> {
        let mut packet = vec![0x2a;PACKET_BYTES]; packet[..4].copy_from_slice(b"QJTA");packet[4]=1;packet[5]=role.wire();packet[6..8].fill(0);
        packet[8..40].copy_from_slice(&[1;32]);packet[40..56].copy_from_slice(&[4;16]);
        packet[56..88].copy_from_slice(&Sha256::digest(b"synthetic_call"));packet[88..96].copy_from_slice(&sequence.to_be_bytes());
        packet[96..104].copy_from_slice(&(sequence*20_000).to_be_bytes());packet
    }
    fn take(channel: &mut AudioChannel) -> Vec<u8> {
        let end=Instant::now()+Duration::from_secs(1);
        loop { if let Some(p)=channel.poll().unwrap(){return p;}assert!(Instant::now()<end);thread::sleep(Duration::from_millis(2)); }
    }
    #[test]
    fn real_duplex_noise_exchanges_exact_audio_packets() {
        let (_a,_b,mut phone,mut pad)=pair();let down=packet(Role::Phone,0);let up=packet(Role::Pad,0);
        phone.offer(&down).unwrap();pad.offer(&up).unwrap();assert_eq!(take(&mut pad),down);assert_eq!(take(&mut phone),up);
    }
    #[test]
    fn call_and_direction_binding_prevent_stream_reflection() {
        let (_a,_b,phone,_pad)=pair();assert_eq!(phone.offer(&packet(Role::Pad,0)),Err(Error::Protocol));
        let (_a,_b,phone,_pad)=pair();let mut raw=packet(Role::Phone,0);raw[56]^=1;assert_eq!(phone.offer(&raw),Err(Error::Protocol));
    }
    #[test]
    fn authenticated_audio_replay_closes_the_lane() {
        let (_a,_b,phone,mut pad)=pair();let raw=packet(Role::Phone,0);phone.offer(&raw).unwrap();take(&mut pad);
        assert_eq!(phone.offer(&raw),Err(Error::Protocol));assert_eq!(phone.status(),Err(Error::Protocol));
    }
    #[test]
    fn revocation_cannot_be_renewed_or_reused() {
        let (auth,_b,phone,_pad)=pair();auth.revoke_native_call([4;16]);assert_eq!(phone.offer(&packet(Role::Phone,0)),Err(Error::Revoked));
        assert_eq!(auth.renew_native_call([4;16],Duration::from_secs(5)),Err(Error::Revoked));
        assert!(matches!(auth.approve_native_call("synthetic_call",[4;16],Role::Phone,key(6),Duration::from_secs(1)),Err(Error::AlreadyUsed)));
    }
    #[test]
    fn expired_native_decision_cannot_be_revived() {
        let auth=auth();let _grant=auth.approve_native_call("synthetic_call",[4;16],Role::Phone,key(5),Duration::from_millis(2)).unwrap();
        thread::sleep(Duration::from_millis(5));assert_eq!(auth.renew_native_call([4;16],Duration::from_secs(1)),Err(Error::Expired));
        assert_eq!(auth.renew_native_call([4;16],Duration::from_secs(1)),Err(Error::Revoked));
    }
    #[test]
    fn key_scope_and_grant_tombstones_are_bounded() {
        let auth=auth();assert!(matches!(auth.approve_native_call("bad call",[4;16],Role::Phone,key(5),Duration::from_secs(1)),Err(Error::InvalidScope)));
        for i in 1..=64 {let mut stream=[0;16];stream[0]=i;let _g=auth.approve_native_call("synthetic_call",stream,Role::Phone,key(i),Duration::from_secs(1)).unwrap();}
        assert!(matches!(auth.approve_native_call("synthetic_call",[100;16],Role::Phone,key(100),Duration::from_secs(1)),Err(Error::Capacity)));
    }
    #[test]
    fn wrong_control_key_cannot_establish_audio() {
        let listener=TcpListener::bind("127.0.0.1:0").unwrap();let address=listener.local_addr().unwrap();
        let initiator=auth().approve_native_call("synthetic_call",[4;16],Role::Phone,key(5),Duration::from_secs(5)).unwrap();
        let responder=auth().approve_native_call("synthetic_call",[4;16],Role::Pad,key(6),Duration::from_secs(5)).unwrap();
        let t=thread::spawn(move||AudioChannel::establish(listener.accept().unwrap().0,responder).is_err());
        assert!(AudioChannel::establish(TcpStream::connect(address).unwrap(),initiator).is_err());assert!(t.join().unwrap());
    }
    #[test]
    fn correct_key_for_another_call_cannot_establish_audio() {
        let listener=TcpListener::bind("127.0.0.1:0").unwrap();let address=listener.local_addr().unwrap();
        let initiator=auth().approve_native_call("synthetic_call",[4;16],Role::Phone,key(5),Duration::from_secs(5)).unwrap();
        let responder=auth().approve_native_call("another_call",[4;16],Role::Pad,key(5),Duration::from_secs(5)).unwrap();
        let t=thread::spawn(move||AudioChannel::establish(listener.accept().unwrap().0,responder).is_err());
        assert!(AudioChannel::establish(TcpStream::connect(address).unwrap(),initiator).is_err());assert!(t.join().unwrap());
    }
}
