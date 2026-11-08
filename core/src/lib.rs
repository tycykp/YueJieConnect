//! Passive native discovery -> authenticated local IPC -> unified device catalog.
//! Observed vendor bitmaps are opaque evidence, never authorization or capabilities.
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

pub mod gateway;

pub const MAX_INPUT: usize = 8192;
const LEASE_MS: u64 = 300_000;
const DEVICE_TTL_MS: u64 = 120_000;
const MAX_SESSIONS: usize = 16;
const MAX_DEVICES: usize = 256;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Source {
    HyperOs4,
    ColorOs17,
}

#[derive(Debug, Clone, Copy)]
pub struct Caller {
    pub uid: u32,
    pub pid: u32,
    pub owner: bool,
    // Set by Android's verified UID/package/version/certificate policy, not JSON.
    pub source: Option<Source>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Observation {
    pub kind: EventKind,
    pub native_id: String,
    pub name: String,
    pub device_type: String,
    pub capability: i64,
    #[serde(default)]
    pub capability_v2: i64,
    #[serde(default)]
    pub capability_v3: i64,
    #[serde(default)]
    pub service_id: String,
}
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventKind {
    Found,
    Changed,
    Lost,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    SetEnabled {
        enabled: bool,
    },
    Open,
    Observe {
        token: String,
        sequence: u64,
        event: Observation,
    },
    Catalog,
    Request {
        capability: String,
    },
}

struct Session {
    uid: u32,
    pid: u32,
    source: Source,
    deadline: u64,
    sequence: u64,
    // Ephemeral salt; identifiers cannot be correlated after a session ends.
    salt: [u8; 32],
}
#[derive(Clone, Serialize)]
pub struct Device {
    pub id: String,
    pub source: Source,
    pub name: String,
    pub vendor_device_type: String,
    pub state: &'static str,
    pub last_seen_elapsed_ms: u64,
    pub expires_elapsed_ms: u64,
    pub vendor_capabilities: [i64; 3],
    pub capabilities: Vec<Capability>,
    #[serde(skip)]
    session: String,
}
#[derive(Clone, Serialize)]
pub struct Capability {
    pub name: &'static str,
    pub status: &'static str,
    pub reason: &'static str,
}

#[derive(Default)]
pub struct Engine {
    enabled: bool,
    sessions: BTreeMap<String, Session>,
    devices: BTreeMap<String, Device>,
}

pub fn error(code: &str) -> Value {
    json!({"status":"error","code":code})
}

impl Engine {
    /// Shared authentication for gateway IPC. Identity comes from Binder, never JSON.
    pub fn authorize(
        &mut self,
        caller: Caller,
        now: u64,
        token: &str,
        sequence: u64,
    ) -> Result<(), &'static str> {
        self.expire(now);
        if !self.enabled {
            return Err("consent_required");
        }
        let s = self.sessions.get_mut(token).ok_or("invalid_session")?;
        if s.uid != caller.uid || s.pid != caller.pid || Some(s.source) != caller.source {
            return Err("forbidden");
        }
        if sequence == 0 || sequence <= s.sequence {
            return Err("replay");
        }
        s.sequence = sequence;
        Ok(())
    }
    pub fn handle(&mut self, caller: Caller, now: u64, input: &str) -> Value {
        self.expire(now);
        if input.len() > MAX_INPUT {
            return error("payload_too_large");
        }
        let Ok(command) = serde_json::from_str::<Command>(input) else {
            return error("invalid_request");
        };
        match command {
            Command::SetEnabled { enabled } if caller.owner => {
                // Revoking also erases observations and all IPC leases.
                self.enabled = enabled;
                self.sessions.clear();
                self.devices.clear();
                json!({"status":"ok", "enabled":enabled})
            }
            Command::Catalog if caller.owner => json!({
                "status":"ok", "schema":1, "enabled":self.enabled,
                "business":"local_device_catalog", "native_interoperability":"unverified",
                "sessions":self.sessions.len(), "devices":self.devices.values().collect::<Vec<_>>()
            }),
            Command::Request { capability } if caller.owner => {
                if capability == "local_device_catalog" {
                    self.handle(caller, now, r#"{"op":"catalog"}"#)
                } else {
                    json!({"status":"unsupported","code":"native_protocol_not_integrated",
                        "capability":capability,"reason":"Lyra/SCP authentication and native business routing are not implemented"})
                }
            }
            Command::Open => self.open(caller, now),
            Command::Observe {
                token,
                sequence,
                event,
            } => self.observe(caller, now, token, sequence, event),
            _ => error("forbidden"),
        }
    }

    fn expire(&mut self, now: u64) {
        self.sessions.retain(|_, s| now < s.deadline);
        self.devices
            .retain(|_, d| now < d.expires_elapsed_ms && self.sessions.contains_key(&d.session));
    }

    fn open(&mut self, caller: Caller, now: u64) -> Value {
        let Some(source) = caller.source else {
            return error("forbidden");
        };
        if !self.enabled {
            return error("consent_required");
        }
        let mut random = [0u8; 64];
        if getrandom::getrandom(&mut random).is_err() {
            return error("entropy_unavailable");
        }
        // One lease per verified process. Re-opening invalidates the previous lease.
        self.sessions
            .retain(|_, s| !(s.uid == caller.uid && s.pid == caller.pid));
        self.expire(now);
        if self.sessions.len() >= MAX_SESSIONS {
            return error("session_limit");
        }
        let token = hex(&random[..32]);
        let mut salt = [0u8; 32];
        salt.copy_from_slice(&random[32..]);
        self.sessions.insert(
            token.clone(),
            Session {
                uid: caller.uid,
                pid: caller.pid,
                source,
                deadline: now.saturating_add(LEASE_MS),
                sequence: 0,
                salt,
            },
        );
        json!({"status":"ok","token":token,"lease_ms":LEASE_MS})
    }

    fn observe(
        &mut self,
        caller: Caller,
        now: u64,
        token: String,
        sequence: u64,
        event: Observation,
    ) -> Value {
        if !self.enabled {
            return error("consent_required");
        }
        let Some(s) = self.sessions.get_mut(&token) else {
            return error("invalid_session");
        };
        if s.uid != caller.uid || s.pid != caller.pid || Some(s.source) != caller.source {
            return error("forbidden");
        }
        if sequence == 0 || sequence <= s.sequence {
            return error("replay");
        }
        if !valid_text(&event.native_id, 256, false)
            || !valid_text(&event.name, 128, true)
            || !valid_text(&event.device_type, 64, false)
            || !valid_text(&event.service_id, 64, true)
        {
            return error("invalid_event");
        }
        if s.source == Source::HyperOs4 && event.service_id != "00070A2A" {
            return error("unsupported_service");
        }
        if s.source == Source::ColorOs17 && !event.service_id.is_empty() {
            return error("invalid_event");
        }
        let mut hash = Sha256::new();
        hash.update(s.salt);
        hash.update(event.native_id.as_bytes());
        let id = hex(&hash.finalize());
        if !matches!(event.kind, EventKind::Lost)
            && !self.devices.contains_key(&id)
            && self.devices.len() >= MAX_DEVICES
        {
            return error("device_limit");
        }
        s.sequence = sequence;
        if matches!(event.kind, EventKind::Lost) {
            self.devices.remove(&id);
        } else {
            self.devices.insert(
                id.clone(),
                Device {
                    id: id.clone(),
                    source: s.source,
                    name: event.name,
                    vendor_device_type: event.device_type,
                    state: "observed",
                    last_seen_elapsed_ms: now,
                    expires_elapsed_ms: now.saturating_add(DEVICE_TTL_MS).min(s.deadline),
                    vendor_capabilities: [
                        event.capability,
                        event.capability_v2,
                        event.capability_v3,
                    ],
                    capabilities: vec![
                        Capability {
                            name: "local_device_catalog",
                            status: "available",
                            reason: "observed_native_callback",
                        },
                        Capability {
                            name: "native_pairing",
                            status: "unsupported",
                            reason: "authentication_not_integrated",
                        },
                        Capability {
                            name: "native_file_transfer",
                            status: "unsupported",
                            reason: "business_channel_not_integrated",
                        },
                    ],
                    session: token,
                },
            );
        }
        json!({"status":"ok","meaning":"observation_accepted","device_id":id})
    }
}

fn valid_text(s: &str, max: usize, empty: bool) -> bool {
    (empty || !s.is_empty()) && s.len() <= max && !s.chars().any(char::is_control)
}
fn hex(data: &[u8]) -> String {
    data.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    const OWNER: Caller = Caller {
        uid: 10,
        pid: 10,
        owner: true,
        source: None,
    };
    const HOOK: Caller = Caller {
        uid: 20,
        pid: 21,
        owner: false,
        source: Some(Source::HyperOs4),
    };
    fn send(e: &mut Engine, c: Caller, t: u64, v: Value) -> Value {
        e.handle(c, t, &v.to_string())
    }
    fn start(e: &mut Engine) -> String {
        send(e, OWNER, 0, json!({"op":"set_enabled","enabled":true}));
        send(e, HOOK, 0, json!({"op":"open"}))["token"]
            .as_str()
            .unwrap()
            .to_owned()
    }
    fn obs(token: &str, sequence: u64, kind: &str) -> Value {
        json!({"op":"observe","token":token,"sequence":sequence,"event":{
            "kind":kind,"native_id":"private-id","name":"测试设备","device_type":"3",
            "capability":2147483647,"service_id":"00070A2A"}})
    }
    #[test]
    fn consent_identity_sequence_and_revoke() {
        let mut e = Engine::default();
        assert_eq!(
            send(&mut e, HOOK, 0, json!({"op":"open"}))["code"],
            "consent_required"
        );
        let token = start(&mut e);
        assert_eq!(
            send(
                &mut e,
                Caller { pid: 22, ..HOOK },
                1,
                obs(&token, 1, "found")
            )["code"],
            "forbidden"
        );
        assert_eq!(
            send(&mut e, HOOK, 2, obs(&token, 1, "found"))["status"],
            "ok"
        );
        assert_eq!(
            send(&mut e, HOOK, 3, obs(&token, 1, "found"))["code"],
            "replay"
        );
        assert_eq!(
            send(&mut e, HOOK, 4, json!({"op":"catalog"}))["code"],
            "forbidden"
        );
        let catalog = send(&mut e, OWNER, 4, json!({"op":"catalog"}));
        assert!(!catalog.to_string().contains("private-id"));
        assert_eq!(
            catalog["devices"][0]["capabilities"][1]["status"],
            "unsupported"
        );
        send(
            &mut e,
            OWNER,
            5,
            json!({"op":"set_enabled","enabled":false}),
        );
        assert_eq!(
            send(&mut e, HOOK, 6, obs(&token, 2, "found"))["code"],
            "consent_required"
        );
        assert!(e.devices.is_empty());
    }
    #[test]
    fn lost_expiry_and_restart_invalidate_state() {
        let mut e = Engine::default();
        let token = start(&mut e);
        send(&mut e, HOOK, 1, obs(&token, 1, "found"));
        send(&mut e, HOOK, 2, obs(&token, 2, "lost"));
        assert!(e.devices.is_empty());
        send(&mut e, HOOK, 3, obs(&token, 3, "found"));
        send(&mut e, OWNER, DEVICE_TTL_MS + 3, json!({"op":"catalog"}));
        assert!(e.devices.is_empty());
        assert_eq!(
            send(&mut e, HOOK, LEASE_MS, obs(&token, 4, "found"))["code"],
            "invalid_session"
        );
        assert_eq!(
            send(&mut Engine::default(), HOOK, 1, obs(&token, 4, "found"))["code"],
            "consent_required"
        );
    }
    #[test]
    fn untrusted_unknown_and_oversized_inputs_fail_closed() {
        let mut e = Engine::default();
        let token = start(&mut e);
        assert_eq!(
            send(&mut e, HOOK, 1, json!({"op":"set_enabled","enabled":true}))["code"],
            "forbidden"
        );
        assert_eq!(
            e.handle(OWNER, 1, &"x".repeat(MAX_INPUT + 1))["code"],
            "payload_too_large"
        );
        let mut v = obs(&token, 1, "found");
        v["event"]["same_account"] = json!(true);
        assert_eq!(send(&mut e, HOOK, 1, v)["code"], "invalid_request");
        let mut v = obs(&token, 1, "found");
        v["event"]["service_id"] = json!("other");
        assert_eq!(send(&mut e, HOOK, 1, v)["code"], "unsupported_service");
        assert_eq!(
            send(
                &mut e,
                OWNER,
                2,
                json!({"op":"request","capability":"native_file_transfer"})
            )["status"],
            "unsupported"
        );
    }
    #[test]
    fn coloros_and_xiaomi_never_merge_identity_or_infer_pairing() {
        let mut e = Engine::default();
        let t = start(&mut e);
        send(&mut e, HOOK, 1, obs(&t, 1, "found"));
        let c = Caller {
            uid: 30,
            pid: 31,
            source: Some(Source::ColorOs17),
            ..HOOK
        };
        let t = send(&mut e, c, 1, json!({"op":"open"}))["token"]
            .as_str()
            .unwrap()
            .to_owned();
        let mut v = obs(&t, 1, "found");
        v["event"]["service_id"] = json!("");
        send(&mut e, c, 2, v);
        assert_eq!(e.devices.len(), 2);
        assert!(e.devices.values().all(|d| d.state == "observed"));
    }
}
