//! Bounded network-sharing commands. Hotspot credentials never enter diagnostics.
use serde_json::Value;
fn exact(v: &Value, keys: &[&str]) -> bool {
    v.as_object().is_some_and(|m| m.len() == keys.len() && keys.iter().all(|k| m.contains_key(*k)))
}
fn text<'a>(v: &'a Value, k: &str) -> &'a str { v[k].as_str().unwrap_or("") }
fn share(v: &Value) -> bool {
    let s = text(v, "share_id");
    !s.is_empty() && s.len() <= 128 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b"_:-".contains(&b))
}
fn upstream(v: &Value) -> bool { matches!(text(v,"upstream"), "wifi" | "cellular") }
fn hex(s: &str, n: usize) -> bool { s.len() == n && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }
fn credentials(v: &Value) -> bool {
    text(v,"ssid").strip_prefix("YJ-").is_some_and(|s| hex(s,16)) && hex(text(v,"passphrase"),32)
}
pub fn valid(v: &Value) -> bool {
    match text(v,"action") {
        "availability" | "prepare_control" => exact(v,&["action"]),
        "start" | "join" => {
            let keys: &[&str] = if v["action"] == "start" { &["action","share_id","upstream","allow_metered"] }
                else { &["action","share_id","upstream","allow_metered","ssid","passphrase"] };
            exact(v, keys) && share(v) && upstream(v) && v["allow_metered"].is_boolean()
                && (v["upstream"] != "cellular" || v["allow_metered"] == true)
                && (v["action"] != "join" || credentials(v))
        }
        "confirm" | "renew" | "stop" => exact(v,&["action","share_id"]) && share(v),
        _ => false,
    }
}
pub fn receipt(v: &Value) -> bool {
    if exact(v,&["ticket"]) { return hex(text(v,"ticket"),64); }
    if exact(v,&["provider","consumer","upstream","metered","error"]) {
        return v["provider"].is_boolean() && v["consumer"].is_boolean() && v["metered"].is_boolean()
            && matches!(text(v,"upstream"),"none"|"wifi"|"cellular") && error(v);
    }
    if exact(v,&["share_id","ssid","passphrase","upstream"]) { return share(v) && credentials(v) && upstream(v); }
    exact(v,&["share_id","role","upstream","phase","error"]) && share(v) && upstream(v) && error(v)
        && matches!(text(v,"role"),"provider"|"consumer")
        && matches!(text(v,"phase"),"requested"|"starting"|"ready"|"active"|"stopping"|"stopped"|"failed"|"recovery_required")
}
fn error(v: &Value) -> bool { v["error"].as_str().is_some_and(|s| s.len()<=256 && s.bytes().all(|b| b.is_ascii_lowercase() || b==b'_')) }
pub fn matches(p: &Value, r: &Value) -> bool {
    if !valid(p) || !receipt(r) { return false; }
    match text(p,"action") {
        "prepare_control" => r.get("ticket").is_some(),
        "availability" => r.get("provider").is_some(),
        "start" => r.get("ssid").is_some() && p["share_id"]==r["share_id"] && p["upstream"]==r["upstream"],
        "join" | "confirm" | "renew" => p["share_id"]==r["share_id"] && r["phase"]=="active" && r["error"]==""
            && (p["action"]!="join" || (r["role"]=="consumer" && p["upstream"]==r["upstream"]))
            && (p["action"]!="confirm" || r["role"]=="provider"),
        "stop" => p["share_id"]==r["share_id"] && matches!(text(r,"phase"),"stopped"|"failed"|"recovery_required"),
        _ => false,
    }
}

#[cfg(test)] mod tests {
    use super::*; use serde_json::json;
    #[test] fn control_handover_is_bounded_and_cannot_replace_share_receipts() {
        let p=json!({"action":"prepare_control"});
        let r=json!({"ticket":"ab".repeat(32)});
        assert!(matches(&p,&r));
        assert!(!matches(&json!({"action":"availability"}),&r));
        assert!(!matches(&p,&json!({"ticket":"ab".repeat(31)})));
        assert!(!valid(&json!({"action":"prepare_control","address":"untrusted"})));
    }
    #[test] fn rejects_unconsented_cellular_and_extra_fields() {
        let mut p=json!({"action":"start","share_id":"network:1","upstream":"cellular","allow_metered":false});
        assert!(!valid(&p)); p["allow_metered"]=true.into(); assert!(valid(&p));
        p["password"]="secret".into(); assert!(!valid(&p));
    }
    #[test] fn binds_receipt_to_request_and_role() {
        let p=json!({"action":"confirm","share_id":"network:1"});
        let mut r=json!({"share_id":"network:1","role":"provider","upstream":"wifi","phase":"active","error":""});
        assert!(matches(&p,&r)); r["role"]="consumer".into(); assert!(!matches(&p,&r));
        r["role"]="provider".into(); r["share_id"]="network:2".into(); assert!(!matches(&p,&r));
    }
}
