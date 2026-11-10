//! Closed media-control JSON schema. This module grants no cast, process or device permission.
//! Shared routing must additionally bind the authenticated peer/session and actual rendered cast.
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

fn present<'de, D: Deserializer<'de>, T: Deserialize<'de>>(
    deserializer: D,
) -> Result<Option<T>, D::Error> {
    // Absence is allowed by serde(default); a present JSON null is never an absent field.
    T::deserialize(deserializer).map(Some)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Action {
    Query,
    Play,
    Pause,
    Stop,
    Toggle,
    Next,
    Previous,
    FastForward,
    Rewind,
    SeekForward,
    SeekBackward,
    Seek,
    VolumeQuery,
    SetVolume,
    RestoreVolume,
}
impl Action {
    pub fn media(self) -> bool {
        !matches!(
            self,
            Self::VolumeQuery | Self::SetVolume | Self::RestoreVolume
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Control {
    pub action: Action,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub package: Option<String>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub media_token: Option<String>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub value: Option<i64>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub expected_volume: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub cast_stream_id: i64,
    pub cast_screen_id: i32,
    pub control: Control,
}

fn package(value: &str) -> bool {
    value.len() <= 255
        && value.contains('.')
        && value.split('.').all(|part| {
            !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
}
fn token(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if [8, 13, 18, 23].contains(&index) {
                byte == b'-'
            } else {
                byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)
            }
        })
}
fn bounded(value: &Value) -> bool {
    // Java's JSONObject budget is measured in UTF-16 code units, not Unicode scalar values.
    serde_json::to_string(value).is_ok_and(|s| s.encode_utf16().count() <= 2048)
}

impl Control {
    fn valid(&self) -> bool {
        let fields = (
            self.package.is_some(),
            self.media_token.is_some(),
            self.value.is_some(),
            self.expected_volume.is_some(),
        );
        let expected = match self.action {
            Action::Query => (true, false, false, false),
            Action::Seek => (true, true, true, false),
            Action::VolumeQuery => (false, false, false, false),
            Action::SetVolume => (false, false, true, self.expected_volume.is_some()),
            Action::RestoreVolume => (false, false, true, true),
            _ => (true, true, false, false),
        };
        if fields != expected
            || self.package.as_ref().is_some_and(|s| !package(s))
            || self.media_token.as_ref().is_some_and(|s| !token(s))
            || self
                .expected_volume
                .is_some_and(|v| !(0..=10_000).contains(&v))
        {
            return false;
        }
        match self.action {
            Action::Seek => self.value.is_some_and(|v| (0..=604_800_000).contains(&v)),
            Action::SetVolume | Action::RestoreVolume => {
                self.value.is_some_and(|v| (0..=10_000).contains(&v))
            }
            _ => true,
        }
    }
}

pub fn parse(payload: &Value) -> Result<Request, &'static str> {
    if !bounded(payload) {
        return Err("invalid_media_request");
    }
    let request: Request =
        serde_json::from_value(payload.clone()).map_err(|_| "invalid_media_request")?;
    let device_volume = request.cast_stream_id == 0
        && request.cast_screen_id == 0
        && matches!(
            request.control.action,
            Action::VolumeQuery | Action::SetVolume
        );
    if (!device_volume && (request.cast_stream_id <= 0 || request.cast_screen_id < 0))
        || !request.control.valid()
    {
        return Err("invalid_media_request");
    }
    Ok(request)
}

/// Successful endpoint data only. Errors travel in the authenticated shared error envelope;
/// an error or empty state is never allowed to masquerade as a successful native receipt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Receipt {
    pub status: String,
    pub code: String,
    pub dispatched: bool,
    pub state: State,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct State {
    pub media_token: String,
    pub playback: u8,
    pub position_ms: i64,
    pub actions: i64,
    pub volume: u16,
    pub min_volume: u16,
    pub max_volume: u16,
    pub interactive: bool,
    pub power_save: bool,
}

pub fn receipt(data: &Value) -> Result<Receipt, &'static str> {
    if !bounded(data) {
        return Err("invalid_result");
    }
    let result: Receipt = serde_json::from_value(data.clone()).map_err(|_| "invalid_result")?;
    let state = &result.state;
    if result.status != "ok"
        || result.code != "ok"
        || (!state.media_token.is_empty() && !token(&state.media_token))
        || state.playback > 11
        || state.position_ms < -1
        || state.actions < 0
        || state.min_volume > state.volume
        || state.volume > state.max_volume
        || state.max_volume > 10_000
    {
        return Err("invalid_result");
    }
    Ok(result)
}

impl Request {
    /// Additional postconditions bound to the ORIGINAL request. Native dispatch/readback and
    /// queue-neighbor checks still happen at the endpoint; transport cannot invent those facts.
    pub fn matches(&self, result: &Receipt) -> bool {
        let control = &self.control;
        let state = &result.state;
        if control.action.media() {
            if !token(&state.media_token)
                || control
                    .media_token
                    .as_ref()
                    .is_some_and(|expected| expected != &state.media_token)
            {
                return false;
            }
        } else if !state.media_token.is_empty()
            || state.playback != 0
            || state.position_ms != -1
            || state.actions != 0
        {
            return false;
        }
        match control.action {
            Action::Query | Action::VolumeQuery => !result.dispatched,
            Action::Play => state.playback == 3,
            Action::Pause => state.playback == 2,
            Action::Stop => state.playback == 1,
            Action::Toggle => matches!(state.playback, 2 | 3),
            Action::FastForward => state.playback == 4,
            Action::Rewind => state.playback == 5,
            Action::Seek => control.value.is_some_and(|value| {
                state.position_ms >= 0 && state.position_ms.abs_diff(value) <= 1000
            }),
            Action::SeekForward | Action::SeekBackward => state.position_ms >= 0,
            Action::Next | Action::Previous => matches!(state.playback, 1..=3),
            Action::SetVolume | Action::RestoreVolume => {
                control.value == Some(i64::from(state.volume))
            }
        }
    }
}

pub fn valid_result(payload: &Value, data: &Value) -> bool {
    parse(payload).is_ok_and(|request| receipt(data).is_ok_and(|result| request.matches(&result)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const TOKEN: &str = "12345678-1234-1234-1234-123456789abc";
    fn request(control: Value) -> Value {
        json!({"cast_stream_id":7,"cast_screen_id":0,"control":control})
    }
    fn result(media: bool, playback: u8, volume: u16) -> Value {
        json!({"status":"ok","code":"ok","dispatched":false,"state":{
            "media_token":if media {TOKEN} else {""},"playback":playback,
            "position_ms":if media {5000} else {-1},"actions":if media {511} else {0},
            "volume":volume,"min_volume":0,"max_volume":15,"interactive":true,"power_save":false}})
    }
    #[test]
    fn accepts_exact_java_action_fields() {
        assert!(parse(&request(
            json!({"action":"QUERY","package":"test.synthetic.player"})
        ))
        .is_ok());
        for action in [
            "PLAY",
            "PAUSE",
            "STOP",
            "TOGGLE",
            "NEXT",
            "PREVIOUS",
            "FAST_FORWARD",
            "REWIND",
            "SEEK_FORWARD",
            "SEEK_BACKWARD",
        ] {
            assert!(
                parse(&request(
                    json!({"action":action,"package":"test.synthetic.player","media_token":TOKEN})
                ))
                .is_ok(),
                "{action}"
            );
        }
        assert!(parse(&request(json!({"action":"SEEK","package":"test.synthetic.player","media_token":TOKEN,"value":604800000}))).is_ok());
        for control in [
            json!({"action":"VOLUME_QUERY"}),
            json!({"action":"SET_VOLUME","value":15}),
            json!({"action":"SET_VOLUME","value":15,"expected_volume":14}),
            json!({"action":"RESTORE_VOLUME","value":14,"expected_volume":15}),
        ] {
            assert!(parse(&request(control)).is_ok());
        }
    }
    #[test]
    fn null_unrelated_and_extra_fields_are_not_defaults() {
        for control in [
            json!({"action":"VOLUME_QUERY","package":null}),
            json!({"action":"VOLUME_QUERY","package":"test.player"}),
            json!({"action":"SET_VOLUME","value":1,"expected_volume":null}),
            json!({"action":"SET_VOLUME","value":1,"extra":0}),
            json!({"action":"PLAY","package":"test.player","media_token":TOKEN,"value":0}),
            json!({"action":"QUERY","package":"test.player","media_token":TOKEN}),
        ] {
            assert!(parse(&request(control)).is_err());
        }
        let mut extra = request(json!({"action":"VOLUME_QUERY"}));
        extra["peer"] = "guessed".into();
        assert!(parse(&extra).is_err());
        assert!(parse(&json!({"action":"VOLUME_QUERY"})).is_err());
    }
    #[test]
    fn only_integer_android_ranges_are_accepted() {
        for value in [
            json!(-1),
            json!(1.0),
            json!(true),
            json!("1"),
            json!(u64::MAX),
        ] {
            let mut raw = request(json!({"action":"VOLUME_QUERY"}));
            raw["cast_stream_id"] = value;
            assert!(parse(&raw).is_err());
        }
        let mut maximum = request(json!({"action":"SET_VOLUME","value":10000,"expected_volume":0}));
        maximum["cast_stream_id"] = i64::MAX.into();
        maximum["cast_screen_id"] = i32::MAX.into();
        assert!(parse(&maximum).is_ok());
        maximum["cast_screen_id"] = json!(2147483648_i64);
        assert!(parse(&maximum).is_err());
        for value in [json!(-1), json!(10001), json!(1.0), json!("1"), json!(null)] {
            assert!(parse(&request(json!({"action":"SET_VOLUME","value":value}))).is_err());
        }
        assert!(parse(&request(json!({"action":"RESTORE_VOLUME","value":0}))).is_err());
    }

    #[test]
    fn device_scope_allows_only_volume_query_and_set() {
        let mut raw = request(json!({"action":"VOLUME_QUERY"}));
        raw["cast_stream_id"] = 0.into();
        assert!(parse(&raw).is_ok());
        raw["control"] = json!({"action":"SET_VOLUME","value":4,"expected_volume":3});
        assert!(parse(&raw).is_ok());
        raw["cast_screen_id"] = 1.into();
        assert!(parse(&raw).is_err());
        raw["cast_screen_id"] = 0.into();
        raw["control"] = json!({"action":"QUERY","package":"test.player"});
        assert!(parse(&raw).is_err());
        raw["control"] = json!({"action":"RESTORE_VOLUME","value":4,"expected_volume":3});
        assert!(parse(&raw).is_err());
    }
    #[test]
    fn package_and_token_cannot_be_guessed_or_coerced() {
        for package in [
            "player",
            ".test.player",
            "test..player",
            "test.player-1",
            "测试.player",
        ] {
            assert!(parse(&request(json!({"action":"QUERY","package":package}))).is_err());
        }
        for token in ["", "old-session", "12345678-1234-1234-1234-123456789ABC"] {
            assert!(parse(&request(
                json!({"action":"PLAY","package":"test.player","media_token":token})
            ))
            .is_err());
        }
    }
    #[test]
    fn success_requires_all_nine_actual_state_fields() {
        let good = result(true, 2, 7);
        assert!(receipt(&good).is_ok());
        let fields: Vec<_> = good["state"].as_object().unwrap().keys().cloned().collect();
        assert_eq!(fields.len(), 9);
        for field in fields {
            let mut missing = good.clone();
            missing["state"].as_object_mut().unwrap().remove(&field);
            assert!(receipt(&missing).is_err(), "{field}");
        }
        for bad in [
            json!({"status":"ok","code":"ok","dispatched":false,"state":{}}),
            json!({"status":"error","code":"busy","dispatched":false}),
            json!({"status":"ok","code":"ok","dispatched":true,"state":null}),
        ] {
            assert!(receipt(&bad).is_err());
        }
        let mut private = good.clone();
        private["state"]["title"] = "private".into();
        assert!(receipt(&private).is_err());
    }
    #[test]
    fn result_types_ranges_and_query_dispatch_are_checked() {
        for (field, value) in [
            ("playback", json!(12)),
            ("position_ms", json!(-2)),
            ("actions", json!(-1)),
            ("volume", json!(16)),
            ("max_volume", json!(10001)),
            ("min_volume", json!(8)),
            ("interactive", json!(1)),
            ("power_save", json!("false")),
            ("volume", json!(7.0)),
        ] {
            let mut raw = result(true, 2, 7);
            raw["state"][field] = value;
            assert!(receipt(&raw).is_err(), "{field}");
        }
        let query = request(json!({"action":"QUERY","package":"test.player"}));
        let mut state = result(true, 2, 7);
        assert!(valid_result(&query, &state));
        state["dispatched"] = true.into();
        assert!(!valid_result(&query, &state));
    }
    #[test]
    fn results_stay_bound_to_actual_target_and_requested_effect() {
        let play = request(json!({"action":"PLAY","package":"test.player","media_token":TOKEN}));
        let mut state = result(true, 3, 7);
        state["dispatched"] = true.into();
        assert!(valid_result(&play, &state));
        state["state"]["playback"] = 2.into();
        assert!(!valid_result(&play, &state));
        state["state"]["playback"] = 3.into();
        state["state"]["media_token"] = "22345678-1234-1234-1234-123456789abc".into();
        assert!(!valid_result(&play, &state));
        let volume = request(json!({"action":"SET_VOLUME","value":7,"expected_volume":6}));
        let mut v = result(false, 0, 7);
        assert!(valid_result(&volume, &v));
        v["state"]["volume"] = 8.into();
        assert!(!valid_result(&volume, &v));
        assert!(!valid_result(
            &request(json!({"action":"VOLUME_QUERY"})),
            &result(true, 2, 7)
        ));
    }
    #[test]
    fn receiver_resolved_relative_commands_keep_their_real_semantics() {
        for (action, accepted, rejected) in [
            ("PAUSE", 2, 3),
            ("STOP", 1, 3),
            ("FAST_FORWARD", 4, 3),
            ("REWIND", 5, 3),
            ("NEXT", 3, 6),
            ("PREVIOUS", 2, 10),
            ("TOGGLE", 2, 1),
        ] {
            let raw = request(json!({"action":action,"package":"test.player","media_token":TOKEN}));
            assert!(valid_result(&raw, &result(true, accepted, 7)), "{action}");
            assert!(!valid_result(&raw, &result(true, rejected, 7)), "{action}");
        }
        let seek = request(
            json!({"action":"SEEK","package":"test.player","media_token":TOKEN,"value":5000}),
        );
        let mut state = result(true, 2, 7);
        state["state"]["position_ms"] = 6000.into();
        assert!(valid_result(&seek, &state));
        state["state"]["position_ms"] = 6001.into();
        assert!(!valid_result(&seek, &state));
        state["state"]["position_ms"] = i64::MAX.into();
        assert!(!valid_result(&seek, &state));
    }
}
