//! File-scoped stop-and-wait ledger. Chunk RPCs do not consume the generic 256-request map.
//! Exactly four active files and 256 non-evictable transfer identities per actual Noise session.
use super::AsyncRequest;
use qj_core::gateway::Operation;
use qj_transport::{
    executor::file_operation,
    file_transfer::{self, Command, Offer},
};
use serde_json::Value;
use std::collections::BTreeMap;

type Result<T> = std::result::Result<T, &'static str>;
pub(super) struct Slot {
    pub uid: u32,
    pub step: Option<u64>,
    pub request: String,
    pub entry: Option<AsyncRequest>,
    pub consumed: bool,
    prepare_hash: Option<[u8; 32]>,
    offer: Option<Offer>,
    approved: bool,
    opened: bool,
    offset: u64,
    committed: bool,
    failed: bool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use sha2::{Digest, Sha256};
    use std::sync::mpsc;
    fn apply(
        files: &mut FileRequests,
        id: &str,
        step: u64,
        operation: Operation,
        payload: Value,
        data: Value,
    ) {
        let request = format!("file:{id}:{step}");
        let hash: [u8; 32] = Sha256::digest(payload.to_string()).into();
        assert!(!files
            .check(123, &request, operation, &payload, hash)
            .unwrap());
        let (_, receive) = mpsc::sync_channel(1);
        let result = serde_json::from_value(
            json!({"operation":file_operation(operation).unwrap(),"data":data}),
        )
        .unwrap();
        files
            .insert(
                123,
                request.clone(),
                &payload,
                AsyncRequest {
                    wire_id: step + 1,
                    operation,
                    payload_hash: hash,
                    read_only: false,
                    receive,
                    result: Some(Ok(result)),
                },
            )
            .unwrap();
        FileRequests::observe_result(files.request(123, &request).unwrap()).unwrap();
    }
    #[test]
    fn file_requests_full_64mib_uses_one_slot_not_21846_generic_records() {
        let mut files = FileRequests::default();
        files.session(&"a".repeat(64));
        let id = files.allocate(123).unwrap();
        let total = file_transfer::MAX_BYTES;
        let sha = "a".repeat(64);
        let prepare = json!({"transfer":id,"purpose":"drag","native_owner_token":"c".repeat(32),"name":"synthetic.bin","mime":"application/octet-stream","size":total,"sha256":sha});
        apply(
            &mut files,
            &id,
            0,
            Operation::FilePrepare,
            prepare.clone(),
            json!({"transfer":id,"prepared":true,"approved":true}),
        );
        let offer = json!({"transfer":id,"name":"synthetic.bin","mime":"application/octet-stream","size":total,"sha256":sha});
        apply(
            &mut files,
            &id,
            1,
            Operation::FileOffer,
            offer,
            json!({"transfer":id,"offset":0}),
        );
        let mut offset = 0;
        let mut step = 2;
        while offset < total {
            let count = (total - offset).min(3072);
            let base64 = if count == 3072 {
                "AAAA".repeat(1024)
            } else {
                assert_eq!(count, 1024);
                format!("{}AA==", "AAAA".repeat(341))
            };
            apply(
                &mut files,
                &id,
                step,
                Operation::FileChunk,
                json!({"transfer":id,"offset":offset,"data":base64}),
                json!({"transfer":id,"offset":offset+count}),
            );
            offset += count;
            step += 1;
            assert_eq!(files.records.len(), 1);
        }
        assert_eq!(step - 2, 21846);
        apply(
            &mut files,
            &id,
            step,
            Operation::FileCommit,
            json!({"transfer":id}),
            json!({"transfer":id,"size":total,"sha256":sha,"committed":true}),
        );
        assert_eq!(
            files.check(
                123,
                &format!("file:{id}:0"),
                Operation::FilePrepare,
                &prepare,
                Sha256::digest(prepare.to_string()).into()
            ),
            Err("file_step_replayed")
        );
        assert!(files.request(456, &format!("file:{id}:{step}")).is_err());
        files.release(123, &id).unwrap();
        assert!(files.request(123, &format!("file:{id}:{step}")).is_err());
        files.session(&"a".repeat(64));
        assert_eq!(files.records.len(), 1);
        files.session(&"b".repeat(64));
        assert!(files.records.is_empty());
    }
}
enum Record {
    Active(Box<Slot>),
    Released(u32),
}
#[derive(Default)]
pub(super) struct FileRequests {
    session: String,
    records: BTreeMap<String, Record>,
}
impl FileRequests {
    pub fn session(&mut self, session: &str) {
        if self.session != session {
            self.records.clear();
            self.session = session.into();
        }
    }
    pub fn allocate(&mut self, uid: u32) -> Result<String> {
        if self.records.len() >= 256 {
            return Err("file_transfer_limit");
        }
        if self
            .records
            .values()
            .filter(|r| matches!(r, Record::Active(_)))
            .count()
            >= 4
        {
            return Err("file_busy");
        }
        let mut bytes = [0u8; 16];
        getrandom::getrandom(&mut bytes).map_err(|_| "entropy_unavailable")?;
        let id = bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
        if self.records.contains_key(&id) {
            return Err("file_transfer_limit");
        }
        self.records.insert(
            id.clone(),
            Record::Active(Box::new(Slot {
                uid,
                step: None,
                request: String::new(),
                entry: None,
                consumed: true,
                prepare_hash: None,
                offer: None,
                approved: false,
                opened: false,
                offset: 0,
                committed: false,
                failed: false,
            })),
        );
        Ok(id)
    }
    fn slot(&mut self, uid: u32, id: &str) -> Result<&mut Slot> {
        match self.records.get_mut(id) {
            Some(Record::Active(slot)) if slot.uid == uid => Ok(slot),
            Some(Record::Released(owner)) if *owner == uid => Err("file_transfer_released"),
            _ => Err("forbidden"),
        }
    }
    /// Returns true only for an exact replay of the last request; previous steps never revive.
    pub fn check(
        &mut self,
        uid: u32,
        request: &str,
        operation: Operation,
        payload: &Value,
        hash: [u8; 32],
    ) -> Result<bool> {
        let (id, step) = file_transfer::request_identity(request)?;
        let command = file_transfer::parse(
            file_operation(operation).ok_or("invalid_file_operation")?,
            payload,
        )?;
        if command.transfer() != id {
            return Err("request_conflict");
        }
        let slot = self.slot(uid, id)?;
        if slot.step == Some(step) {
            let entry = slot.entry.as_ref().ok_or("file_step_replayed")?;
            return if entry.operation == operation && entry.payload_hash == hash {
                Ok(true)
            } else {
                Err("request_conflict")
            };
        }
        let next = slot.step.map_or(Some(0), |v| v.checked_add(1));
        if Some(step) != next {
            return Err("file_step_replayed");
        }
        if !slot.consumed {
            return Err("file_busy");
        }
        if slot.failed && !matches!(command, Command::Cancel { .. }) {
            return Err("file_cancelled");
        }
        match &command {
            Command::Prepare { offer, .. } => {
                if slot.prepare_hash.is_some_and(|h| h != hash)
                    || slot.offer.as_ref().is_some_and(|o| o != offer)
                {
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
        Ok(false)
    }
    pub fn insert(
        &mut self,
        uid: u32,
        request: String,
        payload: &Value,
        entry: AsyncRequest,
    ) -> Result<()> {
        let (id, step) = file_transfer::request_identity(&request)?;
        let command = file_transfer::parse(
            file_operation(entry.operation).ok_or("invalid_file_operation")?,
            payload,
        )?;
        let slot = self.slot(uid, id)?;
        if let Command::Prepare { offer, .. } = command {
            slot.offer = Some(offer);
            slot.prepare_hash = Some(entry.payload_hash);
        }
        slot.step = Some(step);
        slot.request = request;
        slot.entry = Some(entry);
        slot.consumed = false;
        Ok(())
    }
    pub fn request(&mut self, uid: u32, request: &str) -> Result<&mut Slot> {
        let (id, step) = file_transfer::request_identity(request)?;
        let slot = self.slot(uid, id)?;
        if slot.step != Some(step) || slot.request != request {
            return Err("file_step_replayed");
        }
        Ok(slot)
    }
    pub fn observe_result(slot: &mut Slot) -> Result<()> {
        let entry = slot.entry.as_ref().ok_or("request_not_found")?;
        let Some(outcome) = &entry.result else {
            return Ok(());
        };
        if slot.consumed {
            return Ok(());
        }
        match outcome {
            Ok(value) => {
                let data = serde_json::to_value(value).map_err(|_| "invalid_file_result")?;
                let data = &data["data"];
                match entry.operation {
                    Operation::FilePrepare => {
                        slot.approved = data["approved"].as_bool() == Some(true)
                    }
                    Operation::FileOffer => {
                        if data["offset"].as_u64() != Some(slot.offset) {
                            return Err("offset_mismatch");
                        }
                        slot.opened = true;
                    }
                    Operation::FileChunk => {
                        slot.offset = data["offset"].as_u64().ok_or("invalid_file_result")?
                    }
                    Operation::FileCommit => {
                        let offer = slot.offer.as_ref().ok_or("consent_metadata_mismatch")?;
                        if data["size"].as_u64() != Some(offer.size)
                            || data["sha256"].as_str() != Some(&offer.sha256)
                        {
                            return Err("file_hash_mismatch");
                        }
                        slot.committed = true;
                    }
                    Operation::FileCancel => {
                        if data["cancelled"] == true {
                            slot.failed = true;
                        }
                    }
                    _ => return Err("invalid_file_operation"),
                }
            }
            Err(_) => slot.failed = true,
        }
        slot.consumed = true;
        Ok(())
    }
    pub fn release(&mut self, uid: u32, id: &str) -> Result<()> {
        match self.records.get(id) {
            Some(Record::Active(slot)) if slot.uid == uid => {
                if slot.entry.as_ref().is_some_and(|e| e.result.is_none()) || !slot.consumed {
                    return Err("file_busy");
                }
            }
            Some(Record::Released(owner)) if *owner == uid => return Ok(()),
            _ => return Err("forbidden"),
        }
        self.records.insert(id.into(), Record::Released(uid));
        Ok(())
    }
    pub fn discard_unpublished(&mut self, uid: u32, id: &str) {
        if self.records.get(id).is_some_and(
            |r| matches!(r,Record::Active(s)if s.uid==uid&&s.step.is_none()&&s.entry.is_none()),
        ) {
            self.records.remove(id);
        }
    }
}
