//! Content-free native call/SMS status schemas. Installing this module does not publish capability.
use serde::Deserialize;
use serde_json::Value;

pub const CALL_STATE: &str = "telephony_call_state";
pub const CALL_CONTROL: &str = "telephony_call_control";
pub const SMS_STATUS: &str = "telephony_sms_status";
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum State { Ringing, Active, Holding, Disconnecting, Ended }
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum Action { Answer, Reject, End, Hold, Unhold }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CallState { call_id: String, revision: i64, state: State }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CallControl { call_id: String, revision: i64, action: Action }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SmsStatus { message_id: String, revision: i64, status: i32 }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct StateReceipt { call_id: String, revision: i64, effect: Effect }
#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum Effect { Created, Applied, Ended }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ControlReceipt { call_id: String, revision: i64, state: State, observed: bool }
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SmsReceipt { message_id: String, revision: i64, status: i32, applied: bool }
fn call(value: &str) -> bool {
    !value.is_empty() && value.len() <= 96 && value.bytes().all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
}
fn message(value: &str) -> bool {
    value.len() == 32 && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn terminal(value: i32) -> bool { matches!(value, 1 | 8 | 9) }
fn bounded(raw: &Value) -> bool { serde_json::to_vec(raw).is_ok_and(|v| v.len() <= 512) }
pub fn valid_payload(operation: &str, raw: &Value) -> bool {
    if !bounded(raw) { return false; }
    match operation {
        CALL_STATE => serde_json::from_value::<CallState>(raw.clone()).is_ok_and(|s| call(&s.call_id) && s.revision > 0),
        CALL_CONTROL => serde_json::from_value::<CallControl>(raw.clone()).is_ok_and(|s| call(&s.call_id) && s.revision > 0),
        SMS_STATUS => serde_json::from_value::<SmsStatus>(raw.clone()).is_ok_and(|s| message(&s.message_id) && s.revision > 0 && terminal(s.status)),
        _ => false,
    }
}
/// Validate the native receipt against the original request, not merely its shape.
pub fn valid_result(operation: &str, request: &Value, result: &Value) -> bool {
    if !valid_payload(operation, request) || !bounded(result) { return false; }
    match operation {
        CALL_STATE => {
            let Ok(source) = serde_json::from_value::<CallState>(request.clone()) else { return false; };
            serde_json::from_value::<StateReceipt>(result.clone()).is_ok_and(|r|
                r.call_id == source.call_id && r.revision == source.revision
                && matches!(r.effect, Effect::Ended) == (source.state == State::Ended))
        }
        CALL_CONTROL => {
            let Ok(source) = serde_json::from_value::<CallControl>(request.clone()) else { return false; };
            serde_json::from_value::<ControlReceipt>(result.clone()).is_ok_and(|r|
                r.observed && r.call_id == source.call_id && r.revision > source.revision
                && match source.action { Action::Answer | Action::Unhold => r.state == State::Active,
                    Action::End | Action::Reject => r.state == State::Ended, Action::Hold => r.state == State::Holding })
        }
        SMS_STATUS => {
            let Ok(source) = serde_json::from_value::<SmsStatus>(request.clone()) else { return false; };
            serde_json::from_value::<SmsReceipt>(result.clone()).is_ok_and(|r|
                r.applied && r.message_id == source.message_id && r.revision > source.revision && r.status == source.status)
        }
        _ => false,
    }
}
pub const EXECUTION_ERRORS: &[&str] = &[
    "session_invalid", "permission_denied", "permission_revoked", "deadline_expired", "invalid_payload", "invalid_snapshot",
    "invalid_result", "request_conflict", "request_capacity", "call_capacity", "call_missing", "call_ended", "call_busy",
    "stale_call", "invalid_call_state", "native_failed", "native_failure", "native_mismatch", "outcome_unknown", "cancelled",
    "state_superseded", "revision_conflict", "initial_state_invalid", "relay_busy", "creation_timeout", "native_lost",
    "native_cleanup_failed", "session_withdrawn", "sms_missing", "stale_message", "invalid_message_state", "sms_busy",
    "remote_error", "native_unavailable",
];

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn exact_call_shapes_and_states() {
        for state in ["ringing", "active", "holding", "disconnecting", "ended"] {
            assert!(valid_payload(CALL_STATE, &json!({"call_id":"call_test","revision":1,"state":state})));
        }
        for action in ["answer", "reject", "end", "hold", "unhold"] {
            assert!(valid_payload(CALL_CONTROL, &json!({"call_id":"call_test","revision":1,"action":action})));
        }
    }
    #[test]
    fn no_phone_number_dial_or_unknown_state() {
        assert!(!valid_payload(CALL_CONTROL, &json!({"call_id":"call_test","revision":1,"action":"dial"})));
        assert!(!valid_payload(CALL_STATE, &json!({"call_id":"call_test","revision":1,"state":"dialing"})));
        assert!(!valid_payload(CALL_STATE, &json!({"call_id":"call_test","revision":1,"state":"ringing","number":"synthetic"})));
        assert!(!valid_payload("telephony_arbitrary", &json!({})));
    }
    #[test]
    fn revisions_must_be_positive_exact_signed_integers() {
        for revision in [json!(0), json!(-1), json!(1.0), json!("1"), json!(true), json!(u64::MAX)] {
            assert!(!valid_payload(CALL_STATE, &json!({"call_id":"call_test","revision":revision,"state":"ringing"})));
        }
        assert!(valid_payload(CALL_STATE, &json!({"call_id":"call_test","revision":i64::MAX,"state":"ended"})));
    }
    #[test]
    fn opaque_id_and_size_limits() {
        for id in ["", "bad id", "call/../../", "号码"] {
            assert!(!valid_payload(CALL_STATE, &json!({"call_id":id,"revision":1,"state":"ringing"})));
        }
        assert!(!valid_payload(CALL_STATE, &json!({"call_id":"x".repeat(97),"revision":1,"state":"ringing"})));
        assert!(valid_payload(CALL_STATE, &json!({"call_id":"x".repeat(96),"revision":1,"state":"ringing"})));
    }
    #[test]
    fn state_receipt_binds_request_revision_and_terminal_effect() {
        let source = json!({"call_id":"call_test","revision":2,"state":"active"});
        for effect in ["created", "applied"] { assert!(valid_result(CALL_STATE, &source, &json!({"call_id":"call_test","revision":2,"effect":effect}))); }
        for bad in [json!({"call_id":"other","revision":2,"effect":"applied"}), json!({"call_id":"call_test","revision":1,"effect":"applied"}),
            json!({"call_id":"call_test","revision":2,"effect":"ended"}), json!({"call_id":"call_test","revision":2,"effect":"queued"})] {
            assert!(!valid_result(CALL_STATE, &source, &bad));
        }
        assert!(valid_result(CALL_STATE, &json!({"call_id":"call_test","revision":3,"state":"ended"}), &json!({"call_id":"call_test","revision":3,"effect":"ended"})));
    }
    #[test]
    fn control_receipt_requires_observation_and_correct_target_state() {
        for (action, state) in [("answer","active"),("reject","ended"),("end","ended"),("hold","holding"),("unhold","active")] {
            let source = json!({"call_id":"call_test","revision":1,"action":action});
            assert!(valid_result(CALL_CONTROL, &source, &json!({"call_id":"call_test","revision":2,"state":state,"observed":true})));
            for (revision, observed, wrong) in [(1,true,state),(2,false,state),(2,true,"ringing")] {
                assert!(!valid_result(CALL_CONTROL, &source, &json!({"call_id":"call_test","revision":revision,"state":wrong,"observed":observed})));
            }
        }
    }
    #[test]
    fn sms_is_exact_native_status_only_not_send_retry_delivery_or_sql() {
        for status in [1,8,9] {
            let source = json!({"message_id":"d".repeat(32),"revision":1,"status":status});
            assert!(valid_payload(SMS_STATUS, &source));
            assert!(valid_result(SMS_STATUS, &source, &json!({"message_id":"d".repeat(32),"revision":2,"status":status,"applied":true})));
        }
        for status in [0,2,3,4,5,6,7,10] { assert!(!valid_payload(SMS_STATUS, &json!({"message_id":"d".repeat(32),"revision":1,"status":status}))); }
        assert!(!valid_payload(SMS_STATUS, &json!({"message_id":"d".repeat(32),"revision":1,"status":1,"identifier":"native row"})));
    }
    #[test]
    fn sms_receipt_cannot_ack_another_message_or_status() {
        let source = json!({"message_id":"d".repeat(32),"revision":1,"status":1});
        for bad in [json!({"message_id":"e".repeat(32),"revision":2,"status":1,"applied":true}),
            json!({"message_id":"d".repeat(32),"revision":1,"status":1,"applied":true}),
            json!({"message_id":"d".repeat(32),"revision":2,"status":8,"applied":true}),
            json!({"message_id":"d".repeat(32),"revision":2,"status":1,"applied":false})] { assert!(!valid_result(SMS_STATUS,&source,&bad)); }
    }
}
