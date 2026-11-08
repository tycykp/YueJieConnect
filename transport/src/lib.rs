//! Offline SCP V4 plain control-frame subset from static evidence E25/C09/C18.
//! Not a connection/authentication implementation. Unsupported flags fail closed.
use sha2::{Digest, Sha256};

pub mod battery;
pub mod bridge;

#[derive(Debug, PartialEq, Eq)]
pub enum Error {
    Truncated,
    UnsupportedHeader,
    LengthMismatch,
    TooLarge,
}
#[derive(Debug, PartialEq, Eq)]
pub struct PlainControl {
    pub server: bool,
    pub hello: bool,
    pub agent: [u8; 4],
    pub protobuf: Vec<u8>,
}
impl PlainControl {
    pub fn encode(&self) -> Result<Vec<u8>, Error> {
        let len = u16::try_from(self.protobuf.len()).map_err(|_| Error::TooLarge)?;
        let mut b = vec![0x40, 0x30 | if self.server { 4 } else { 0 }];
        b.extend_from_slice(&len.to_be_bytes());
        b.extend_from_slice(&[0, if self.hello { 2 } else { 0 }, 0, 0]);
        b.extend_from_slice(&self.agent);
        b.extend_from_slice(&self.protobuf);
        Ok(b)
    }
    /// Exactly one complete frame, not TCP read boundaries. No allocation from an unchecked length.
    pub fn decode(b: &[u8]) -> Result<Self, Error> {
        if b.len() < 12 {
            return Err(Error::Truncated);
        }
        if b[0] != 0x40 || b[1] & !4 != 0x30 || b[4] != 0 || b[5] & !2 != 0 || b[6..8] != [0, 0] {
            return Err(Error::UnsupportedHeader);
        }
        if b.len() != 12 + u16::from_be_bytes([b[2], b[3]]) as usize {
            return Err(Error::LengthMismatch);
        }
        Ok(Self {
            server: b[1] & 4 != 0,
            hello: b[5] & 2 != 0,
            agent: b[8..12].try_into().unwrap(),
            protobuf: b[12..].to_vec(),
        })
    }
}
pub fn agent_address(namespace: &str, channel: &str) -> [u8; 4] {
    let hash = Sha256::digest(format!("{namespace}{channel}").as_bytes());
    hash[28..32].try_into().unwrap()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn static_layout_vector() {
        // Synthetic field-vector, NOT a device capture or interoperability proof.
        let bytes = [0x40, 0x34, 0, 2, 0, 2, 0, 0, 1, 2, 3, 4, 8, 0];
        let f = PlainControl::decode(&bytes).unwrap();
        assert!(f.server && f.hello);
        assert_eq!(f.protobuf, [8, 0]);
        assert_eq!(f.encode().unwrap(), bytes);
    }
    #[test]
    fn refuse_encrypted_crc_extensions_business_and_truncation() {
        let b = PlainControl {
            server: false,
            hello: false,
            agent: [0; 4],
            protobuf: vec![1],
        }
        .encode()
        .unwrap();
        for (index, mask) in [(1, 8), (1, 1), (4, 1), (5, 1), (6, 1), (0, 0x10)] {
            let mut bad = b.clone();
            bad[index] ^= mask;
            assert_eq!(PlainControl::decode(&bad), Err(Error::UnsupportedHeader));
        }
        for len in 0..b.len() {
            assert!(PlainControl::decode(&b[..len]).is_err());
        }
        let mut extra = b;
        extra.push(0);
        assert_eq!(PlainControl::decode(&extra), Err(Error::LengthMismatch));
    }
}
