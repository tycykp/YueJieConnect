//! Bounded authenticated vendor-process mailbox. The socket thread never runs OEM code.
use crate::bridge::OperationResult;
use qj_core::gateway::Operation;
use serde::Serialize;
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    sync::mpsc::{self, Receiver, SyncSender},
};

const LEASE_MS: u64 = 4_000;
const MAX_TASKS: usize = 32;
type Result<T> = std::result::Result<T, &'static str>;

/// Fixed public error vocabulary: never reflect arbitrary OEM exception text or payload data.
pub fn execution_error(code: &str) -> &'static str {
    if let Some(value) = crate::telephony::EXECUTION_ERRORS
        .iter()
        .find(|value| **value == code)
    {
        return value;
    }
    match code {
        "cancelled" => "cancelled",
        "permission_denied" => "permission_denied",
        "request_timeout" | "expired" => "request_timeout",
        "invalid_payload" => "invalid_payload",
        "busy" => "busy",
        "session_expired" | "stale_session" => "session_expired",
        "executor_unavailable" => "executor_unavailable",
        "channel_closed" => "channel_closed",
        "channel_conflict" => "channel_conflict",
        "unsupported_operation" => "unsupported_operation",
        "battery_unavailable" => "battery_unavailable",
        "finder_busy" => "finder_busy",
        "invalid_action" => "invalid_action",
        "invalid_duration" => "invalid_duration",
        "invalid_finder_payload" => "invalid_finder_payload",
        "invalid_request_id" => "invalid_request_id",
        "request_conflict" => "request_conflict",
        "playback_unverified" => "playback_unverified",
        "audio_busy" => "audio_busy",
        "alarm_not_audible" => "alarm_not_audible",
        "audio_focus_denied" => "audio_focus_denied",
        "finder_torch_unavailable" => "finder_torch_unavailable",
        "finder_torch_busy" => "finder_torch_busy",
        "vibration_permission_required" => "vibration_permission_required",
        "request_capacity" => "request_capacity",
        "finder_execution_failed" => "finder_execution_failed",
        "notification_execution_failed" => "notification_execution_failed",
        "notification_request_rejected" => "notification_request_rejected",
        "native_peer_unavailable" => "native_peer_unavailable",
        "native_directory_unavailable" => "native_directory_unavailable",
        "stale_relay_catalog" => "stale_relay_catalog",
        "native_user_agreement_required" => "native_user_agreement_required",
        "native_relay_disabled" => "native_relay_disabled",
        "ability_query_failed" => "ability_query_failed",
        "ability_query_timeout" => "ability_query_timeout",
        "target_relay_ability_missing" => "target_relay_ability_missing",
        "native_file_provider_unavailable" => "native_file_provider_unavailable",
        "native_file_uri_invalid" => "native_file_uri_invalid",
        "native_icon_unavailable" => "native_icon_unavailable",
        "native_icon_cleanup_failed" => "native_icon_cleanup_failed",
        "invalid_file_operation" => "invalid_file_operation",
        "invalid_file_payload" => "invalid_file_payload",
        "invalid_file_result" => "invalid_file_result",
        "invalid_chunk" => "invalid_chunk",
        "file_length_exceeded" => "file_length_exceeded",
        "consent_metadata_mismatch" => "consent_metadata_mismatch",
        "receive_consent_required" => "receive_consent_required",
        "receive_consent_failed" => "receive_consent_failed",
        "native_file_owner_reused" => "native_file_owner_reused",
        "file_transfer_limit" => "file_transfer_limit",
        "file_transfer_released" => "file_transfer_released",
        "file_cancelled" => "file_cancelled",
        "file_busy" => "file_busy",
        "offset_mismatch" => "offset_mismatch",
        "file_incomplete" => "file_incomplete",
        "file_hash_mismatch" => "file_hash_mismatch",
        "native_file_hash_mismatch" => "native_file_hash_mismatch",
        "native_file_receive_failed" => "native_file_receive_failed",
        "destination_cleanup_failed" => "destination_cleanup_failed",
        "invalid_clipboard_uri_payload" => "invalid_clipboard_uri_payload",
        "clipboard_uri_already_started" => "clipboard_uri_already_started",
        "request_id_reused" => "request_id_reused",
        "clipboard_uri_request_limit" => "clipboard_uri_request_limit",
        "invalid_request" => "invalid_request",
        "expired_or_invalid_request" => "expired_or_invalid_request",
        "file_receive_failed" => "file_receive_failed",
        "clipboard_uri_claim_failed" => "clipboard_uri_claim_failed",
        "clipboard_uri_worker_unavailable" => "clipboard_uri_worker_unavailable",
        "native_clipboard_failed" => "native_clipboard_failed",
        "clipboard_write_unverified" => "clipboard_write_unverified",
        "invalid_media_request" => "invalid_media_request",
        "duplicate_request" => "duplicate_request",
        "stale_media_session" => "stale_media_session",
        "cancelled_after_dispatch" => "cancelled_after_dispatch",
        "media_unavailable" => "media_unavailable",
        "ambiguous_media_session" => "ambiguous_media_session",
        "action_unavailable" => "action_unavailable",
        "queue_unavailable" => "queue_unavailable",
        "position_unavailable" => "position_unavailable",
        "volume_fixed" => "volume_fixed",
        "volume_changed" => "volume_changed",
        "volume_out_of_range" => "volume_out_of_range",
        "native_rejected" => "native_rejected",
        "result_unconfirmed" => "result_unconfirmed",
        "execution_failed" => "execution_failed",
        "media_execution_failed" => "media_execution_failed",
        "invalid_result" => "invalid_result",
        _ => "remote_error",
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct ExecutorOwner {
    pub uid: u32,
    pub pid: u32,
    pub token: String,
}
struct Registration {
    owner: ExecutorOwner,
    until: u64,
}
#[derive(Clone, Serialize)]
pub struct VendorTask {
    pub session_id: String,
    pub request_id: String,
    pub projection_id: String,
    pub operation: Operation,
    pub payload: Value,
    pub remaining_ms: u64,
}
struct Pending {
    owner: ExecutorOwner,
    task: VendorTask,
    deadline: u64,
    delivered: bool,
    cancel_requested: bool,
    reply: SyncSender<Result<OperationResult>>,
}
#[derive(Default)]
pub struct VendorMailbox {
    registrations: BTreeMap<Operation, Registration>,
    logical_registrations: BTreeMap<(u32, u32), (ExecutorOwner, BTreeSet<Operation>, u64)>,
    logical_routes: BTreeMap<(String, String), ExecutorOwner>,
    pending: BTreeMap<(String, String), Pending>,
    order: VecDeque<(String, String)>,
    cancellations: VecDeque<(ExecutorOwner, String, String)>,
    files: crate::file_session::FileSession,
}
fn logical_control(operation: Operation) -> bool {
    matches!(
        operation,
        Operation::LogicalOpen | Operation::LogicalClose | Operation::LogicalState
    )
}

pub fn supported(operation: Operation) -> bool {
    matches!(
        operation,
        Operation::ClipboardWrite
            | Operation::ClipboardRichWrite
            | Operation::DeviceFinder
            | Operation::NotificationPost
            | Operation::NotificationRemove
            | Operation::NotificationAction
            | Operation::AppRelay
            | Operation::FilePrepare
            | Operation::FileOffer
            | Operation::FileChunk
            | Operation::FileCommit
            | Operation::FileCancel
            | Operation::ClipboardUriWrite
            | Operation::MediaControl
            | Operation::LogicalOpen
            | Operation::LogicalClose
            | Operation::LogicalState
            | Operation::TelephonyCallState
            | Operation::TelephonyCallControl
            | Operation::TelephonySmsStatus
            | Operation::ScreenControl
    )
}
pub fn valid_channel(value: &str) -> bool {
    value.len() == 32
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub fn logical_business(operation: Operation) -> bool {
    supported(operation)
        && !matches!(
            operation,
            Operation::LogicalOpen
                | Operation::LogicalClose
                | Operation::LogicalState
                | Operation::DeviceFinder
                | Operation::NotificationPost
                | Operation::NotificationRemove
                | Operation::NotificationAction
                | Operation::TelephonyCallState
                | Operation::TelephonyCallControl
                | Operation::TelephonySmsStatus
                | Operation::ScreenControl
        )
}
pub fn telephony_operation(operation: Operation) -> Option<&'static str> {
    match operation {
        Operation::TelephonyCallState => Some(crate::telephony::CALL_STATE),
        Operation::TelephonyCallControl => Some(crate::telephony::CALL_CONTROL),
        Operation::TelephonySmsStatus => Some(crate::telephony::SMS_STATUS),
        _ => None,
    }
}

pub(crate) fn logical_operations(payload: &Value) -> Result<BTreeSet<Operation>> {
    let values = payload["operations"].as_array().ok_or("invalid_payload")?;
    if values.is_empty() || values.len() > 10 {
        return Err("invalid_payload");
    }
    let mut operations = BTreeSet::new();
    for value in values {
        let operation: Operation =
            serde_json::from_value(value.clone()).map_err(|_| "invalid_payload")?;
        if !logical_business(operation) || !operations.insert(operation) {
            return Err("invalid_payload");
        }
    }
    Ok(operations)
}

pub fn file_operation(operation: Operation) -> Option<&'static str> {
    match operation {
        Operation::FilePrepare => Some("file_prepare"),
        Operation::FileOffer => Some("file_offer"),
        Operation::FileChunk => Some("file_chunk"),
        Operation::FileCommit => Some("file_commit"),
        Operation::FileCancel => Some("file_cancel"),
        _ => None,
    }
}
pub fn timeout_ms(operation: Operation) -> u64 {
    match operation {
        Operation::TelephonyCallState
        | Operation::TelephonyCallControl
        | Operation::TelephonySmsStatus => 10_000,
        Operation::FileCommit => 15_000,
        Operation::AppRelay => 20_000,
        _ => 5_000,
    }
}
fn uri_file_payload(payload: &Value) -> Result<Value> {
    let mut value = payload
        .as_object()
        .ok_or("invalid_clipboard_uri_payload")?
        .clone();
    if value.len() != 5 || value.contains_key("transfer") {
        return Err("invalid_clipboard_uri_payload");
    }
    let transfer = value
        .remove("transfer_id")
        .ok_or("invalid_clipboard_uri_payload")?;
    value.insert("transfer".into(), transfer);
    let value = Value::Object(value);
    crate::file_transfer::parse("file_offer", &value)
        .map_err(|_| "invalid_clipboard_uri_payload")?;
    Ok(value)
}

/// Only this typed operation is enabled; adding a name does not grant a generic RPC channel.
pub fn validate_payload(operation: Operation, payload: &Value) -> Result<()> {
    if operation == Operation::ScreenControl {
        return if crate::screen_control::valid(payload) {
            Ok(())
        } else {
            Err("invalid_payload")
        };
    }
    if let Some(op) = telephony_operation(operation) {
        return if crate::telephony::valid_payload(op, payload) {
            Ok(())
        } else {
            Err("invalid_payload")
        };
    }
    if let Some(name) = file_operation(operation) {
        return crate::file_transfer::parse(name, payload).map(|_| ());
    }
    if operation == Operation::ClipboardUriWrite {
        return uri_file_payload(payload).map(|_| ());
    }
    if operation == Operation::MediaControl {
        return crate::media_control::parse(payload).map(|_| ());
    }
    if operation == Operation::AppRelay {
        return crate::relay::parse(payload).map(|_| ());
    }
    if operation == Operation::ClipboardRichWrite {
        rich_clipboard_bytes(payload)?;
        return Ok(());
    }
    if matches!(
        operation,
        Operation::NotificationPost | Operation::NotificationRemove | Operation::NotificationAction
    ) {
        return crate::notification::parse(payload).map(|_| ());
    }
    if operation == Operation::DeviceFinder {
        let p = payload.as_object().ok_or("invalid_payload")?;
        match p.get("action").and_then(Value::as_str) {
            Some("start") if p.len() == 1 => return Ok(()),
            Some("start")
                if p.len() == 2
                    && p.get("duration_ms")
                        .and_then(Value::as_u64)
                        .is_some_and(|n| (1..=30_000).contains(&n)) =>
            {
                return Ok(())
            }
            Some("state" | "stop") if p.len() == 1 => return Ok(()),
            _ => return Err("invalid_payload"),
        }
    }
    if matches!(
        operation,
        Operation::LogicalOpen | Operation::LogicalClose | Operation::LogicalState
    ) {
        let p = payload.as_object().ok_or("invalid_payload")?;
        if !p
            .get("channel_id")
            .and_then(Value::as_str)
            .is_some_and(valid_channel)
        {
            return Err("invalid_payload");
        }
        if operation == Operation::LogicalOpen {
            if p.len() != 2 || logical_operations(payload).is_err() {
                return Err("invalid_payload");
            }
        } else if p.len() != 1 {
            return Err("invalid_payload");
        }
        return Ok(());
    }
    if operation != Operation::ClipboardWrite {
        return if payload.as_object().is_some_and(|p| p.is_empty()) {
            Ok(())
        } else {
            Err("invalid_payload")
        };
    }
    let p = payload.as_object().ok_or("invalid_payload")?;
    if p.len() != 2 || p.get("mime").and_then(Value::as_str) != Some("text/plain") {
        return Err("invalid_payload");
    }
    let encoded = p
        .get("utf8_b64")
        .and_then(Value::as_str)
        .ok_or("invalid_payload")?;
    let bytes = decode_base64(encoded, 4096)?;
    if std::str::from_utf8(&bytes).is_err() {
        return Err("invalid_payload");
    }
    Ok(())
}

// Canonical RFC4648 only. The caller supplies the decoded byte budget before allocation.
pub(crate) fn decode_base64(encoded: &str, limit: usize) -> Result<Vec<u8>> {
    if encoded.len() > limit.div_ceil(3) * 4 || !encoded.len().is_multiple_of(4) {
        return Err("invalid_payload");
    }
    let mut bytes = Vec::with_capacity(encoded.len() / 4 * 3);
    let alphabet = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (index, chunk) in encoded.as_bytes().as_chunks::<4>().0.iter().enumerate() {
        let last = (index + 1) * 4 == encoded.len();
        let a = alphabet
            .iter()
            .position(|b| *b == chunk[0])
            .ok_or("invalid_payload")? as u8;
        let b = alphabet
            .iter()
            .position(|b| *b == chunk[1])
            .ok_or("invalid_payload")? as u8;
        bytes.push((a << 2) | (b >> 4));
        if chunk[2] == b'=' {
            if !last || chunk[3] != b'=' || b & 15 != 0 {
                return Err("invalid_payload");
            }
            continue;
        }
        let c = alphabet
            .iter()
            .position(|b| *b == chunk[2])
            .ok_or("invalid_payload")? as u8;
        bytes.push((b << 4) | (c >> 2));
        if chunk[3] == b'=' {
            if !last || c & 3 != 0 {
                return Err("invalid_payload");
            }
            continue;
        }
        let d = alphabet
            .iter()
            .position(|b| *b == chunk[3])
            .ok_or("invalid_payload")? as u8;
        bytes.push((c << 6) | d);
    }
    if bytes.len() > limit {
        return Err("invalid_payload");
    }
    Ok(bytes)
}

pub(crate) fn rich_clipboard_bytes(payload: &Value) -> Result<usize> {
    let p = payload.as_object().ok_or("invalid_payload")?;
    if p.len() != 3 || p.get("mime").and_then(Value::as_str) != Some("text/html") {
        return Err("invalid_payload");
    }
    let text = decode_base64(
        p.get("utf8_b64")
            .and_then(Value::as_str)
            .ok_or("invalid_payload")?,
        4096,
    )?;
    let html = decode_base64(
        p.get("html_utf8_b64")
            .and_then(Value::as_str)
            .ok_or("invalid_payload")?,
        4096,
    )?;
    let total = text.len() + html.len();
    if html.is_empty()
        || total > 4096
        || std::str::from_utf8(&text).is_err()
        || std::str::from_utf8(&html).is_err()
    {
        return Err("invalid_payload");
    }
    Ok(total)
}

pub(crate) fn receipt_matches_payload(value: &OperationResult, payload: &Value) -> bool {
    match value {
        OperationResult::ScreenControl(result) => {
            return crate::screen_control::matches(payload, result)
        }
        OperationResult::TelephonyCallState(result) => {
            return crate::telephony::valid_result(crate::telephony::CALL_STATE, payload, result)
        }
        OperationResult::TelephonyCallControl(result) => {
            return crate::telephony::valid_result(crate::telephony::CALL_CONTROL, payload, result)
        }
        OperationResult::TelephonySmsStatus(result) => {
            return crate::telephony::valid_result(crate::telephony::SMS_STATUS, payload, result)
        }
        _ => {}
    }
    match value {
        OperationResult::FilePrepare(r) => {
            crate::file_transfer::validate_result("file_prepare", payload, r).is_ok()
        }
        OperationResult::FileOffer(r) => {
            crate::file_transfer::validate_result("file_offer", payload, r).is_ok()
        }
        OperationResult::FileChunk(r) => {
            crate::file_transfer::validate_result("file_chunk", payload, r).is_ok()
        }
        OperationResult::FileCommit(r) => {
            crate::file_transfer::validate_result("file_commit", payload, r).is_ok()
        }
        OperationResult::FileCancel(r) => {
            crate::file_transfer::validate_result("file_cancel", payload, r).is_ok()
        }
        OperationResult::MediaControl(r) => serde_json::to_value(r)
            .is_ok_and(|data| crate::media_control::valid_result(payload, &data)),
        OperationResult::ClipboardUriWrite(r) => {
            uri_file_payload(payload).is_ok()
                && r.as_object().is_some_and(|o| o.len() == 5)
                && r["applied"] == true
                && r["transfer_id"] == payload["transfer_id"]
                && r["mime"] == payload["mime"]
                && r["size"] == payload["size"]
                && r["sha256"] == payload["sha256"]
        }
        OperationResult::AppRelay(r) => r.matches_payload(payload),
        OperationResult::ClipboardWrite(r) => payload["utf8_b64"]
            .as_str()
            .is_some_and(|s| decode_base64(s, 4096).is_ok_and(|b| b.len() == r.utf8_bytes)),
        OperationResult::ClipboardRichWrite(r) => rich_clipboard_bytes(payload) == Ok(r.utf8_bytes),
        OperationResult::NotificationPost(r)
        | OperationResult::NotificationRemove(r)
        | OperationResult::NotificationAction(r) => r.matches_payload(payload),
        OperationResult::LogicalOpen(r) => {
            Some(r.channel_id.as_str()) == payload["channel_id"].as_str()
                && logical_operations(payload).is_ok_and(|ops| ops == r.operations)
        }
        OperationResult::LogicalClose(r) | OperationResult::LogicalState(r) => {
            Some(r.channel_id.as_str()) == payload["channel_id"].as_str()
        }
        _ => true,
    }
}

impl VendorMailbox {
    pub fn register(
        &mut self,
        owner: ExecutorOwner,
        operations: BTreeSet<Operation>,
        now: u64,
    ) -> Result<()> {
        self.expire(now);
        if operations.iter().any(|op| !supported(*op)) {
            return Err("unsupported_operation");
        }
        if operations.contains(&Operation::LogicalOpen)
            && !operations.iter().any(|op| logical_business(*op))
        {
            return Err("executor_unavailable");
        }
        // Native IPC has already authorized the freshly opened lease. Renewing the same
        // kernel UID/PID must not advertise a four-second outage or abandon in-flight tasks.
        for registration in self.registrations.values_mut() {
            if registration.owner.uid == owner.uid && registration.owner.pid == owner.pid {
                registration.owner = owner.clone();
            }
        }
        for pending in self.pending.values_mut() {
            if pending.owner.uid == owner.uid && pending.owner.pid == owner.pid {
                pending.owner = owner.clone();
            }
        }
        for route in self.logical_routes.values_mut() {
            if route.uid == owner.uid && route.pid == owner.pid {
                *route = owner.clone();
            }
        }
        for (registered, _, _) in &mut self.cancellations {
            if registered.uid == owner.uid && registered.pid == owner.pid {
                *registered = owner.clone();
            }
        }
        if operations.iter().any(|op| {
            !logical_control(*op) && self.registrations.get(op).is_some_and(|r| r.owner != owner)
        }) {
            return Err("executor_busy");
        }
        let logical: BTreeSet<_> = operations
            .iter()
            .copied()
            .filter(|op| logical_control(*op))
            .collect();
        if logical.is_empty() {
            self.logical_registrations.remove(&(owner.uid, owner.pid));
        } else {
            self.logical_registrations.insert(
                (owner.uid, owner.pid),
                (owner.clone(), logical, now + LEASE_MS),
            );
        }
        let removed: BTreeSet<_> = self
            .registrations
            .iter()
            .filter(|(op, r)| r.owner == owner && !operations.contains(op))
            .map(|(op, _)| *op)
            .collect();
        self.registrations
            .retain(|op, r| r.owner != owner || operations.contains(op));
        self.pending.retain(|_, p| {
            if p.owner == owner && removed.contains(&p.task.operation) {
                self.files.abandon(&p.task.request_id);
                let _ = p.reply.try_send(Err("executor_unavailable"));
                false
            } else {
                true
            }
        });
        for operation in operations {
            if logical_control(operation) {
                continue;
            }
            self.registrations.insert(
                operation,
                Registration {
                    owner: owner.clone(),
                    until: now + LEASE_MS,
                },
            );
        }
        Ok(())
    }
    pub fn operations(&mut self, now: u64) -> BTreeSet<Operation> {
        self.expire(now);
        self.registrations
            .keys()
            .copied()
            .chain(
                self.logical_registrations
                    .values()
                    .flat_map(|(_, ops, _)| ops.iter().copied()),
            )
            .collect()
    }
    pub fn submit(
        &mut self,
        task: VendorTask,
        now: u64,
    ) -> Result<Receiver<Result<OperationResult>>> {
        self.expire(now);
        validate_payload(task.operation, &task.payload)?;
        let owner = if logical_control(task.operation) {
            let key = (
                task.session_id.clone(),
                task.payload["channel_id"]
                    .as_str()
                    .ok_or("invalid_payload")?
                    .to_owned(),
            );
            let candidate = if task.operation == Operation::LogicalOpen {
                let requested = logical_operations(&task.payload)?;
                let first = requested.iter().next().ok_or("invalid_payload")?;
                self.registrations
                    .get(first)
                    .ok_or("executor_unavailable")?
                    .owner
                    .clone()
            } else if let Some(owner) = self.logical_routes.get(&key) {
                owner.clone()
            } else {
                self.logical_registrations
                    .values()
                    .find(|(_, ops, _)| ops.contains(&task.operation))
                    .ok_or("executor_unavailable")?
                    .0
                    .clone()
            };
            if !self
                .logical_registrations
                .get(&(candidate.uid, candidate.pid))
                .is_some_and(|(o, ops, _)| o == &candidate && ops.contains(&task.operation))
            {
                return Err("executor_unavailable");
            }
            if self
                .logical_routes
                .get(&key)
                .is_some_and(|o| o != &candidate)
            {
                return Err("channel_conflict");
            }
            if !self.logical_routes.contains_key(&key) && self.logical_routes.len() >= 256 {
                return Err("busy");
            }
            self.logical_routes.insert(key, candidate.clone());
            candidate
        } else {
            self.registrations
                .get(&task.operation)
                .ok_or("executor_unavailable")?
                .owner
                .clone()
        };
        if task.operation == Operation::LogicalOpen {
            for operation in logical_operations(&task.payload)? {
                if !self
                    .registrations
                    .get(&operation)
                    .is_some_and(|r| r.owner == owner)
                {
                    return Err("executor_unavailable");
                }
            }
        }
        if self.pending.len() >= MAX_TASKS {
            return Err("busy");
        }
        let key = (task.session_id.clone(), task.request_id.clone());
        if self.pending.contains_key(&key) {
            return Err("duplicate_request");
        }
        if file_operation(task.operation).is_some() {
            self.files.scope(&task.session_id);
            self.files
                .begin(owner.uid, &task.request_id, task.operation, &task.payload)?;
        }
        let deadline = now + task.remaining_ms.min(timeout_ms(task.operation));
        let (reply, receive) = mpsc::sync_channel(1);
        self.order.push_back(key.clone());
        self.pending.insert(
            key,
            Pending {
                owner,
                task,
                deadline,
                delivered: false,
                cancel_requested: false,
                reply,
            },
        );
        Ok(receive)
    }
    pub fn poll(
        &mut self,
        owner: &ExecutorOwner,
        now: u64,
    ) -> (Option<VendorTask>, Vec<(String, String)>) {
        self.expire(now);
        if let Some((registered, _, until)) =
            self.logical_registrations.get_mut(&(owner.uid, owner.pid))
        {
            if registered == owner {
                *until = now + LEASE_MS;
            }
        }
        for registration in self
            .registrations
            .values_mut()
            .filter(|r| &r.owner == owner)
        {
            registration.until = now + LEASE_MS;
        }
        let mut cancelled = Vec::new();
        self.cancellations.retain(|(o, s, r)| {
            if o == owner && cancelled.len() < 8 {
                cancelled.push((s.clone(), r.clone()));
                false
            } else {
                true
            }
        });
        let next = self
            .order
            .iter()
            .find(|key| {
                self.pending
                    .get(*key)
                    .is_some_and(|p| &p.owner == owner && !p.delivered)
            })
            .cloned();
        let task = next.and_then(|key| self.pending.get_mut(&key)).map(|p| {
            p.delivered = true;
            let mut task = p.task.clone();
            task.remaining_ms = p.deadline.saturating_sub(now);
            task
        });
        (task, cancelled)
    }
    pub fn complete(
        &mut self,
        owner: &ExecutorOwner,
        session: &str,
        request: &str,
        outcome: Result<OperationResult>,
        now: u64,
    ) -> Result<()> {
        self.expire(now);
        let key = (session.to_owned(), request.to_owned());
        let p = self.pending.get(&key).ok_or("stale_task")?;
        if &p.owner != owner || !p.delivered {
            return Err("forbidden");
        }
        if let Ok(value) = &outcome {
            if !value.matches(p.task.operation, &[0; 32])
                || !receipt_matches_payload(value, &p.task.payload)
            {
                return Err("invalid_result");
            }
        }
        self.files.finish(request, &outcome)?;
        let pending = self.pending.remove(&key).unwrap();
        let _ = pending.reply.try_send(outcome);
        Ok(())
    }
    pub fn cancel(&mut self, session: &str, request: &str) {
        let key = (session.to_owned(), request.to_owned());
        if let Some(p) = self.pending.get_mut(&key) {
            if !p.delivered {
                let p = self.pending.remove(&key).unwrap();
                self.files.abandon(request);
                let _ = p.reply.try_send(Err("cancelled"));
            } else if !p.cancel_requested {
                p.cancel_requested = true;
                self.cancellations.push_back((
                    p.owner.clone(),
                    session.to_owned(),
                    request.to_owned(),
                ));
            }
        }
        self.bound_cancellations();
    }
    pub fn invalidate(&mut self) {
        self.logical_routes.clear();
        self.files.scope("");
        self.order.clear();
        for (_, p) in std::mem::take(&mut self.pending) {
            let _ = p.reply.try_send(Err("session_expired"));
            if p.delivered {
                self.cancellations
                    .push_back((p.owner, p.task.session_id, p.task.request_id));
            }
        }
        self.bound_cancellations();
    }
    fn bound_cancellations(&mut self) {
        while self.cancellations.len() > 64 {
            self.cancellations.pop_front();
        }
    }
    fn expire(&mut self, now: u64) {
        self.registrations.retain(|_, r| now < r.until);
        self.logical_registrations
            .retain(|_, (_, _, until)| now < *until);
        self.pending.retain(|_, p| {
            let code = if now >= p.deadline {
                Some("request_timeout")
            } else if logical_control(p.task.operation)
                && !self
                    .logical_registrations
                    .get(&(p.owner.uid, p.owner.pid))
                    .is_some_and(|(o, ops, _)| o == &p.owner && ops.contains(&p.task.operation))
            {
                Some("executor_unavailable")
            } else if !logical_control(p.task.operation)
                && !self
                    .registrations
                    .get(&p.task.operation)
                    .is_some_and(|r| r.owner == p.owner)
            {
                Some("executor_unavailable")
            } else {
                None
            };
            if let Some(code) = code {
                self.files.abandon(&p.task.request_id);
                let _ = p.reply.try_send(Err(code));
                if p.delivered {
                    self.cancellations.push_back((
                        p.owner.clone(),
                        p.task.session_id.clone(),
                        p.task.request_id.clone(),
                    ));
                }
                false
            } else {
                true
            }
        });
        self.order.retain(|key| self.pending.contains_key(key));
        self.bound_cancellations();
    }
    pub fn allocate_file(&mut self, uid: u32, session: &str, transfer: &str) -> Result<()> {
        self.files.scope(session);
        self.files.allocate_outgoing(uid, transfer)
    }
    pub fn release_file(
        &mut self,
        uid: u32,
        session: &str,
        transfer: &str,
        direction: &str,
    ) -> Result<()> {
        self.files.scope(session);
        self.files.release(uid, transfer, direction)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::ClipboardReceipt;
    use serde_json::json;
    fn owner() -> ExecutorOwner {
        ExecutorOwner {
            uid: 123,
            pid: 456,
            token: "verified-lease".into(),
        }
    }
    fn task(request: &str) -> VendorTask {
        VendorTask {
            session_id: "a".repeat(64),
            request_id: request.into(),
            projection_id: "qj:peer".into(),
            operation: Operation::ClipboardWrite,
            payload: json!({"mime":"text/plain","utf8_b64":"aGk="}),
            remaining_ms: 5000,
        }
    }
    fn receipt() -> OperationResult {
        OperationResult::ClipboardWrite(ClipboardReceipt {
            applied: true,
            utf8_bytes: 2,
        })
    }
    #[test]
    fn rich_clipboard_payload_and_receipt_bind_the_total_utf8_bytes() {
        use crate::bridge::RichClipboardReceipt;
        let payload = json!({"mime":"text/html","utf8_b64":"aGk=","html_utf8_b64":"PGI+aGk8L2I+"});
        assert_eq!(rich_clipboard_bytes(&payload), Ok(11));
        assert!(validate_payload(Operation::ClipboardRichWrite, &payload).is_ok());
        assert_eq!(
            validate_payload(Operation::ClipboardWrite, &payload),
            Err("invalid_payload")
        );
        for (field, bad) in [
            ("mime", json!("text/plain")),
            ("html_utf8_b64", json!("")),
            ("utf8_b64", json!("/w==")),
            ("html_utf8_b64", json!("AAAA".repeat(1366))),
            ("extra", json!(true)),
        ] {
            let mut invalid = payload.clone();
            invalid[field] = bad;
            assert!(validate_payload(Operation::ClipboardRichWrite, &invalid).is_err());
        }
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(
            o.clone(),
            BTreeSet::from([Operation::ClipboardRichWrite]),
            0,
        )
        .unwrap();
        let mut work = task("rich");
        work.operation = Operation::ClipboardRichWrite;
        work.payload = payload;
        let rx = m.submit(work, 1).unwrap();
        m.poll(&o, 2);
        let receipt = |n| {
            OperationResult::ClipboardRichWrite(RichClipboardReceipt {
                mime: "text/html".into(),
                applied: true,
                utf8_bytes: n,
            })
        };
        assert_eq!(
            m.complete(&o, &"a".repeat(64), "rich", Ok(receipt(2)), 3),
            Err("invalid_result")
        );
        m.complete(&o, &"a".repeat(64), "rich", Ok(receipt(11)), 3)
            .unwrap();
        assert_eq!(rx.recv().unwrap(), Ok(receipt(11)));
    }
    #[test]
    fn finder_terminal_errors_are_preserved_and_internal_or_unknown_text_is_redacted() {
        for code in [
            "alarm_not_audible",
            "audio_busy",
            "audio_focus_denied",
            "cancelled",
            "finder_busy",
            "finder_execution_failed",
            "finder_torch_busy",
            "finder_torch_unavailable",
            "invalid_action",
            "invalid_duration",
            "invalid_finder_payload",
            "invalid_request_id",
            "playback_unverified",
            "request_capacity",
            "request_conflict",
            "request_timeout",
            "session_expired",
            "vibration_permission_required",
        ] {
            assert_eq!(execution_error(code), code);
        }
        assert_eq!(execution_error("stale_session"), "session_expired");
        assert_eq!(execution_error("cleanup_pending"), "remote_error");
        assert_eq!(
            execution_error("synthetic OEM exception with private fields"),
            "remote_error"
        );
    }
    #[test]
    fn logical_channels_route_to_each_business_process_and_preserve_close_owner() {
        let mut m = VendorMailbox::default();
        let clipboard = owner();
        let mut relay = owner();
        relay.pid += 1;
        for (o, business) in [
            (&clipboard, Operation::ClipboardWrite),
            (&relay, Operation::AppRelay),
        ] {
            m.register(
                o.clone(),
                BTreeSet::from([
                    business,
                    Operation::LogicalOpen,
                    Operation::LogicalClose,
                    Operation::LogicalState,
                ]),
                0,
            )
            .unwrap();
        }
        let mut work = task("logical-relay");
        work.operation = Operation::LogicalOpen;
        work.payload = json!({"channel_id":"d".repeat(32),"operations":["app_relay"]});
        let _result = m.submit(work, 1).unwrap();
        assert!(m.poll(&clipboard, 2).0.is_none());
        assert_eq!(m.poll(&relay, 2).0.unwrap().request_id, "logical-relay");
        let mut close = task("close-relay");
        close.operation = Operation::LogicalClose;
        close.payload = json!({"channel_id":"d".repeat(32)});
        let _closed = m.submit(close, 3).unwrap();
        assert!(m.poll(&clipboard, 4).0.is_none());
        assert_eq!(
            m.poll(&relay, 4).0.unwrap().operation,
            Operation::LogicalClose
        );
        let mut hijack = task("hijack");
        hijack.operation = Operation::LogicalOpen;
        hijack.payload = json!({"channel_id":"d".repeat(32),"operations":["clipboard_write"]});
        assert!(matches!(m.submit(hijack, 5), Err("channel_conflict")));
    }
    #[test]
    fn telephony_mailbox_rejects_fake_native_receipts_and_keeps_known_errors() {
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(
            o.clone(),
            BTreeSet::from([Operation::TelephonyCallControl]),
            0,
        )
        .unwrap();
        let mut work = task("call-control");
        work.operation = Operation::TelephonyCallControl;
        work.payload = json!({"call_id":"call_local_1","revision":3,"action":"answer"});
        let result = m.submit(work, 1).unwrap();
        let task = m.poll(&o, 2).0.unwrap();
        let wrong = OperationResult::TelephonyCallControl(
            json!({"call_id":"call_local_1","revision":3,"state":"active","observed":true}),
        );
        assert_eq!(
            m.complete(&o, &task.session_id, &task.request_id, Ok(wrong), 3),
            Err("invalid_result")
        );
        let right = OperationResult::TelephonyCallControl(
            json!({"call_id":"call_local_1","revision":4,"state":"active","observed":true}),
        );
        m.complete(&o, &task.session_id, &task.request_id, Ok(right.clone()), 4)
            .unwrap();
        assert_eq!(result.recv().unwrap(), Ok(right));
        assert_eq!(execution_error("native_unavailable"), "native_unavailable");
        assert_eq!(
            execution_error("untrusted native exception"),
            "remote_error"
        );
    }
    #[test]
    fn logical_business_sets_bind_receipts_and_executor_ownership() {
        use crate::bridge::LogicalReceipt;
        let channel = "b".repeat(32);
        let payload = json!({"channel_id":channel,"operations":["app_relay","media_control"]});
        assert!(validate_payload(Operation::LogicalOpen, &payload).is_ok());
        for operations in [
            json!([]),
            json!(["app_relay", "app_relay"]),
            json!(["logical_open"]),
            json!(["camera_video"]),
        ] {
            assert_eq!(
                validate_payload(
                    Operation::LogicalOpen,
                    &json!({"channel_id":channel,"operations":operations})
                ),
                Err("invalid_payload")
            );
        }
        let wrong = OperationResult::LogicalOpen(LogicalReceipt {
            channel_id: channel.clone(),
            state: "open".into(),
            operations: BTreeSet::from([Operation::AppRelay]),
        });
        assert!(!receipt_matches_payload(&wrong, &payload));
        let right = OperationResult::LogicalOpen(LogicalReceipt {
            channel_id: channel,
            state: "open".into(),
            operations: BTreeSet::from([Operation::AppRelay, Operation::MediaControl]),
        });
        assert!(right.matches(Operation::LogicalOpen, &[0; 32]));
        assert!(receipt_matches_payload(&right, &payload));
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(
            o.clone(),
            BTreeSet::from([Operation::AppRelay, Operation::LogicalOpen]),
            0,
        )
        .unwrap();
        let mut other = owner();
        other.pid += 1;
        m.register(other, BTreeSet::from([Operation::MediaControl]), 0)
            .unwrap();
        let mut work = task("1");
        work.operation = Operation::LogicalOpen;
        work.payload = payload;
        assert!(matches!(m.submit(work, 1), Err("executor_unavailable")));
    }
    #[test]
    fn logical_channels_use_exact_schema_and_bound_remote_receipts() {
        use crate::bridge::LogicalReceipt;
        let channel = "b".repeat(32);
        let payload = json!({"channel_id":channel,"operations":["clipboard_write"]});
        assert!(validate_payload(Operation::LogicalOpen, &payload).is_ok());
        assert_eq!(
            validate_payload(Operation::LogicalClose, &payload),
            Err("invalid_payload")
        );
        assert_eq!(
            validate_payload(
                Operation::LogicalOpen,
                &json!({"channel_id":channel,"operations":["device_profile"]})
            ),
            Err("invalid_payload")
        );
        let mut m = VendorMailbox::default();
        let o = owner();
        assert_eq!(
            m.register(o.clone(), BTreeSet::from([Operation::LogicalOpen]), 0),
            Err("executor_unavailable")
        );
        m.register(
            o.clone(),
            BTreeSet::from([Operation::ClipboardWrite, Operation::LogicalOpen]),
            0,
        )
        .unwrap();
        let mut work = task("1");
        work.operation = Operation::LogicalOpen;
        work.payload = payload;
        let rx = m.submit(work, 1).unwrap();
        m.poll(&o, 2);
        let wrong = OperationResult::LogicalOpen(LogicalReceipt {
            channel_id: "c".repeat(32),
            state: "open".into(),
            operations: BTreeSet::from([Operation::ClipboardWrite]),
        });
        assert_eq!(
            m.complete(&o, &"a".repeat(64), "1", Ok(wrong), 3),
            Err("invalid_result")
        );
        let result = OperationResult::LogicalOpen(LogicalReceipt {
            channel_id: channel,
            state: "open".into(),
            operations: BTreeSet::from([Operation::ClipboardWrite]),
        });
        m.complete(&o, &"a".repeat(64), "1", Ok(result.clone()), 3)
            .unwrap();
        assert_eq!(rx.recv().unwrap(), Ok(result));
    }
    #[test]
    fn numeric_request_ids_do_not_reorder_vendor_execution_lexicographically() {
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(o.clone(), BTreeSet::from([Operation::ClipboardWrite]), 0)
            .unwrap();
        let _first = m.submit(task("9"), 1).unwrap();
        let _second = m.submit(task("10"), 2).unwrap();
        assert_eq!(m.poll(&o, 3).0.unwrap().request_id, "9");
        assert_eq!(m.poll(&o, 4).0.unwrap().request_id, "10");
    }
    #[test]
    fn authenticated_same_process_lease_renewal_keeps_executor_and_rejects_old_lease() {
        let mut m = VendorMailbox::default();
        let first = owner();
        m.register(
            first.clone(),
            BTreeSet::from([Operation::ClipboardWrite]),
            0,
        )
        .unwrap();
        let rx = m.submit(task("1"), 1).unwrap();
        m.poll(&first, 2);
        let mut renewed = first.clone();
        renewed.token = "new-authenticated-lease".into();
        m.register(
            renewed.clone(),
            BTreeSet::from([Operation::ClipboardWrite]),
            3,
        )
        .unwrap();
        assert_eq!(m.operations(4), BTreeSet::from([Operation::ClipboardWrite]));
        assert_eq!(
            m.complete(&first, &"a".repeat(64), "1", Ok(receipt()), 4),
            Err("forbidden")
        );
        m.complete(&renewed, &"a".repeat(64), "1", Ok(receipt()), 4)
            .unwrap();
        assert_eq!(rx.recv().unwrap(), Ok(receipt()));
    }
    #[test]
    fn finder_is_typed_bounded_and_keeps_real_playback_results() {
        use crate::bridge::FinderReceipt;
        for payload in [
            json!({"action":"start"}),
            json!({"action":"start","duration_ms":30000}),
            json!({"action":"stop"}),
            json!({"action":"state"}),
        ] {
            assert!(validate_payload(Operation::DeviceFinder, &payload).is_ok());
        }
        for payload in [
            json!({"action":"start","duration_ms":0}),
            json!({"action":"start","duration_ms":30001}),
            json!({"action":"start","duration_ms":true}),
            json!({"action":"stop","duration_ms":1}),
            json!({"action":"start","device_name":"untrusted"}),
        ] {
            assert_eq!(
                validate_payload(Operation::DeviceFinder, &payload),
                Err("invalid_payload")
            );
        }
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(o.clone(), BTreeSet::from([Operation::DeviceFinder]), 0)
            .unwrap();
        let mut work = task("1");
        work.operation = Operation::DeviceFinder;
        work.payload = json!({"action":"state"});
        let rx = m.submit(work, 1).unwrap();
        m.poll(&o, 2);
        let bad = OperationResult::DeviceFinder(FinderReceipt {
            ringing: false,
            remaining_ms: 10000,
            reason: "stopped".into(),
            replayed: false,
        });
        assert_eq!(
            m.complete(&o, &"a".repeat(64), "1", Ok(bad), 3),
            Err("invalid_result")
        );
        let stopped = OperationResult::DeviceFinder(FinderReceipt {
            ringing: false,
            remaining_ms: 0,
            reason: "stopped".into(),
            replayed: false,
        });
        m.complete(&o, &"a".repeat(64), "1", Ok(stopped.clone()), 3)
            .unwrap();
        assert_eq!(rx.recv().unwrap(), Ok(stopped));
    }
    #[test]
    fn canonical_payload_boundary_rejects_aliases_invalid_utf8_and_wrong_fields() {
        for encoded in ["aGk=", "", "AA==", "5Lit5paH"] {
            assert!(validate_payload(
                Operation::ClipboardWrite,
                &json!({"mime":"text/plain","utf8_b64":encoded})
            )
            .is_ok());
        }
        for encoded in ["aGk", "aGl=", "AB==", "aGk=AAAA", "_w==", "/w==", "aGk=\n"] {
            assert_eq!(
                validate_payload(
                    Operation::ClipboardWrite,
                    &json!({"mime":"text/plain","utf8_b64":encoded})
                ),
                Err("invalid_payload")
            );
        }
        let boundary = "AAAA".repeat(1365) + "AA==";
        assert!(validate_payload(
            Operation::ClipboardWrite,
            &json!({"mime":"text/plain","utf8_b64":boundary})
        )
        .is_ok());
        assert_eq!(
            validate_payload(
                Operation::ClipboardWrite,
                &json!({"mime":"text/plain","utf8_b64":"AAAA".repeat(1366)})
            ),
            Err("invalid_payload")
        );
        assert_eq!(
            validate_payload(
                Operation::ClipboardWrite,
                &json!({"mime":"text/plain","utf8_b64":"aGk=","text":"hi"})
            ),
            Err("invalid_payload")
        );
    }
    #[test]
    fn mailbox_binds_owner_request_result_and_actual_byte_count() {
        let mut m = VendorMailbox::default();
        let o = owner();
        assert!(matches!(
            m.submit(task("1"), 0),
            Err("executor_unavailable")
        ));
        m.register(o.clone(), BTreeSet::from([Operation::ClipboardWrite]), 0)
            .unwrap();
        let mut foreign = o.clone();
        foreign.pid += 1;
        assert_eq!(
            m.register(
                foreign.clone(),
                BTreeSet::from([Operation::ClipboardWrite]),
                1
            ),
            Err("executor_busy")
        );
        let rx = m.submit(task("1"), 1).unwrap();
        assert!(m.poll(&foreign, 2).0.is_none());
        assert!(m.poll(&o, 2).0.is_some());
        assert!(m.poll(&o, 3).0.is_none());
        assert_eq!(
            m.complete(&foreign, &"a".repeat(64), "1", Ok(receipt()), 4),
            Err("forbidden")
        );
        assert_eq!(
            m.complete(
                &o,
                &"a".repeat(64),
                "1",
                Ok(OperationResult::ClipboardWrite(ClipboardReceipt {
                    applied: true,
                    utf8_bytes: 1
                })),
                4
            ),
            Err("invalid_result")
        );
        m.complete(&o, &"a".repeat(64), "1", Ok(receipt()), 4)
            .unwrap();
        assert_eq!(rx.recv().unwrap(), Ok(receipt()));
        assert_eq!(
            m.complete(&o, &"a".repeat(64), "1", Ok(receipt()), 5),
            Err("stale_task")
        );
    }
    #[test]
    fn cancel_before_delivery_prevents_execution_after_delivery_waits_for_real_result() {
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(o.clone(), BTreeSet::from([Operation::ClipboardWrite]), 0)
            .unwrap();
        let first = m.submit(task("1"), 1).unwrap();
        m.cancel(&"a".repeat(64), "1");
        assert_eq!(first.recv().unwrap(), Err("cancelled"));
        assert!(m.poll(&o, 2).0.is_none());
        let second = m.submit(task("2"), 2).unwrap();
        m.poll(&o, 3);
        m.cancel(&"a".repeat(64), "2");
        assert!(matches!(second.try_recv(), Err(mpsc::TryRecvError::Empty)));
        assert_eq!(m.poll(&o, 4).1, vec![("a".repeat(64), "2".into())]);
        m.complete(&o, &"a".repeat(64), "2", Ok(receipt()), 5)
            .unwrap();
        assert_eq!(second.recv().unwrap(), Ok(receipt()));
    }
    #[test]
    fn expiration_disconnect_and_capacity_remain_bounded() {
        let mut m = VendorMailbox::default();
        let o = owner();
        m.register(o.clone(), BTreeSet::from([Operation::ClipboardWrite]), 0)
            .unwrap();
        let first = m.submit(task("1"), 1).unwrap();
        m.poll(&o, 2);
        m.invalidate();
        assert_eq!(first.recv().unwrap(), Err("session_expired"));
        assert_eq!(
            m.complete(&o, &"a".repeat(64), "1", Ok(receipt()), 3),
            Err("stale_task")
        );
        let receivers = (0..32)
            .map(|i| m.submit(task(&i.to_string()), 4).unwrap())
            .collect::<Vec<_>>();
        assert!(matches!(m.submit(task("overflow"), 5), Err("busy")));
        assert!(m.operations(4003).is_empty());
        for rx in receivers {
            assert_eq!(rx.recv().unwrap(), Err("executor_unavailable"));
        }
    }
}
