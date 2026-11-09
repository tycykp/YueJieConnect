//! Input has its own bilateral grant, and is further scoped to a live rendered stream by Android.
use serde::Deserialize;
use serde_json::Value;
#[derive(Deserialize)]
#[serde(tag="kind", rename_all="snake_case", deny_unknown_fields)]
enum Command {
    Pointer { stream_id: i64, sequence: i64, action: u32, x: f32, y: f32, #[serde(default)] mouse: bool },
    Key { stream_id: i64, sequence: i64, code: u32, down: bool },
    Cancel { stream_id: i64, sequence: i64 },
}
pub fn valid(value: &Value) -> bool {
    match serde_json::from_value::<Command>(value.clone()) {
        Ok(Command::Pointer{stream_id,sequence,action,x,y,mouse}) => {let _=mouse;stream_id>0 && sequence>0 && action<=3 && x.is_finite() && y.is_finite() && (0.0..=1.0).contains(&x) && (0.0..=1.0).contains(&y)},
        Ok(Command::Key{stream_id,sequence,code,down}) => { let _=down;stream_id>0 && sequence>0 && (1..=337).contains(&code) },
        Ok(Command::Cancel{stream_id,sequence}) => stream_id>0 && sequence>0,
        _ => false,
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt { stream_id: i64, sequence: i64, accepted: bool }
pub fn receipt(value: &Value) -> bool {
    serde_json::from_value::<Receipt>(value.clone()).is_ok_and(|r| r.stream_id > 0 && r.sequence > 0 && r.accepted)
}
pub fn matches(payload: &Value, result: &Value) -> bool {
    valid(payload) && receipt(result) && payload["stream_id"] == result["stream_id"] && payload["sequence"] == result["sequence"]
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test] fn validates_input_and_exact_receipts() {
        let p=json!({"stream_id":7,"sequence":2,"kind":"pointer","action":0,"x":0.5,"y":1.0});
        assert!(valid(&p));
        let mut mouse=p.clone();mouse["mouse"]=json!(true);assert!(valid(&mouse));mouse["mouse"]=json!(1);assert!(!valid(&mouse));
        assert!(matches(&p,&json!({"stream_id":7,"sequence":2,"accepted":true})));
        assert!(!matches(&p,&json!({"stream_id":7,"sequence":1,"accepted":true})));
        for patch in [json!({"action":5}),json!({"x":1.1}),json!({"sequence":0}),json!({"stream_id":7.5}),json!({"extra":1})] {
            let mut v=p.clone();v.as_object_mut().unwrap().extend(patch.as_object().unwrap().clone());assert!(!valid(&v),"{v}");
        }
        assert!(valid(&json!({"stream_id":7,"sequence":3,"kind":"key","code":4,"down":true})));
        assert!(valid(&json!({"stream_id":7,"sequence":4,"kind":"cancel"})));
    }
}
