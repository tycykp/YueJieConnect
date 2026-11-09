//! Strict authenticated file control schemas. No URI, path or Android object enters this wire.
//! Parsing prepares no destination and grants no consent; the native receive owner does that.
use serde_json::{Map, Value};

pub const MAX_BYTES: u64 = 64 * 1024 * 1024;
pub const CHUNK_BYTES: usize = 3072;
pub const OPERATIONS: [&str; 5] = [
    "file_prepare",
    "file_offer",
    "file_chunk",
    "file_commit",
    "file_cancel",
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Offer {
    pub name: String,
    pub mime: String,
    pub size: u64,
    pub sha256: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Purpose {
    Drag,
    Clipboard,
    AppRelay,
    NfcTap,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Command {
    Prepare {
        transfer: String,
        purpose: Purpose,
        native_owner_token: String,
        offer: Offer,
    },
    Offer {
        transfer: String,
        offer: Offer,
    },
    Chunk {
        transfer: String,
        offset: u64,
        data: Vec<u8>,
    },
    Commit {
        transfer: String,
    },
    Cancel {
        transfer: String,
    },
}

impl Command {
    pub fn transfer(&self) -> &str {
        match self {
            Self::Prepare { transfer, .. }
            | Self::Offer { transfer, .. }
            | Self::Chunk { transfer, .. }
            | Self::Commit { transfer }
            | Self::Cancel { transfer } => transfer,
        }
    }
}

fn fields<'a>(value: &'a Value, names: &[&str]) -> Result<&'a Map<String, Value>, &'static str> {
    let object = value.as_object().ok_or("invalid_file_payload")?;
    if object.len() != names.len() || names.iter().any(|name| !object.contains_key(*name)) {
        return Err("invalid_file_payload");
    }
    Ok(object)
}

fn text<'a>(object: &'a Map<String, Value>, key: &str) -> Result<&'a str, &'static str> {
    object
        .get(key)
        .and_then(Value::as_str)
        .ok_or("invalid_file_payload")
}

fn integer(object: &Map<String, Value>, key: &str) -> Result<u64, &'static str> {
    object
        .get(key)
        .and_then(Value::as_u64)
        .filter(|value| *value <= MAX_BYTES)
        .ok_or("invalid_file_payload")
}

fn lower_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn java_whitespace(c: char) -> bool {
    // String.isBlank / Character.isWhitespace, including Java's non-breaking-space exclusions.
    matches!(c, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{1680}'
        | '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}' | '\u{2028}' | '\u{2029}'
        | '\u{205f}' | '\u{3000}')
}

fn file_name(value: &str) -> bool {
    !value.is_empty()
        && value.encode_utf16().count() <= 160
        && !value.chars().all(java_whitespace)
        && !matches!(value, "." | "..")
        && !value.chars().any(|c| {
            matches!(c, '/' | '\\' | '\u{202d}' | '\u{202e}' | '\0'..='\u{001f}' | '\u{007f}'..='\u{009f}')
        })
}

fn mime(value: &str) -> bool {
    let Some((left, right)) = value.split_once('/') else {
        return false;
    };
    !left.is_empty()
        && !right.is_empty()
        && value.len() <= 128
        && [left, right].into_iter().all(|part| {
            part.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"!#$&^_.+-".contains(&b))
        })
}

fn offer(object: &Map<String, Value>) -> Result<Offer, &'static str> {
    let name = text(object, "name")?;
    let content_type = text(object, "mime")?;
    let sha256 = text(object, "sha256")?;
    if !file_name(name) || !mime(content_type) || !lower_hex(sha256, 64) {
        return Err("invalid_file_payload");
    }
    Ok(Offer {
        name: name.to_owned(),
        mime: content_type.to_owned(),
        size: integer(object, "size")?,
        sha256: sha256.to_owned(),
    })
}

fn base64_digit(byte: u8) -> Option<u8> {
    match byte {
        b'A'..=b'Z' => Some(byte - b'A'),
        b'a'..=b'z' => Some(byte - b'a' + 26),
        b'0'..=b'9' => Some(byte - b'0' + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

fn chunk(encoded: &str) -> Result<Vec<u8>, &'static str> {
    if encoded.is_empty() || encoded.len() > CHUNK_BYTES * 4 / 3 || !encoded.len().is_multiple_of(4) {
        return Err("invalid_chunk");
    }
    let mut bytes = Vec::with_capacity(encoded.len() / 4 * 3);
    for (index, quartet) in encoded.as_bytes().as_chunks::<4>().0.iter().enumerate() {
        let last = index + 1 == encoded.len() / 4;
        let a = base64_digit(quartet[0]).ok_or("invalid_chunk")?;
        let b = base64_digit(quartet[1]).ok_or("invalid_chunk")?;
        bytes.push(a << 2 | b >> 4);
        if quartet[2] == b'=' {
            if !last || quartet[3] != b'=' || b & 15 != 0 {
                return Err("invalid_chunk");
            }
        } else {
            let c = base64_digit(quartet[2]).ok_or("invalid_chunk")?;
            bytes.push(b << 4 | c >> 2);
            if quartet[3] == b'=' {
                if !last || c & 3 != 0 {
                    return Err("invalid_chunk");
                }
            } else {
                let d = base64_digit(quartet[3]).ok_or("invalid_chunk")?;
                bytes.push(c << 6 | d);
            }
        }
    }
    Ok(bytes)
}

pub fn parse(operation: &str, payload: &Value) -> Result<Command, &'static str> {
    let object = match operation {
        "file_prepare" => fields(
            payload,
            &[
                "transfer",
                "purpose",
                "native_owner_token",
                "name",
                "mime",
                "size",
                "sha256",
            ],
        )?,
        "file_offer" => fields(payload, &["transfer", "name", "mime", "size", "sha256"])?,
        "file_chunk" => fields(payload, &["transfer", "offset", "data"])?,
        "file_commit" | "file_cancel" => fields(payload, &["transfer"])?,
        _ => return Err("invalid_file_operation"),
    };
    let id = text(object, "transfer")?;
    if !lower_hex(id, 32) {
        return Err("invalid_file_payload");
    }
    let transfer = id.to_owned();
    match operation {
        "file_prepare" => {
            let purpose = match text(object, "purpose")? {
                "drag" => Purpose::Drag,
                "clipboard" => Purpose::Clipboard,
                "app_relay" => Purpose::AppRelay,
                "nfc_tap" => Purpose::NfcTap,
                _ => return Err("invalid_file_payload"),
            };
            let token = text(object, "native_owner_token")?;
            if !lower_hex(token, 32) {
                return Err("invalid_file_payload");
            }
            Ok(Command::Prepare {
                transfer,
                purpose,
                native_owner_token: token.to_owned(),
                offer: offer(object)?,
            })
        }
        "file_offer" => Ok(Command::Offer {
            transfer,
            offer: offer(object)?,
        }),
        "file_chunk" => {
            let offset = integer(object, "offset")?;
            let data = chunk(text(object, "data")?)?;
            if data.len() as u64 > MAX_BYTES - offset {
                return Err("file_length_exceeded");
            }
            Ok(Command::Chunk {
                transfer,
                offset,
                data,
            })
        }
        "file_commit" => Ok(Command::Commit { transfer }),
        "file_cancel" => Ok(Command::Cancel { transfer }),
        _ => unreachable!(),
    }
}

/// Statically binds receipts to their original command. Final commit SHA/size must additionally
/// match the saved Offer in the sender's authenticated transfer slot; commit has no Offer payload.
pub fn validate_result(
    operation: &str,
    payload: &Value,
    result: &Value,
) -> Result<(), &'static str> {
    let command = parse(operation, payload)?;
    let expected = match command {
        Command::Prepare { .. } => &["transfer", "prepared", "approved"][..],
        Command::Offer { .. } | Command::Chunk { .. } => &["transfer", "offset"][..],
        Command::Commit { .. } => &["transfer", "size", "sha256", "committed"][..],
        Command::Cancel { .. } => &["transfer", "cancelled"][..],
    };
    let object = fields(result, expected).map_err(|_| "invalid_file_result")?;
    if text(object, "transfer").map_err(|_| "invalid_file_result")? != command.transfer() {
        return Err("invalid_file_result");
    }
    let valid = match command {
        Command::Prepare { .. } => {
            object["prepared"].as_bool() == Some(true) && object["approved"].is_boolean()
        }
        Command::Offer { offer, .. } => integer(object, "offset").is_ok_and(|n| n <= offer.size),
        Command::Chunk { offset, data, .. } => {
            integer(object, "offset") == Ok(offset + data.len() as u64)
        }
        Command::Commit { .. } => {
            object["committed"].as_bool() == Some(true)
                && integer(object, "size").is_ok()
                && object["sha256"].as_str().is_some_and(|s| lower_hex(s, 64))
        }
        Command::Cancel { .. } => object["cancelled"].is_boolean(),
    };
    if valid {
        Ok(())
    } else {
        Err("invalid_file_result")
    }
}

/// Canonical file:<transfer>:<step>. State/ownership/high-water enforcement remains in JNI.
pub fn request_identity(request: &str) -> Result<(&str, u64), &'static str> {
    let rest = request.strip_prefix("file:").ok_or("invalid_request_id")?;
    let (transfer, step) = rest.split_once(':').ok_or("invalid_request_id")?;
    if !lower_hex(transfer, 32)
        || step.is_empty()
        || step.len() > 20
        || step.len() > 1 && step.starts_with('0')
        || !step.bytes().all(|b| b.is_ascii_digit())
    {
        return Err("invalid_request_id");
    }
    Ok((transfer, step.parse().map_err(|_| "invalid_request_id")?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn id() -> String {
        "a".repeat(32)
    }
    fn offer() -> Value {
        json!({"transfer":id(),"name":"synthetic.bin","mime":"application/octet-stream","size":7001,"sha256":"b".repeat(64)})
    }
    fn prepare() -> Value {
        let mut p = offer();
        p["purpose"] = json!("drag");
        p["native_owner_token"] = json!("c".repeat(32));
        p
    }
    #[test]
    fn file_schema_all_commands_and_results() {
        let p = prepare();
        assert!(matches!(
            parse("file_prepare", &p),
            Ok(Command::Prepare {
                purpose: Purpose::Drag,
                ..
            })
        ));
        for approved in [true, false] {
            assert!(validate_result(
                "file_prepare",
                &p,
                &json!({"transfer":id(),"prepared":true,"approved":approved})
            )
            .is_ok());
        }
        let p = offer();
        assert!(validate_result("file_offer", &p, &json!({"transfer":id(),"offset":0})).is_ok());
        let p = json!({"transfer":id(),"offset":3072,"data":"AAEC"});
        assert!(
            matches!(parse("file_chunk", &p), Ok(Command::Chunk{offset:3072,data,..}) if data == [0,1,2])
        );
        assert!(validate_result("file_chunk", &p, &json!({"transfer":id(),"offset":3075})).is_ok());
        let p = json!({"transfer":id()});
        assert!(validate_result(
            "file_commit",
            &p,
            &json!({"transfer":id(),"size":0,"sha256":"b".repeat(64),"committed":true})
        )
        .is_ok());
        for cancelled in [true, false] {
            assert!(validate_result(
                "file_cancel",
                &p,
                &json!({"transfer":id(),"cancelled":cancelled})
            )
            .is_ok());
        }
    }
    #[test]
    fn file_schema_strict_fields_and_no_uri_or_download_url() {
        for op in OPERATIONS {
            let base = match op {
                "file_prepare" => prepare(),
                "file_offer" => offer(),
                "file_chunk" => json!({"transfer":id(),"offset":0,"data":"AA=="}),
                _ => json!({"transfer":id()}),
            };
            for key in ["uri", "path", "url", "permission", "peer", "session_id"] {
                let mut p = base.clone();
                p[key] = json!("content://synthetic/forbidden");
                assert!(parse(op, &p).is_err());
            }
            for key in base.as_object().unwrap().keys() {
                let mut p = base.clone();
                p.as_object_mut().unwrap().remove(key);
                assert!(parse(op, &p).is_err());
            }
            assert!(parse(op, &Value::Null).is_err());
        }
        assert_eq!(
            parse("file_anything", &offer()),
            Err("invalid_file_operation")
        );
    }
    #[test]
    fn file_schema_java_utf16_name_boundaries() {
        let mut p = offer();
        p["name"] = json!("😀".repeat(80));
        assert!(parse("file_offer", &p).is_ok());
        p["name"] = json!(format!("{}x", "😀".repeat(80)));
        assert!(parse("file_offer", &p).is_err());
        for name in [
            "",
            " ",
            "\u{1680}",
            ".",
            "..",
            "a/b",
            "a\\b",
            "x\0",
            "x\u{001f}",
            "x\u{007f}",
            "x\u{009f}",
            "x\u{202d}",
            "x\u{202e}",
        ] {
            p["name"] = json!(name);
            assert!(parse("file_offer", &p).is_err(), "{name:?}");
        }
        for name in ["\u{00a0}", "\u{2007}", "\u{202f}", "synthetic😀.bin"] {
            p["name"] = json!(name);
            assert!(parse("file_offer", &p).is_ok());
        }
    }
    #[test]
    fn file_schema_mime_is_ascii_token_pair() {
        let mut p = offer();
        for mime in [
            "",
            "text",
            "/plain",
            "text/",
            "text/plain/extra",
            "text/plain;utf8",
            "图像/png",
            "text/*",
            "text/ plain",
        ] {
            p["mime"] = json!(mime);
            assert!(parse("file_offer", &p).is_err());
        }
        p["mime"] = json!(format!("{}/b", "a".repeat(126)));
        assert!(parse("file_offer", &p).is_ok());
        p["mime"] = json!(format!("{}/b", "a".repeat(127)));
        assert!(parse("file_offer", &p).is_err());
    }
    #[test]
    fn file_schema_integer_and_hash_identity_boundaries() {
        let mut p = offer();
        for size in [json!(0), json!(MAX_BYTES)] {
            p["size"] = size;
            assert!(parse("file_offer", &p).is_ok());
        }
        for size in [
            json!(-1),
            json!(MAX_BYTES + 1),
            json!(1.0),
            json!(1.5),
            json!("1"),
            json!(true),
            Value::Null,
        ] {
            p["size"] = size;
            assert!(parse("file_offer", &p).is_err());
        }
        p = offer();
        for key in ["transfer", "sha256"] {
            let original = p[key].clone();
            for value in [
                "A".repeat(original.as_str().unwrap().len()),
                "g".repeat(original.as_str().unwrap().len()),
                "a".repeat(original.as_str().unwrap().len() - 1),
            ] {
                p[key] = json!(value);
                assert!(parse("file_offer", &p).is_err());
            }
            p[key] = original;
        }
    }
    #[test]
    fn file_schema_prepare_native_owner_and_purpose_are_explicit() {
        let mut p = prepare();
        for purpose in ["drag", "clipboard", "app_relay", "nfc_tap"] {
            p["purpose"] = json!(purpose);
            assert!(parse("file_prepare", &p).is_ok());
        }
        for purpose in ["relay", "pc", "", "DRAG"] {
            p["purpose"] = json!(purpose);
            assert!(parse("file_prepare", &p).is_err());
        }
        p = prepare();
        for token in ["", "IP:192.0.2.1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaA"] {
            p["native_owner_token"] = json!(token);
            assert!(parse("file_prepare", &p).is_err());
        }
    }
    #[test]
    fn file_schema_canonical_base64_and_decoded_limit() {
        let mut p = json!({"transfer":id(),"offset":0,"data":"AA=="});
        for encoded in ["AA==", "AAE=", "AAEC"] {
            p["data"] = json!(encoded);
            assert!(parse("file_chunk", &p).is_ok());
        }
        for encoded in [
            "", "AA", "AAA", "AB==", "AAB=", "AA==AA==", " AA==", "AA==\n", "____", "AAAA=", "====",
        ] {
            p["data"] = json!(encoded);
            assert!(parse("file_chunk", &p).is_err());
        }
        p["data"] = json!("AAAA".repeat(1024));
        assert!(
            matches!(parse("file_chunk",&p),Ok(Command::Chunk{data,..}) if data.len()==CHUNK_BYTES)
        );
        p["data"] = json!("AAAA".repeat(1025));
        assert!(parse("file_chunk", &p).is_err());
        p["data"] = json!("AA==");
        p["offset"] = json!(MAX_BYTES - 1);
        assert!(parse("file_chunk", &p).is_ok());
        p["offset"] = json!(MAX_BYTES);
        assert_eq!(parse("file_chunk", &p), Err("file_length_exceeded"));
    }
    #[test]
    fn file_schema_receipt_is_bound_and_never_accepts_fake_ack() {
        let p = prepare();
        for ack in [
            json!({"transfer":id(),"prepared":false,"approved":true}),
            json!({"transfer":id(),"prepared":true,"approved":1}),
            json!({"transfer":"b".repeat(32),"prepared":true,"approved":true}),
            json!({"transfer":id(),"prepared":true,"approved":true,"uri":"content://private"}),
        ] {
            assert!(validate_result("file_prepare", &p, &ack).is_err());
        }
        let p = offer();
        assert!(
            validate_result("file_offer", &p, &json!({"transfer":id(),"offset":7002})).is_err()
        );
        assert!(validate_result("file_offer", &p, &json!({"transfer":id(),"offset":0.0})).is_err());
        let p = json!({"transfer":id(),"offset":3,"data":"AA=="});
        for offset in [3, 5] {
            assert!(
                validate_result("file_chunk", &p, &json!({"transfer":id(),"offset":offset}))
                    .is_err()
            );
        }
        let p = json!({"transfer":id()});
        for ack in [
            json!({"transfer":id(),"size":0,"sha256":"b".repeat(64),"committed":false}),
            json!({"transfer":id(),"size":0,"sha256":"wrong","committed":true}),
            json!({"transfer":id(),"size":MAX_BYTES+1,"sha256":"b".repeat(64),"committed":true}),
        ] {
            assert!(validate_result("file_commit", &p, &ack).is_err());
        }
        assert!(validate_result(
            "file_cancel",
            &p,
            &json!({"transfer":id(),"cancelled":"true"})
        )
        .is_err());
    }
    #[test]
    fn file_schema_request_step_has_one_canonical_representation() {
        for step in ["0", "1", "18446744073709551615"] {
            let request = format!("file:{}:{step}", id());
            assert_eq!(
                request_identity(&request),
                Ok((id().as_str(), step.parse::<u64>().unwrap()))
            );
        }
        for step in [
            "",
            "00",
            "01",
            "+1",
            "-1",
            "1.0",
            "1:0",
            "18446744073709551616",
        ] {
            assert!(request_identity(&format!("file:{}:{step}", id())).is_err());
        }
        assert!(request_identity(&format!("generic:{}:0", id())).is_err());
    }
}
