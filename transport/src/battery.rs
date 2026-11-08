//! Battery level, power source and actual Android charging state.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BatteryStatus {
    pub level: u8,
    /// Android BatteryManager EXTRA_PLUGGED, not a Boolean charging flag.
    pub plugged: u8,
    /// None for older peers or an unknown Android status; plugged alone is not charging.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub charging: Option<bool>,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn charging_survives_wire_and_does_not_follow_usb_power() {
        for charging in [true, false] {
            let value = BatteryStatus { level: 65, plugged: 2, charging: Some(charging) };
            value.validate().unwrap();
            let wire = serde_json::to_value(&value).unwrap();
            assert_eq!(wire["charging"], charging);
            assert_eq!(serde_json::from_value::<BatteryStatus>(wire).unwrap(), value);
        }
        let old: BatteryStatus = serde_json::from_str(r#"{"level":65,"plugged":2}"#).unwrap();
        assert_eq!(old.charging, None);
        assert!(serde_json::to_value(old).unwrap().get("charging").is_none());
    }
}

impl BatteryStatus {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.level > 100 || !matches!(self.plugged, 0 | 1 | 2 | 4 | 8) {
            Err("invalid_battery_status")
        } else {
            Ok(())
        }
    }
}
