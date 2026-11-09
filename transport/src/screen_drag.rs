use serde::Deserialize;
use serde_json::Value;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Drag { stream_id: i64, drag_id: String, action: String, transfers: Vec<String> }
fn token(s: &str) -> bool { s.len()==32 && s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)) }
pub fn valid(v: &Value) -> bool {
    serde_json::from_value::<Drag>(v.clone()).is_ok_and(|d| d.stream_id>0 && token(&d.drag_id)
        && match d.action.as_str() {
            "start" | "drop" => !d.transfers.is_empty() && d.transfers.len()<=4 && d.transfers.iter().all(|t| token(t))
                && d.transfers.iter().collect::<std::collections::BTreeSet<_>>().len()==d.transfers.len(),
            "cancel" | "finish" => d.transfers.is_empty(), _ => false })
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt { stream_id:i64, drag_id:String, action:String, accepted:bool }
pub fn receipt(v:&Value)->bool { serde_json::from_value::<Receipt>(v.clone()).is_ok_and(|r|
    r.stream_id>0 && token(&r.drag_id) && r.accepted && matches!(r.action.as_str(),"start"|"drop"|"cancel"|"finish")) }
pub fn matches(p:&Value,r:&Value)->bool {valid(p)&&receipt(r)&&p["stream_id"]==r["stream_id"]&&p["drag_id"]==r["drag_id"]&&p["action"]==r["action"]}
#[cfg(test)] mod tests {
 use super::*; use serde_json::json;
 #[test] fn typed_bounded_and_exact_receipt(){
 let p=json!({"stream_id":1,"drag_id":"a".repeat(32),"action":"drop","transfers":["b".repeat(32)]}); assert!(valid(&p));
 let mut r=json!({"stream_id":1,"drag_id":"a".repeat(32),"action":"drop","accepted":true});assert!(matches(&p,&r));r["stream_id"]=json!(2);assert!(!matches(&p,&r));
 let mut bad=p.clone();bad["transfers"]=json!(["b".repeat(32),"b".repeat(32)]);assert!(!valid(&bad));bad=p.clone();bad["uri"]=json!("content://foreign/path");assert!(!valid(&bad));
 }
}
