//! The only dynamic device operation currently implemented by the bridge.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BatteryStatus {
    pub level: u8,
    /// Android BatteryManager EXTRA_PLUGGED, not a Boolean charging flag.
    pub plugged: u8,
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
