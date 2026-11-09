use serde::Deserialize;
use serde_json::Value;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request { stream_id: i64 }
pub fn request(value: &Value) -> bool {
    serde_json::from_value::<Request>(value.clone()).is_ok_and(|r| r.stream_id > 0)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RequestReceipt { stream_id: i64, accepted: bool }
pub fn request_receipt(value: &Value) -> bool {
    serde_json::from_value::<RequestReceipt>(value.clone()).is_ok_and(|r| r.stream_id > 0 && r.accepted)
}
#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Allowed { stream_id: i64, width: u32, height: u32, fps: u32, bitrate: u32 },
    Prepared {
        stream_id: i64,
        width: u32,
        height: u32,
        fps: u32,
        bitrate: u32,
    },
    Rendered {
        stream_id: i64,
        sequence: i64,
        presentation_time_us: i64,
    },
    Keyframe {
        stream_id: i64,
    },
    Stop {
        stream_id: i64,
    },
    AudioReady {
        stream_id: i64,
    },
    AudioStop {
        stream_id: i64,
    },
}
pub fn valid(payload: &Value) -> bool {
    match serde_json::from_value::<Command>(payload.clone()) {
        Ok(Command::Allowed { stream_id, width, height, fps, bitrate }) | Ok(Command::Prepared {
            stream_id,
            width,
            height,
            fps,
            bitrate,
        }) => {
            stream_id > 0
                && (16..=4096).contains(&width)
                && (16..=4096).contains(&height)
                && width % 2 == 0
                && height % 2 == 0
                && (width as u64) * (height as u64) <= 8_847_360
                && (1..=60).contains(&fps)
                && (64_000..=30_000_000).contains(&bitrate)
        }
        Ok(Command::Rendered {
            stream_id,
            sequence,
            presentation_time_us,
        }) => stream_id > 0 && sequence > 0 && presentation_time_us >= 0,
        Ok(Command::Keyframe { stream_id } | Command::Stop { stream_id }
            | Command::AudioReady { stream_id } | Command::AudioStop { stream_id }) => stream_id > 0,
        _ => false,
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt {
    stream_id: i64,
    action: String,
    accepted: bool,
}
pub fn receipt(value: &Value) -> bool {
    serde_json::from_value::<Receipt>(value.clone()).is_ok_and(|r| {
        r.stream_id > 0
            && r.accepted
            && matches!(
                r.action.as_str(),
                "allowed" | "prepared" | "rendered" | "keyframe" | "stop" | "audio_ready" | "audio_stop"
            )
    })
}
pub fn matches(payload: &Value, result: &Value) -> bool {
    valid(payload)
        && receipt(result)
        && payload["stream_id"] == result["stream_id"]
        && payload["action"] == result["action"]
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn requests_do_not_grant_capture_or_accept_untyped_streams() {
        assert!(request(&json!({"stream_id":1})));
        for value in [json!({"stream_id":0}),json!({"stream_id":-1}),json!({"stream_id":1.5}),json!({"stream_id":1,"allowed":true})] {
            assert!(!request(&value));
        }
        assert!(request_receipt(&json!({"stream_id":1,"accepted":true})));
        assert!(!request_receipt(&json!({"stream_id":1,"accepted":false})));
        assert!(!request_receipt(&json!({"stream_id":1,"accepted":true,"rendered":true})));
    }
    #[test]
    fn formats_are_exact_and_control_receipts_do_not_claim_a_render() {
        let prepared = json!({"action":"prepared","stream_id":7,"width":1080,"height":1920,"fps":30,"bitrate":4_000_000});
        assert!(valid(&prepared));
        for patch in [
            json!({"width":1081}),
            json!({"stream_id":7.0}),
            json!({"fps":61}),
            json!({"extra":true}),
        ] {
            let mut invalid = prepared.clone();
            invalid
                .as_object_mut()
                .unwrap()
                .extend(patch.as_object().unwrap().clone());
            assert!(!valid(&invalid));
        }
        let audio = json!({"action":"audio_ready","stream_id":7});
        assert!(matches(&audio, &json!({"action":"audio_ready","stream_id":7,"accepted":true})));
        assert!(!valid(&json!({"action":"audio_ready","stream_id":7,"microphone":true})));
        let stop = json!({"action":"audio_stop","stream_id":7});
        assert!(matches(&stop, &json!({"action":"audio_stop","stream_id":7,"accepted":true})));
        assert!(!matches(&stop, &json!({"action":"stop","stream_id":7,"accepted":true})));
        let receipt = json!({"stream_id":7,"action":"prepared","accepted":true});
        assert!(matches(&prepared, &receipt));
        assert!(!matches(
            &prepared,
            &json!({"stream_id":7,"action":"rendered","accepted":true})
        ));
        assert!(!valid(
            &json!({"stream_id":7,"action":"rendered","sequence":0,"presentation_time_us":10})
        ));
        assert!(!valid(&json!({"stream_id":7,"action":"stop","width":1080})));
    }
}
