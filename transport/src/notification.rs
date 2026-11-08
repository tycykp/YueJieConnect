//! Strict notification-only fragment carrier. Execution and receipt semantics stay at the endpoint.
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Deserialize)]
#[serde(untagged)]
pub enum Fragment {
    Part(Part),
    Abort(Abort),
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Part {
    pub transfer_id: String,
    pub index: u32,
    pub count: u32,
    pub length: u32,
    pub sha256: String,
    pub data: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Abort {
    pub transfer_id: String,
    pub sha256: String,
    pub abort: bool,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case", deny_unknown_fields)]
pub enum Receipt {
    Buffered {
        transfer_id: String,
        index: u32,
        sha256: String,
    },
    Complete {
        transfer_id: String,
        index: u32,
        sha256: String,
        data: String,
    },
    Aborted {
        transfer_id: String,
        sha256: String,
    },
}
fn hex(s: &str, len: usize) -> bool {
    s.len() == len
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn transfer(s: &str) -> bool {
    s.len() == 36
        && s.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
            }
        })
}
pub fn parse(payload: &Value) -> Result<Fragment, &'static str> {
    let fragment: Fragment =
        serde_json::from_value(payload.clone()).map_err(|_| "invalid_payload")?;
    let valid = match &fragment {
        Fragment::Abort(p) => p.abort && transfer(&p.transfer_id) && hex(&p.sha256, 64),
        Fragment::Part(p) => {
            transfer(&p.transfer_id)
                && hex(&p.sha256, 64)
                && (1..=10240).contains(&p.length)
                && p.count == p.length.div_ceil(2048)
                && p.index < p.count
                && crate::executor::decode_base64(&p.data, 2048).is_ok_and(|bytes| {
                    bytes.len() == (p.length - p.index * 2048).min(2048) as usize
                })
        }
    };
    if valid {
        Ok(fragment)
    } else {
        Err("invalid_payload")
    }
}
impl Receipt {
    pub fn valid(&self) -> bool {
        let (id, hash, valid) = match self {
            Self::Buffered {
                transfer_id,
                index,
                sha256,
            } => (transfer_id, sha256, *index < 4),
            Self::Complete {
                transfer_id,
                index,
                sha256,
                data,
            } => (
                transfer_id,
                sha256,
                *index < 5
                    && crate::executor::decode_base64(data, 140)
                        .is_ok_and(|b| valid_native_receipt(&b)),
            ),
            Self::Aborted {
                transfer_id,
                sha256,
            } => (transfer_id, sha256, true),
        };
        transfer(id) && hex(hash, 64) && valid
    }
    pub fn matches_payload(&self, payload: &Value) -> bool {
        if !self.valid() {
            return false;
        }
        match (self, parse(payload)) {
            (
                Self::Buffered {
                    transfer_id,
                    index,
                    sha256,
                },
                Ok(Fragment::Part(p)),
            ) => {
                transfer_id == &p.transfer_id
                    && *index == p.index
                    && sha256 == &p.sha256
                    && p.index + 1 < p.count
            }
            (
                Self::Complete {
                    transfer_id,
                    index,
                    sha256,
                    ..
                },
                Ok(Fragment::Part(p)),
            ) => {
                transfer_id == &p.transfer_id
                    && *index == p.index
                    && sha256 == &p.sha256
                    && p.index + 1 == p.count
            }
            (
                Self::Aborted {
                    transfer_id,
                    sha256,
                },
                Ok(Fragment::Abort(p)),
            ) => transfer_id == &p.transfer_id && sha256 == &p.sha256,
            _ => false,
        }
    }
}

fn valid_native_receipt(bytes: &[u8]) -> bool {
    fn sized<'a>(rest: &mut &'a [u8], max: usize) -> Option<&'a [u8]> {
        let size = u32::from_be_bytes(rest.get(..4)?.try_into().ok()?) as usize;
        *rest = rest.get(4..)?;
        if size == 0 || size > max {
            return None;
        }
        let value = rest.get(..size)?;
        *rest = rest.get(size..)?;
        Some(value)
    }
    if bytes.get(..5) != Some(b"QJN1\x04") {
        return false;
    }
    let mut rest = &bytes[5..];
    let Some(key) = sized(&mut rest, 96) else {
        return false;
    };
    if !key
        .iter()
        .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(b))
    {
        return false;
    }
    let Some(revision) = rest
        .get(..8)
        .and_then(|v| v.try_into().ok())
        .map(i64::from_be_bytes)
    else {
        return false;
    };
    if revision < 1 {
        return false;
    }
    rest = &rest[8..];
    let Some(effect) = sized(&mut rest, 23) else {
        return false;
    };
    matches!(effect, b"posted" | b"removed" | b"pending_intent_finished") && rest.is_empty()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn part() -> Value {
        json!({"transfer_id":"11111111-2222-3333-4444-555555555555","index":0,"count":1,"length":2,"sha256":"a".repeat(64),"data":"aGk="})
    }
    #[test]
    fn exact_notification_fragments_bound_binder_budget_and_reject_aliases() {
        assert!(parse(&part()).is_ok());
        let mut p = part();
        p["count"] = json!(5);
        p["length"] = json!(10240);
        p["data"] = json!("AAAA".repeat(682) + "AAA=");
        assert!(parse(&p).is_ok());
        assert!(p.to_string().len() < 5000);
        p["index"] = json!(4);
        assert!(parse(&p).is_ok());
        for (field, bad) in [
            ("index", json!(5)),
            ("count", json!(6)),
            ("length", json!(10241)),
            ("data", json!("AB==")),
            ("extra", json!(true)),
            ("index", json!(0.0)),
            ("sha256", json!("A".repeat(64))),
        ] {
            let mut p = part();
            p[field] = bad;
            assert!(parse(&p).is_err(), "{field}");
        }
        assert!(parse(&json!({"transfer_id":"11111111-2222-3333-4444-555555555555","sha256":"a".repeat(64),"abort":true})).is_ok());
    }
    #[test]
    fn notification_receipts_bind_fragment_hash_index_and_terminal_state() {
        let payload = part();
        let mut receipt = Receipt::Complete {
            transfer_id: payload["transfer_id"].as_str().unwrap().into(),
            index: 0,
            sha256: "a".repeat(64),
            data: "UUpOMQQAAAABawAAAAAAAAABAAAABnBvc3RlZA==".into(),
        };
        assert!(receipt.matches_payload(&payload));
        let buffered = Receipt::Buffered {
            transfer_id: payload["transfer_id"].as_str().unwrap().into(),
            index: 0,
            sha256: "a".repeat(64),
        };
        assert!(!buffered.matches_payload(&payload));
        if let Receipt::Complete { sha256, .. } = &mut receipt {
            *sha256 = "b".repeat(64);
        }
        assert!(!receipt.matches_payload(&payload));
        assert!(serde_json::from_value::<Receipt>(json!({"state":"buffered","transfer_id":payload["transfer_id"],"sha256":"a".repeat(64),"index":0,"data":"aGk="})).is_err());
    }
}
