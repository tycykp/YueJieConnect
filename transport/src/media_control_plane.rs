//! Media rendezvous messages travel exclusively inside the authenticated parent session.
//! Remote readiness never creates a local native grant.
use crate::media_channel::{Purpose, Role};
use crate::media_runtime::{MediaOffer, MediaRuntime, Phase, StreamStatus};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    net::SocketAddr,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

#[derive(Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum Signal {
    Ready {
        stream: u64,
        purpose: Purpose,
        role: Role,
    },
    Offer {
        stream: u64,
        purpose: Purpose,
        port: u16,
        key: [u8; 32],
        lifetime_ms: u64,
    },
    Close {
        stream: u64,
    },
}
impl Signal {
    pub fn stream(&self) -> u64 {
        match self {
            Self::Ready { stream, .. } | Self::Offer { stream, .. } | Self::Close { stream } => {
                *stream
            }
        }
    }
}
pub struct ControlPlane {
    session: [u8; 32],
    runtime: Arc<MediaRuntime>,
    remote: Mutex<BTreeMap<u64, (Purpose, Role, Instant)>>,
}
impl ControlPlane {
    pub fn confirmed(
        session: [u8; 32],
        local: [u8; 32],
        peer: [u8; 32],
        address: SocketAddr,
    ) -> Result<Self, &'static str> {
        let (runtime, _events) = MediaRuntime::confirmed(session, local, peer, address)?;
        Ok(Self {
            session,
            runtime: Arc::new(runtime),
            remote: Mutex::new(BTreeMap::new()),
        })
    }
    pub fn register(
        &self,
        stream: u64,
        purpose: Purpose,
        role: Role,
        lifetime: Duration,
    ) -> Result<Vec<Signal>, &'static str> {
        self.runtime
            .register_local(stream, purpose, role, lifetime)?;
        let mut signals = vec![Signal::Ready {
            stream,
            purpose,
            role,
        }];
        if role == Role::Sender {
            let ready = self.remote.lock().unwrap().get(&stream).copied();
            if let Some((p, r, at)) = ready {
                if p != purpose || r != Role::Receiver {
                    self.runtime.close_stream(stream)?;
                    return Err("media_role_mismatch");
                }
                if at.elapsed() < Duration::from_secs(8) {
                    signals.push(self.offer(stream)?);
                }
            }
        }
        Ok(signals)
    }
    fn offer(&self, stream: u64) -> Result<Signal, &'static str> {
        let offer = self.runtime.listen_offer(stream)?;
        Ok(Signal::Offer {
            stream,
            purpose: offer.purpose(),
            port: offer.port(),
            key: offer.key_for_authenticated_control(),
            lifetime_ms: offer.lifetime().as_millis() as u64,
        })
    }
    pub fn receive(&self, signal: Signal) -> Result<Vec<Signal>, &'static str> {
        let stream = signal.stream();
        if stream == 0 || stream > i64::MAX as u64 {
            return Err("media_scope_invalid");
        }
        match signal {
            Signal::Ready { purpose, role, .. } => {
                let mut remote = self.remote.lock().unwrap();
                if remote.contains_key(&stream) {
                    return Err("media_stream_reused");
                }
                if remote.len() >= 64 {
                    return Err("media_history_capacity");
                }
                remote.insert(stream, (purpose, role, Instant::now()));
                drop(remote);
                if let Ok(local) = self.runtime.status(stream) {
                    if local.purpose != purpose || local.role == role {
                        return Err("media_role_mismatch");
                    }
                    if local.role == Role::Sender && local.phase == Phase::Registered {
                        return Ok(vec![self.offer(stream)?]);
                    }
                }
                Ok(vec![])
            }
            Signal::Offer {
                purpose,
                port,
                mut key,
                lifetime_ms,
                ..
            } => {
                let status = self.runtime.status(stream)?;
                if status.role != Role::Receiver || status.purpose != purpose {
                    key.fill(0);
                    return Err("media_role_mismatch");
                }
                let offer = MediaOffer::from_authenticated_control(
                    self.session,
                    stream,
                    purpose,
                    port,
                    key,
                    Duration::from_millis(lifetime_ms),
                );
                key.fill(0);
                self.runtime.accept_offer(offer?)?;
                Ok(vec![])
            }
            Signal::Close { .. } => {
                self.runtime.close_stream(stream)?;
                Ok(vec![])
            }
        }
    }
    pub fn status(&self, stream: u64) -> Result<StreamStatus, &'static str> {
        self.runtime.status(stream)
    }
    pub fn close(&self, stream: u64) -> Result<(), &'static str> {
        self.runtime.close_stream(stream)
    }
    pub fn tick(&self) {
        self.runtime.tick();
    }
    pub fn end(&self) {
        self.runtime.close_session();
    }
    #[cfg(unix)]
    pub fn attach(&self, stream: u64, fd: std::os::fd::OwnedFd) -> Result<(), &'static str> {
        self.runtime.attach_local_pipe(stream, fd)
    }
}
impl Drop for ControlPlane {
    fn drop(&mut self) {
        self.end();
    }
}
