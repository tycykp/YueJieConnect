//! Versioned application handoff/catalog schemas, never arbitrary Android Intent serialization.
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeSet;

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum Command {
    Sync {
        request_id: String,
        source_package: String,
        #[serde(default, deserialize_with = "present")]
        native_owner_token: Option<String>,
    },
    Prepare {
        request_id: String,
        handoff: Handoff,
    },
    Launch {
        request_id: String,
    },
    Status {
        request_id: String,
    },
    Cancel {
        request_id: String,
    },
    Catalog {
        request_id: String,
        catalog: Catalog,
    },
}
fn present<'de, D: serde::Deserializer<'de>, T: Deserialize<'de>>(
    d: D,
) -> Result<Option<T>, D::Error> {
    T::deserialize(d).map(Some)
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Handoff {
    version: u32,
    request_id: String,
    source_package: String,
    kind: Kind,
    targets: Vec<String>,
    #[serde(default)]
    uri: String,
    #[serde(default)]
    signer_sha256: String,
    #[serde(default)]
    file_token: String,
    #[serde(default)]
    mime: String,
    #[serde(default)]
    progress_type: u32,
    #[serde(default)]
    progress: u32,
    #[serde(default)]
    progress_text: String,
    #[serde(default)]
    native_payload: String,
}
#[derive(Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum Kind {
    App,
    Browser,
    Document,
    Home,
    RichText,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Catalog {
    revision: i64,
    clear_all: bool,
    candidates: Vec<Candidate>,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Candidate {
    package: String,
    label: String,
    kind: CatalogKind,
    visible: bool,
    data_relay: bool,
    destination: u8,
    activity: String,
}
#[derive(Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum CatalogKind {
    App,
    Browser,
    Document,
    Recent,
    Cast,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Receipt {
    Task(TaskReceipt),
    Catalog(CatalogReceipt),
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TaskReceipt {
    version: u32,
    request_id: String,
    state: TaskState,
    code: String,
    target_package: String,
    task_id: i32,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum TaskState {
    Prepared,
    Launching,
    Completed,
    Failed,
    Cancelled,
}
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CatalogReceipt {
    version: u32,
    request_id: String,
    revision: i64,
    applied: bool,
    replayed: bool,
    code: String,
}
fn request_id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 96
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
}
fn package(s: &str) -> bool {
    s.len() <= 200
        && s.contains('.')
        && s.split('.').all(|p| {
            p.as_bytes().first().is_some_and(u8::is_ascii_alphabetic)
                && p.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
}
fn text(s: &str, max: usize) -> bool {
    s.len() <= max && !s.contains('\0')
}
fn hash(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn bounded<T: Serialize>(value: &T) -> bool {
    serde_json::to_vec(value).is_ok_and(|v| v.len() <= 6144)
}
fn uri(s: &str, browser: bool) -> bool {
    if !text(s, 8192) || s.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return false;
    }
    let Some((scheme, rest)) = s.split_once(':') else {
        return false;
    };
    let scheme = scheme.to_ascii_lowercase();
    if !scheme
        .as_bytes()
        .first()
        .is_some_and(u8::is_ascii_alphabetic)
        || !scheme
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"+-.".contains(&b))
        || matches!(
            scheme.as_str(),
            "intent"
                | "file"
                | "data"
                | "javascript"
                | "tel"
                | "sms"
                | "smsto"
                | "mms"
                | "mmsto"
                | "mailto"
                | "package"
                | "content"
        )
    {
        return false;
    }
    let authority = rest
        .strip_prefix("//")
        .map(|r| r.split(['/', '?', '#']).next().unwrap_or(""));
    if authority.is_some_and(|a| a.contains('@')) {
        return false;
    }
    !browser
        || (matches!(scheme.as_str(), "http" | "https") && authority.is_some_and(|a| !a.is_empty()))
}
impl Handoff {
    fn valid(&self, id: &str) -> bool {
        if self.version != 1
            || self.request_id != id
            || !package(&self.source_package)
            || self.targets.len() > 8
            || (self.targets.is_empty() && self.kind != Kind::Browser)
            || self.targets.iter().any(|p| !package(p))
            || self.targets.iter().collect::<BTreeSet<_>>().len() != self.targets.len()
            || !text(&self.progress_text, 2048)
            || self.progress > i32::MAX as u32
            || self.progress_type > 3
            || (!self.signer_sha256.is_empty() && !hash(&self.signer_sha256))
        {
            return false;
        }
        if matches!(self.kind, Kind::App | Kind::RichText) && !hash(&self.signer_sha256) {
            return false;
        }
        if self.kind == Kind::RichText {
            if !matches!(
                self.source_package.as_str(),
                "com.example.datarelaydemo" | "com.example.datarelaydemo01"
            ) || self.targets != [self.source_package.clone()]
                || self.native_payload.is_empty()
                || !text(&self.native_payload, 4096)
            {
                return false;
            }
        } else if !self.native_payload.is_empty() {
            return false;
        }
        if matches!(self.kind, Kind::App | Kind::Browser) {
            if !uri(&self.uri, self.kind == Kind::Browser) {
                return false;
            }
        } else if !self.uri.is_empty() {
            return false;
        }
        if self.kind == Kind::Document {
            let mime = self.mime.split('/').collect::<Vec<_>>();
            !self.file_token.is_empty()
                && self.file_token.len() <= 128
                && self
                    .file_token
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
                && self.mime.len() <= 128
                && mime.len() == 2
                && mime.iter().all(|s| {
                    !s.is_empty()
                        && s.bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b".+-".contains(&b))
                })
        } else {
            self.file_token.is_empty()
                && self.mime.is_empty()
                && self.progress_type == 0
                && self.progress == 0
                && self.progress_text.is_empty()
        }
    }
}
impl Catalog {
    fn valid(&self) -> bool {
        self.revision > 0
            && self.candidates.len() <= 16
            && (!self.clear_all || self.candidates.is_empty())
            && bounded(self)
            && self
                .candidates
                .iter()
                .map(|c| &c.package)
                .collect::<BTreeSet<_>>()
                .len()
                == self.candidates.len()
            && self.candidates.iter().all(|c| {
                package(&c.package)
                    && c.label.len() <= 256
                    && c.activity.len() <= 512
                    && !c
                        .label
                        .chars()
                        .chain(c.activity.chars())
                        .any(char::is_control)
                    && (!c.data_relay
                        || (c.visible
                            && !matches!(c.kind, CatalogKind::Recent | CatalogKind::Cast)))
            })
    }
}
impl Command {
    fn id(&self) -> &str {
        match self {
            Self::Prepare { request_id, .. }
            | Self::Launch { request_id }
            | Self::Status { request_id }
            | Self::Cancel { request_id }
            | Self::Catalog { request_id, .. } => request_id,
            Self::Sync { request_id, .. } => request_id,
        }
    }
}
pub fn parse(payload: &Value) -> Result<Command, &'static str> {
    if payload.to_string().len() > 6656 {
        return Err("invalid_payload");
    }
    let command: Command =
        serde_json::from_value(payload.clone()).map_err(|_| "invalid_payload")?;
    if !request_id(command.id())
        || !match &command {
            Command::Prepare {
                request_id,
                handoff,
            } => bounded(&payload["handoff"]) && handoff.valid(request_id),
            Command::Catalog { catalog, .. } => catalog.valid(),
            Command::Sync {
                source_package,
                native_owner_token,
                ..
            } => {
                package(source_package)
                    && native_owner_token
                        .as_ref()
                        .is_none_or(|t| crate::executor::valid_channel(t))
            }
            _ => true,
        }
    {
        return Err("invalid_payload");
    }
    Ok(command)
}
impl Receipt {
    pub fn valid(&self) -> bool {
        match self {
            Self::Catalog(r) => {
                r.version == 1
                    && request_id(&r.request_id)
                    && r.revision > 0
                    && r.applied
                    && r.code == "native_catalog_consumed"
            }
            Self::Task(r) => {
                r.version == 1
                    && request_id(&r.request_id)
                    && (r.target_package.is_empty() || package(&r.target_package))
                    && match r.state {
                        TaskState::Prepared => {
                            r.task_id == -1 && r.code == "ready" && !r.target_package.is_empty()
                        }
                        TaskState::Launching => {
                            r.task_id == -1
                                && matches!(
                                    r.code.as_str(),
                                    "launch_dispatched"
                                        | "too_late_to_cancel"
                                        | "native_source_requested"
                                )
                        }
                        TaskState::Completed => {
                            r.task_id >= 0
                                && matches!(
                                    r.code.as_str(),
                                    "foreground_observed" | "too_late_to_cancel"
                                )
                                && !r.target_package.is_empty()
                        }
                        TaskState::Cancelled => {
                            r.task_id == -1 && r.code == "cancelled_before_launch"
                        }
                        TaskState::Failed => {
                            r.task_id == -1
                                && matches!(
                                    r.code.as_str(),
                                    "unknown_request"
                                        | "request_conflict"
                                        | "request_timeout"
                                        | "session_capacity"
                                        | "session_revoked"
                                        | "session_revoked_after_dispatch"
                                        | "native_execution_failed"
                                        | "permission_denied"
                                        | "device_locked"
                                        | "document_unavailable"
                                        | "execution_revoked"
                                        | "file_transfer_required"
                                        | "invalid_document_uri"
                                        | "launch_unverified"
                                        | "target_activity_missing"
                                        | "target_app_disabled"
                                        | "target_app_missing"
                                        | "target_not_exported"
                                        | "target_permission_denied"
                                        | "target_signature_mismatch"
                                        | "target_signature_unavailable"
                                        | "task_observation_unavailable"
                                        | "ability_query_failed"
                                        | "ability_query_timeout"
                                        | "native_relay_disabled"
                                        | "target_relay_ability_missing"
                                        | "stale_session"
                                        | "file_token_unavailable"
                                        | "file_not_committed"
                                        | "file_already_claimed"
                                        | "document_expired"
                                        | "document_mime_mismatch"
                                        | "document_not_committed"
                                        | "document_already_claimed"
                                        | "consent_metadata_mismatch"
                                        | "destination_unreadable"
                                        | "destination_stalled"
                                        | "native_file_length_mismatch"
                                        | "native_file_hash_mismatch"
                                        | "native_source_unavailable"
                                        | "native_document_consent_required"
                                        | "native_source_already_requested"
                                        | "native_source_failed"
                                        | "invalid_remote_receipt"
                                        | "relay_busy"
                                )
                        }
                    }
            }
        }
    }
    pub fn matches_payload(&self, payload: &Value) -> bool {
        if !self.valid() {
            return false;
        }
        match (self, parse(payload)) {
            (
                Self::Task(r),
                Ok(Command::Sync {
                    request_id,
                    source_package,
                    ..
                }),
            ) => {
                r.request_id == request_id
                    && match r.state {
                        TaskState::Launching => {
                            r.target_package == source_package
                                && r.code == "native_source_requested"
                        }
                        TaskState::Completed => r.code == "foreground_observed",
                        TaskState::Failed | TaskState::Cancelled => true,
                        _ => false,
                    }
            }
            (
                Self::Catalog(r),
                Ok(Command::Catalog {
                    request_id,
                    catalog,
                }),
            ) => r.request_id == request_id && r.revision == catalog.revision,
            (Self::Task(r), Ok(c)) => {
                !matches!(c, Command::Catalog { .. }) && r.request_id == c.id()
            }
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn sync_preserves_actual_cross_vendor_target_and_pending_is_not_completion() {
        let payload =
            json!({"action":"sync","request_id":"synthetic_1","source_package":"test.source"});
        assert!(parse(&payload).is_ok());
        let valid:Receipt=serde_json::from_value(json!({"version":1,"request_id":"synthetic_1","state":"COMPLETED","code":"foreground_observed","target_package":"test.different.target","task_id":123})).unwrap();
        assert!(valid.matches_payload(&payload));
        let pending:Receipt=serde_json::from_value(json!({"version":1,"request_id":"synthetic_1","state":"LAUNCHING","code":"native_source_requested","target_package":"test.source","task_id":-1})).unwrap();
        assert!(pending.matches_payload(&payload));
        for value in [Value::Null, json!("A".repeat(32)), json!("a".repeat(31))] {
            let mut bad = payload.clone();
            bad["native_owner_token"] = value;
            assert!(parse(&bad).is_err());
        }
        let mut document = payload;
        document["native_owner_token"] = json!("a".repeat(32));
        assert!(parse(&document).is_ok());
    }
    fn prepare() -> Value {
        json!({"action":"prepare","request_id":"request_1","handoff":{"version":1,"request_id":"request_1","source_package":"test.source","kind":"BROWSER","targets":[],"uri":"https://example.com/synthetic"}})
    }
    #[test]
    fn relay_handoff_is_bounded_typed_and_cannot_be_a_remote_arbitrary_intent() {
        assert!(parse(&prepare()).is_ok());
        for uri in [
            "intent://bad",
            "file:///private",
            "content://private",
            "javascript:bad",
            "https://user@example.com/",
            "https://",
        ] {
            let mut p = prepare();
            p["handoff"]["uri"] = json!(uri);
            assert!(parse(&p).is_err());
        }
        for (field, value) in [
            ("extra", json!(true)),
            ("targets", json!(["test.app", "test.app"])),
            ("version", json!(1.0)),
            ("native_payload", json!("opaque")),
            ("request_id", json!("different")),
        ] {
            let mut p = prepare();
            p["handoff"][field] = value;
            assert!(parse(&p).is_err());
        }
        // Optional fields may be absent. Measure the actual handoff, rather than synthesizing
        // default fields which would wrongly reject a valid payload at the 6144-byte boundary.
        let mut p = prepare();
        let prefix = "https://example.com/";
        p["handoff"]["uri"] = json!(prefix);
        let fill = 6144 - p["handoff"].to_string().len();
        p["handoff"]["uri"] = json!(format!("{prefix}{}", "a".repeat(fill)));
        assert_eq!(p["handoff"].to_string().len(), 6144);
        assert!(parse(&p).is_ok());
        p["handoff"]["uri"] = json!(format!("{prefix}{}", "a".repeat(fill + 1)));
        assert!(parse(&p).is_err());
    }
    #[test]
    fn relay_catalog_requires_actual_receipt_and_keeps_launch_states_separate() {
        let p = json!({"action":"catalog","request_id":"catalog_1","catalog":{"revision":4,"clear_all":true,"candidates":[]}});
        assert!(parse(&p).is_ok());
        let r:Receipt=serde_json::from_value(json!({"version":1,"request_id":"catalog_1","revision":4,"applied":true,"replayed":false,"code":"native_catalog_consumed"})).unwrap();
        assert!(r.matches_payload(&p));
        assert!(!r.matches_payload(&prepare()));
        let mut bad = p.clone();
        bad["catalog"]["candidates"] = json!([{"package":"test.app","label":"App","kind":"APP","visible":true,"data_relay":true,"destination":1,"activity":"test.app.Main"}]);
        assert!(parse(&bad).is_err());
        let task:Receipt=serde_json::from_value(json!({"version":1,"request_id":"request_1","state":"COMPLETED","code":"foreground_observed","target_package":"test.target","task_id":12})).unwrap();
        assert!(task.matches_payload(&prepare()));
    }
}
