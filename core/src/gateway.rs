//! Cross-vendor endpoint projection and request routing.
//!
//! A projection is an alias for a real peer, not evidence that OEM authentication
//! or an operation succeeded. This core is not yet wired to Android injection.
use crate::{hex, valid_text, Source};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeviceKind {
    Phone,
    Tablet,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Operation {
    DeviceProfile,
    BatteryStatus,
    FileTransfer,
    Clipboard,
}

/// Metadata received from the real peer's bridge, not inferred from a vendor advertisement.
/// The authenticated transport must bind this profile to the peer key before `publish`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RealDevice {
    pub peer_key: [u8; 32],
    pub platform: Source,
    pub kind: DeviceKind,
    pub name: String,
    pub manufacturer: String,
    pub model: String,
    pub os_build: String,
    pub operations: BTreeSet<Operation>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Projection {
    /// Internal gateway alias. Adapters must validate native ID constraints before injection.
    pub id: String,
    /// Changes on every authenticated connection, even when the stable alias is reused.
    pub session_id: String,
    pub target_sdk: Source,
    pub sdk_device_type: i32,
    /// Real metadata is retained, including the actual manufacturer and model.
    pub real: RealDevice,
    /// Only the intersection of real support, implemented translation, and consent.
    pub operations: BTreeSet<Operation>,
}

/// Trusted input from the transport's authenticated + user-confirmed session boundary.
/// Deliberately not Deserialize and not exposed as an Engine/IPC JSON command.
/// Constructing this type alone performs NO cryptographic authentication.
pub struct ConfirmedLink {
    pub peer_key: [u8; 32],
    pub session_id: [u8; 32],
    pub expires_ms: u64,
    pub granted: BTreeSet<Operation>,
}

struct Binding {
    projection: Projection,
    link: ConfirmedLink,
}

/// A dispatch instruction, not an operation-completed result or a native success callback.
#[derive(Debug, PartialEq, Eq)]
pub struct Dispatch {
    pub peer_key: [u8; 32],
    pub session_id: [u8; 32],
    pub operation: Operation,
    pub request_id: u64,
}

#[derive(Debug, PartialEq, Eq)]
pub enum GatewayError {
    InvalidProfile,
    IdentityMismatch,
    UnsupportedDirection,
    LinkExpired,
    PeerOffline,
    WrongLocalAdapter,
    UnsupportedOperation,
    LimitReached,
    InvalidRequest,
}

#[derive(Default)]
pub struct Gateway {
    bindings: BTreeMap<String, Binding>,
}

impl Gateway {
    /// Publish only after the bridge transport and connection/send interception are ready.
    /// `implemented` must come from the local adapter implementation, never remote claims.
    pub fn publish(
        &mut self,
        local: Source,
        real: RealDevice,
        link: ConfirmedLink,
        implemented: &BTreeSet<Operation>,
        now: u64,
    ) -> Result<Projection, GatewayError> {
        validate(&real)?;
        if real.peer_key != link.peer_key || link.session_id == [0; 32] {
            return Err(GatewayError::IdentityMismatch);
        }
        if local == real.platform {
            return Err(GatewayError::UnsupportedDirection);
        }
        if now >= link.expires_ms {
            return Err(GatewayError::LinkExpired);
        }
        let id = alias(local, &real.peer_key);
        if !self.bindings.contains_key(&id) && self.bindings.len() >= 64 {
            return Err(GatewayError::LimitReached);
        }
        let operations = real
            .operations
            .intersection(implemented)
            .copied()
            .filter(|op| link.granted.contains(op))
            .collect();
        let projection = Projection {
            id: id.clone(),
            session_id: hex(&link.session_id),
            target_sdk: local,
            sdk_device_type: sdk_device_type(local, real.kind),
            real,
            operations,
        };
        self.bindings.insert(
            id,
            Binding {
                projection: projection.clone(),
                link,
            },
        );
        Ok(projection)
    }

    /// Resolve only aliases actually registered in this gateway and bound to this SDK.
    /// Payload conversion, remote execution and success/error callbacks remain separate steps.
    pub fn route(
        &self,
        local: Source,
        projection_id: &str,
        operation: Operation,
        request_id: u64,
        now: u64,
    ) -> Result<Dispatch, GatewayError> {
        if request_id == 0 {
            return Err(GatewayError::InvalidRequest);
        }
        let binding = self
            .bindings
            .get(projection_id)
            .ok_or(GatewayError::PeerOffline)?;
        if binding.projection.target_sdk != local {
            return Err(GatewayError::WrongLocalAdapter);
        }
        if now >= binding.link.expires_ms {
            return Err(GatewayError::LinkExpired);
        }
        if !binding.projection.operations.contains(&operation) {
            return Err(GatewayError::UnsupportedOperation);
        }
        Ok(Dispatch {
            peer_key: binding.link.peer_key,
            session_id: binding.link.session_id,
            operation,
            request_id,
        })
    }

    /// Returns aliases the native adapter must withdraw with the real lost/offline path.
    pub fn disconnect(&mut self, session_id: &[u8; 32]) -> Vec<String> {
        let removed = self
            .bindings
            .iter()
            .filter(|(_, b)| &b.link.session_id == session_id)
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for id in &removed {
            self.bindings.remove(id);
        }
        removed
    }

    pub fn expire(&mut self, now: u64) -> Vec<String> {
        let removed = self
            .bindings
            .iter()
            .filter(|(_, b)| now >= b.link.expires_ms)
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        for id in &removed {
            self.bindings.remove(id);
        }
        removed
    }
}

pub fn validate(real: &RealDevice) -> Result<(), GatewayError> {
    if real.peer_key == [0; 32]
        || !valid_text(&real.name, 128, false)
        || !valid_text(&real.manufacturer, 64, false)
        || !valid_text(&real.model, 128, false)
        || !valid_text(&real.os_build, 192, false)
    {
        return Err(GatewayError::InvalidProfile);
    }
    Ok(())
}

fn alias(target: Source, key: &[u8; 32]) -> String {
    let mut digest = Sha256::new();
    digest.update(b"qiangjian/projection/v1\0");
    digest.update([match target {
        Source::HyperOs4 => 1,
        Source::ColorOs17 => 2,
    }]);
    digest.update(key);
    format!("qj:{}", hex(&digest.finalize()))
}

/// Based on the sampled DeviceTypeV2 and Pantaconnect DeviceType enum, not brand strings.
fn sdk_device_type(target: Source, kind: DeviceKind) -> i32 {
    match (target, kind) {
        (Source::HyperOs4, DeviceKind::Phone) => 1,
        (Source::HyperOs4, DeviceKind::Tablet) => 2,
        (Source::ColorOs17, DeviceKind::Phone) => 8,
        (Source::ColorOs17, DeviceKind::Tablet) => 10,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn ops(values: &[Operation]) -> BTreeSet<Operation> {
        values.iter().copied().collect()
    }
    fn pad() -> RealDevice {
        RealDevice {
            peer_key: [1; 32],
            platform: Source::HyperOs4,
            kind: DeviceKind::Tablet,
            name: "Xiaomi Pad 8 Pro".into(),
            manufacturer: "Xiaomi".into(),
            model: "25091RP04C".into(),
            os_build: "OS4.0.5.0.XPYCNXM".into(),
            operations: ops(&[Operation::DeviceProfile, Operation::FileTransfer]),
        }
    }
    fn link() -> ConfirmedLink {
        ConfirmedLink {
            peer_key: [1; 32],
            session_id: [2; 32],
            expires_ms: 100,
            granted: ops(&[Operation::DeviceProfile, Operation::FileTransfer]),
        }
    }
    #[test]
    fn preserves_real_info_and_routes_alias_to_exact_peer() {
        let mut gateway = Gateway::default();
        let real = pad();
        let p = gateway
            .publish(
                Source::ColorOs17,
                real.clone(),
                link(),
                &ops(&[Operation::DeviceProfile]),
                0,
            )
            .unwrap();
        assert_eq!(p.real, real);
        assert_eq!(p.sdk_device_type, 10);
        assert_eq!(p.operations, ops(&[Operation::DeviceProfile]));
        let dispatch = gateway
            .route(Source::ColorOs17, &p.id, Operation::DeviceProfile, 7, 1)
            .unwrap();
        assert_eq!(dispatch.peer_key, [1; 32]);
        assert_eq!(dispatch.session_id, [2; 32]);
        assert_eq!(dispatch.request_id, 7);
        assert_eq!(
            gateway.route(Source::ColorOs17, &p.id, Operation::FileTransfer, 8, 1),
            Err(GatewayError::UnsupportedOperation)
        );
    }
    #[test]
    fn identity_is_stable_across_rename_but_scoped_to_sdk() {
        let mut gateway = Gateway::default();
        let p = gateway
            .publish(Source::ColorOs17, pad(), link(), &BTreeSet::new(), 0)
            .unwrap();
        let mut renamed = pad();
        renamed.name = "我的真实平板".into();
        let update = gateway
            .publish(Source::ColorOs17, renamed, link(), &BTreeSet::new(), 1)
            .unwrap();
        assert_eq!(p.id, update.id);
        assert_ne!(p.real.name, update.real.name);
        assert_ne!(alias(Source::HyperOs4, &[1; 32]), p.id);
        let mut phone = pad();
        phone.platform = Source::ColorOs17;
        phone.kind = DeviceKind::Phone;
        phone.name = "一加 Ace 6".into();
        phone.manufacturer = "OnePlus".into();
        phone.model = "PLQ110".into();
        let projection = gateway
            .publish(Source::HyperOs4, phone, link(), &BTreeSet::new(), 0)
            .unwrap();
        assert_eq!(projection.sdk_device_type, 1);
        assert_eq!(projection.real.manufacturer, "OnePlus");
    }
    #[test]
    fn unbound_expired_wrong_adapter_and_disconnected_requests_fail() {
        let mut gateway = Gateway::default();
        let p = gateway
            .publish(
                Source::ColorOs17,
                pad(),
                link(),
                &ops(&[Operation::DeviceProfile]),
                0,
            )
            .unwrap();
        assert_eq!(
            gateway.route(Source::HyperOs4, &p.id, Operation::DeviceProfile, 1, 1),
            Err(GatewayError::WrongLocalAdapter)
        );
        assert_eq!(
            gateway.route(
                Source::ColorOs17,
                "qj:forged",
                Operation::DeviceProfile,
                1,
                1
            ),
            Err(GatewayError::PeerOffline)
        );
        assert_eq!(
            gateway.route(Source::ColorOs17, &p.id, Operation::DeviceProfile, 1, 100),
            Err(GatewayError::LinkExpired)
        );
        assert_eq!(gateway.disconnect(&[2; 32]), vec![p.id.clone()]);
        assert_eq!(
            gateway.route(Source::ColorOs17, &p.id, Operation::DeviceProfile, 1, 1),
            Err(GatewayError::PeerOffline)
        );
    }
    #[test]
    fn session_identity_and_consent_are_not_derived_from_metadata() {
        let mut gateway = Gateway::default();
        let mut bad = link();
        bad.peer_key = [9; 32];
        assert_eq!(
            gateway.publish(Source::ColorOs17, pad(), bad, &BTreeSet::new(), 0),
            Err(GatewayError::IdentityMismatch)
        );
        let mut restricted = link();
        restricted.granted.clear();
        let p = gateway
            .publish(
                Source::ColorOs17,
                pad(),
                restricted,
                &ops(&[Operation::DeviceProfile]),
                0,
            )
            .unwrap();
        assert!(p.operations.is_empty());
        assert_eq!(gateway.expire(100), vec![p.id]);
    }
}
