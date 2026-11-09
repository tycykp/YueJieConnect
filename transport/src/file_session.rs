//! Session-owned native file admission. Executor/PID renewal never clears transfer tombstones.
use crate::{
    bridge::OperationResult,
    executor::file_operation,
    file_transfer::{self, Command, Offer},
};
use yj_core::gateway::Operation;
use serde_json::Value;
use std::collections::BTreeMap;
type Result<T> = std::result::Result<T, &'static str>;
struct Incoming {
    uid: u32,
    active: bool,
    offer: Option<Offer>,
    prepare: Value,
    approved: bool,
    opened: bool,
    offset: u64,
    committed: bool,
    failed: bool,
    pending: Option<(String, Command)>,
}
#[derive(Default)]
pub struct FileSession {
    session: String,
    incoming: BTreeMap<String, Incoming>,
    outgoing: BTreeMap<String, Option<u32>>,
}
impl FileSession {
    pub fn scope(&mut self, session: &str) {
        if self.session != session {
            self.incoming.clear();
            self.outgoing.clear();
            self.session = session.into();
        }
    }
    fn active(&self) -> usize {
        self.incoming.values().filter(|v| v.active).count()
            + self.outgoing.values().filter(|v| v.is_some()).count()
    }
    fn reserve(&self) -> Result<()> {
        if self.incoming.len() + self.outgoing.len() >= 256 {
            return Err("file_transfer_limit");
        }
        if self.active() >= 4 {
            return Err("file_busy");
        }
        Ok(())
    }
    pub fn allocate_outgoing(&mut self, uid: u32, id: &str) -> Result<()> {
        if self.outgoing.contains_key(id) {
            return Err("native_file_owner_reused");
        }
        self.reserve()?;
        self.outgoing.insert(id.into(), Some(uid));
        Ok(())
    }
    pub fn release(&mut self, uid: u32, id: &str, direction: &str) -> Result<()> {
        match direction {
            "outgoing" => match self.outgoing.get_mut(id) {
                Some(owner) if *owner == Some(uid) => {
                    *owner = None;
                    Ok(())
                }
                Some(None) => Ok(()),
                _ => Err("forbidden"),
            },
            "incoming" => {
                let slot = self.incoming.get_mut(id).ok_or("file_transfer_released")?;
                if slot.uid != uid {
                    return Err("forbidden");
                }
                if slot.pending.is_some() {
                    return Err("file_busy");
                }
                slot.active = false;
                slot.offer = None;
                slot.prepare = Value::Null;
                Ok(())
            }
            _ => Err("invalid_request"),
        }
    }
    pub fn begin(
        &mut self,
        uid: u32,
        request: &str,
        operation: Operation,
        payload: &Value,
    ) -> Result<()> {
        let command = file_transfer::parse(
            file_operation(operation).ok_or("invalid_file_operation")?,
            payload,
        )?;
        let id = command.transfer().to_owned();
        if !self.incoming.contains_key(&id) {
            if matches!(command, Command::Cancel { .. }) {
                if self.incoming.len() + self.outgoing.len() >= 256 {
                    return Err("file_transfer_limit");
                }
                self.incoming.insert(
                    id,
                    Incoming {
                        uid,
                        active: false,
                        offer: None,
                        prepare: Value::Null,
                        approved: false,
                        opened: false,
                        offset: 0,
                        committed: false,
                        failed: true,
                        pending: Some((request.into(), command)),
                    },
                );
                return Ok(());
            }
            let Command::Prepare { offer, .. } = &command else {
                return Err("receive_consent_required");
            };
            self.reserve()?;
            self.incoming.insert(
                id.clone(),
                Incoming {
                    uid,
                    active: true,
                    offer: Some(offer.clone()),
                    prepare: payload.clone(),
                    approved: false,
                    opened: false,
                    offset: 0,
                    committed: false,
                    failed: false,
                    pending: None,
                },
            );
        }
        let slot = self.incoming.get_mut(&id).unwrap();
        if !slot.active {
            return Err("native_file_owner_reused");
        }
        if slot.uid != uid {
            return Err("forbidden");
        }
        if slot.pending.is_some() {
            return Err("file_busy");
        }
        if slot.failed && !matches!(command, Command::Cancel { .. }) {
            return Err("file_cancelled");
        }
        match &command {
            Command::Prepare { .. } => {
                if slot.prepare != *payload {
                    return Err("consent_metadata_mismatch");
                }
            }
            Command::Offer { offer, .. } => {
                if !slot.approved {
                    return Err("receive_consent_required");
                }
                if slot.committed || slot.offer.as_ref() != Some(offer) {
                    return Err("consent_metadata_mismatch");
                }
            }
            Command::Chunk { offset, data, .. } => {
                if !slot.opened || slot.committed || *offset != slot.offset {
                    return Err("offset_mismatch");
                }
                if slot
                    .offer
                    .as_ref()
                    .is_none_or(|o| offset + data.len() as u64 > o.size)
                {
                    return Err("file_length_exceeded");
                }
            }
            Command::Commit { .. } => {
                if !slot.opened || slot.offer.as_ref().is_none_or(|o| slot.offset != o.size) {
                    return Err("file_incomplete");
                }
            }
            Command::Cancel { .. } => {}
        }
        slot.pending = Some((request.into(), command));
        Ok(())
    }
    pub fn finish(&mut self, request: &str, outcome: &Result<OperationResult>) -> Result<()> {
        let Some(slot) = self
            .incoming
            .values_mut()
            .find(|s| s.pending.as_ref().is_some_and(|p| p.0 == request))
        else {
            return Ok(());
        };
        let (_, command) = slot.pending.as_ref().unwrap();
        match outcome {
            Ok(value) => {
                let encoded = serde_json::to_value(value).map_err(|_| "invalid_file_result")?;
                let data = &encoded["data"];
                match command {
                    Command::Prepare { .. } => {
                        slot.approved = data["approved"].as_bool() == Some(true)
                    }
                    Command::Offer { .. } => {
                        if data["offset"].as_u64() != Some(slot.offset) {
                            return Err("offset_mismatch");
                        }
                        slot.opened = true;
                    }
                    Command::Chunk {
                        offset,
                        data: bytes,
                        ..
                    } => slot.offset = offset + bytes.len() as u64,
                    Command::Commit { .. } => {
                        let offer = slot.offer.as_ref().ok_or("consent_metadata_mismatch")?;
                        if data["size"].as_u64() != Some(offer.size)
                            || data["sha256"].as_str() != Some(offer.sha256.as_str())
                        {
                            return Err("file_hash_mismatch");
                        }
                        slot.committed = true;
                    }
                    Command::Cancel { .. } => {
                        if data["cancelled"] == true {
                            slot.failed = true;
                        }
                    }
                }
            }
            Err(_) => slot.failed = true,
        }
        slot.pending = None;
        Ok(())
    }
    pub fn abandon(&mut self, request: &str) {
        for slot in self.incoming.values_mut() {
            if slot.pending.as_ref().is_some_and(|p| p.0 == request) {
                slot.pending = None;
                slot.failed = true;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn prepare(id: &str) -> Value {
        json!({"transfer":id,"purpose":"drag","native_owner_token":"d".repeat(32),"name":"synthetic.bin","mime":"application/octet-stream","size":3072,"sha256":"a".repeat(64)})
    }
    #[test]
    fn file_session_tombstones_and_active_slots_are_not_chunk_request_limits() {
        let mut files = FileSession::default();
        files.scope(&"a".repeat(64));
        for n in 0..256 {
            let id = format!("{n:032x}");
            files.allocate_outgoing(123, &id).unwrap();
            files.release(123, &id, "outgoing").unwrap();
            assert_eq!(
                files.allocate_outgoing(123, &id),
                Err("native_file_owner_reused")
            );
        }
        assert_eq!(
            files.allocate_outgoing(123, &"f".repeat(32)),
            Err("file_transfer_limit")
        );
        files.scope(&"b".repeat(64));
        for n in 0..4 {
            files.allocate_outgoing(123, &format!("{n:032x}")).unwrap();
        }
        assert_eq!(
            files.begin(123, "1", Operation::FilePrepare, &prepare(&"c".repeat(32))),
            Err("file_busy")
        );
    }
    #[test]
    fn file_session_approval_and_matching_offer_are_required_and_release_cannot_resurrect() {
        let mut files = FileSession::default();
        let id = "b".repeat(32);
        let p = prepare(&id);
        files.begin(123, "1", Operation::FilePrepare, &p).unwrap();
        assert_eq!(files.release(123, &id, "incoming"), Err("file_busy"));
        files
            .finish(
                "1",
                &Ok(OperationResult::FilePrepare(
                    json!({"transfer":id,"prepared":true,"approved":false}),
                )),
            )
            .unwrap();
        let offer = json!({"transfer":id,"name":"synthetic.bin","mime":"application/octet-stream","size":3072,"sha256":"a".repeat(64)});
        assert_eq!(
            files.begin(123, "2", Operation::FileOffer, &offer),
            Err("receive_consent_required")
        );
        files.begin(123, "3", Operation::FilePrepare, &p).unwrap();
        files
            .finish(
                "3",
                &Ok(OperationResult::FilePrepare(
                    json!({"transfer":id,"prepared":true,"approved":true}),
                )),
            )
            .unwrap();
        let mut wrong = offer.clone();
        wrong["size"] = json!(1);
        assert_eq!(
            files.begin(123, "4", Operation::FileOffer, &wrong),
            Err("consent_metadata_mismatch")
        );
        files.release(123, &id, "incoming").unwrap();
        assert_eq!(
            files.begin(123, "5", Operation::FilePrepare, &p),
            Err("native_file_owner_reused")
        );
    }
    #[test]
    fn file_session_cancel_before_prepare_retains_tombstone_without_claiming_rollback() {
        let mut files = FileSession::default();
        let id = "b".repeat(32);
        files
            .begin(123, "1", Operation::FileCancel, &json!({"transfer":id}))
            .unwrap();
        files
            .finish(
                "1",
                &Ok(OperationResult::FileCancel(
                    json!({"transfer":id,"cancelled":false}),
                )),
            )
            .unwrap();
        assert_eq!(files.active(), 0);
        assert_eq!(
            files.begin(123, "2", Operation::FilePrepare, &prepare(&id)),
            Err("native_file_owner_reused")
        );
    }
}
