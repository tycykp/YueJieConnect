//! Actual JNI ABI only; there is no fabricated Rust libxposed binding.
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
    JNIEnv,
};
use qj_core::gateway::{Operation, RealDevice};
use qj_core::{Caller, Engine, Source};
use qj_transport::bridge::{BatteryReader, Bridge, Identity, OperationResult};
use qj_transport::executor::{execution_error, ExecutorOwner};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
    sync::mpsc::{Receiver, TryRecvError},
    sync::{Arc, Mutex, OnceLock},
};
mod file_requests;

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();
static BRIDGE: OnceLock<Arc<Bridge>> = OnceLock::new();
type AsyncKey = (u32, u32, String, String);
struct AsyncRequest {
    wire_id: u64,
    operation: Operation,
    payload_hash: [u8; 32],
    read_only: bool,
    receive: Receiver<Result<OperationResult, &'static str>>,
    result: Option<Result<OperationResult, &'static str>>,
}
static REQUESTS: OnceLock<Mutex<BTreeMap<AsyncKey, AsyncRequest>>> = OnceLock::new();
static FILE_REQUESTS: OnceLock<Mutex<file_requests::FileRequests>> = OnceLock::new();

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
#[allow(clippy::enum_variant_names)] // Mirrors the versioned IPC namespace, not vendor APIs.
enum BridgeCommand {
    BridgeFileAllocate {
        token: String,
        sequence: u64,
        session_id: String,
    },
    BridgeFileRelease {
        token: String,
        sequence: u64,
        session_id: String,
        transfer: String,
        direction: String,
    },
    BridgeRegisterExecutor {
        token: String,
        sequence: u64,
        operations: BTreeSet<Operation>,
    },
    BridgePollExecutor {
        token: String,
        sequence: u64,
    },
    BridgeCompleteExecutor {
        token: String,
        sequence: u64,
        session_id: String,
        request_id: String,
        result: Option<OperationResult>,
        error: Option<String>,
    },
    BridgeSubmit {
        token: String,
        sequence: u64,
        projection_id: String,
        session_id: String,
        request_id: String,
        operation: Operation,
        payload: Value,
    },
    BridgeResult {
        token: String,
        sequence: u64,
        session_id: String,
        request_id: String,
    },
    BridgeCancel {
        token: String,
        sequence: u64,
        session_id: String,
        request_id: String,
    },
    BridgeListen {
        port: u16,
    },
    BridgeConnect {
        address: String,
    },
    BridgeConfirm {
        session: String,
        #[serde(default)]
        operations: Option<BTreeSet<Operation>>,
    },
    BridgeStop,
    BridgeStatus,
    BridgeQueryPeer,
    BridgeProjections {
        token: String,
        sequence: u64,
    },
    BridgeProfile {
        token: String,
        sequence: u64,
        projection_id: String,
    },
    BridgeOperation {
        token: String,
        sequence: u64,
        projection_id: String,
        session_id: String,
        request_id: String,
        operation: Operation,
        payload: Value,
    },
}

fn authorize(
    caller: Caller,
    now: u64,
    token: &str,
    sequence: u64,
) -> Result<ExecutorOwner, &'static str> {
    ENGINE
        .get_or_init(|| Mutex::new(Engine::default()))
        .lock()
        .map_err(|_| "core_unavailable")?
        .authorize(caller, now, token, sequence)?;
    Ok(ExecutorOwner {
        uid: caller.uid,
        pid: caller.pid,
        token: token.to_owned(),
    })
}
fn valid_request(request: &str) -> bool {
    !request.is_empty()
        && request.len() <= 128
        && request
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_:".contains(&b))
}
fn request_response(
    result: Option<&Result<OperationResult, &'static str>>,
    request: &str,
    session: &str,
) -> Value {
    match result {
        None => json!({"status":"pending","request_id":request,"session_id":session}),
        Some(Ok(value)) => {
            json!({"status":"ok","request_id":request,"session_id":session,"result":value})
        }
        Some(Err(code)) => {
            json!({"status":"error","request_id":request,"session_id":session,"code":code})
        }
    }
}

fn file_result(
    bridge: &Bridge,
    caller: Caller,
    session: &str,
    request: &str,
    cancelling: bool,
) -> Result<Value, &'static str> {
    let mut files = FILE_REQUESTS
        .get_or_init(|| Mutex::new(file_requests::FileRequests::default()))
        .lock()
        .map_err(|_| "core_unavailable")?;
    files.session(session);
    let slot = files.request(caller.uid, request)?;
    let entry = slot.entry.as_mut().ok_or("request_not_found")?;
    if entry.result.is_none() {
        entry.result = match entry.receive.try_recv() {
            Ok(value) => Some(value),
            Err(TryRecvError::Empty) => None,
            Err(TryRecvError::Disconnected) => Some(Err("peer_offline")),
        };
        if cancelling && entry.result.is_none() {
            bridge.cancel_operation(session, entry.wire_id)?;
        }
    }
    if let Err(code) = file_requests::FileRequests::observe_result(slot) {
        slot.entry.as_mut().unwrap().result = Some(Err(code));
        file_requests::FileRequests::observe_result(slot)?;
    }
    Ok(request_response(
        slot.entry.as_ref().unwrap().result.as_ref(),
        request,
        session,
    ))
}

fn handle(caller: Caller, now: u64, text: &str) -> Result<Value, &'static str> {
    if text.len() > qj_core::MAX_INPUT {
        return Err("payload_too_large");
    }
    let value: Value = serde_json::from_str(text).map_err(|_| "invalid_request")?;
    if !value["op"].as_str().unwrap_or("").starts_with("bridge_") {
        return Ok(ENGINE
            .get_or_init(|| Mutex::new(Engine::default()))
            .lock()
            .map_err(|_| "core_unavailable")?
            .handle(caller, now, text));
    }
    let command: BridgeCommand = serde_json::from_value(value).map_err(|_| "invalid_request")?;
    let bridge = BRIDGE.get().ok_or("bridge_not_initialized")?;
    let cancelling = matches!(&command, BridgeCommand::BridgeCancel { .. });
    match command {
        BridgeCommand::BridgeFileAllocate {
            token,
            sequence,
            session_id,
        } => {
            authorize(caller, now, &token, sequence)?;
            let snapshot = bridge.snapshot();
            if snapshot.phase != "active" || snapshot.session != session_id {
                return Err("stale_session");
            }
            if caller.source != Some(snapshot.local.platform)
                || !snapshot
                    .granted_operations
                    .contains(&Operation::FilePrepare)
            {
                return Err("permission_denied");
            }
            let mut files = FILE_REQUESTS
                .get_or_init(|| Mutex::new(file_requests::FileRequests::default()))
                .lock()
                .map_err(|_| "core_unavailable")?;
            files.session(&session_id);
            let transfer = files.allocate(caller.uid)?;
            if let Err(code) = bridge.allocate_file(caller.uid, &session_id, &transfer) {
                files.discard_unpublished(caller.uid, &transfer);
                return Err(code);
            }
            Ok(json!({"status":"ok","session_id":session_id,"transfer":transfer}))
        }
        BridgeCommand::BridgeFileRelease {
            token,
            sequence,
            session_id,
            transfer,
            direction,
        } => {
            authorize(caller, now, &token, sequence)?;
            let snapshot = bridge.snapshot();
            if snapshot.phase != "active" || snapshot.session != session_id {
                return Err("stale_session");
            }
            if caller.source != Some(snapshot.local.platform)
                || !qj_transport::executor::valid_channel(&transfer)
            {
                return Err("forbidden");
            }
            if direction == "outgoing" {
                let mut files = FILE_REQUESTS
                    .get_or_init(|| Mutex::new(file_requests::FileRequests::default()))
                    .lock()
                    .map_err(|_| "core_unavailable")?;
                files.session(&session_id);
                files.release(caller.uid, &transfer)?;
            }
            bridge.release_file(caller.uid, &session_id, &transfer, &direction)?;
            Ok(json!({"status":"ok","session_id":session_id,"transfer":transfer,"released":true}))
        }
        BridgeCommand::BridgeRegisterExecutor {
            token,
            sequence,
            operations,
        } => {
            let owner = authorize(caller, now, &token, sequence)?;
            if caller.source != Some(bridge.snapshot().local.platform) {
                return Err("forbidden");
            }
            bridge.register_executor(owner, operations)?;
            Ok(json!({"status":"ok"}))
        }
        BridgeCommand::BridgePollExecutor { token, sequence } => {
            let owner = authorize(caller, now, &token, sequence)?;
            let (task, cancelled) = bridge.poll_executor(&owner);
            Ok(json!({"status":"ok","task":task,"cancelled":cancelled}))
        }
        BridgeCommand::BridgeCompleteExecutor {
            token,
            sequence,
            session_id,
            request_id,
            result,
            error,
        } => {
            let owner = authorize(caller, now, &token, sequence)?;
            let outcome = match (result, error) {
                (Some(value), None) => Ok(value),
                (None, Some(code)) => Err(execution_error(&code)),
                _ => return Err("invalid_result"),
            };
            bridge.complete_executor(&owner, &session_id, &request_id, outcome)?;
            Ok(json!({"status":"ok"}))
        }
        BridgeCommand::BridgeSubmit {
            token,
            sequence,
            projection_id,
            session_id,
            request_id,
            operation,
            payload,
        } => {
            authorize(caller, now, &token, sequence)?;
            if !valid_request(&request_id) {
                return Err("invalid_request_id");
            }
            let snapshot = bridge.snapshot();
            let current = snapshot.session;
            if snapshot.phase != "active" || session_id != current {
                return Ok(request_response(
                    Some(&Err("stale_session")),
                    &request_id,
                    &session_id,
                ));
            }
            if !snapshot
                .projection
                .as_ref()
                .is_some_and(|p| p.id == projection_id && Some(p.target_sdk) == caller.source)
            {
                return Err("forbidden");
            }
            let payload_hash: [u8; 32] = Sha256::digest(payload.to_string()).into();
            if qj_transport::executor::file_operation(operation).is_some() {
                let mut files = FILE_REQUESTS
                    .get_or_init(|| Mutex::new(file_requests::FileRequests::default()))
                    .lock()
                    .map_err(|_| "core_unavailable")?;
                files.session(&session_id);
                if files.check(caller.uid, &request_id, operation, &payload, payload_hash)? {
                    let slot = files.request(caller.uid, &request_id)?;
                    return Ok(request_response(
                        slot.entry.as_ref().unwrap().result.as_ref(),
                        &request_id,
                        &session_id,
                    ));
                }
                let (wire_id, receive) = bridge.submit_operation(
                    caller.source.ok_or("forbidden")?,
                    &projection_id,
                    &session_id,
                    operation,
                    payload.clone(),
                )?;
                files.insert(
                    caller.uid,
                    request_id.clone(),
                    &payload,
                    AsyncRequest {
                        wire_id,
                        operation,
                        payload_hash,
                        read_only: false,
                        receive,
                        result: None,
                    },
                )?;
                return Ok(request_response(None, &request_id, &session_id));
            }
            if request_id.starts_with("file:") {
                return Err("invalid_request_id");
            }
            let mut requests = REQUESTS
                .get_or_init(|| Mutex::new(BTreeMap::new()))
                .lock()
                .map_err(|_| "core_unavailable")?;
            requests.retain(|key, _| key.2 == current);
            let key = (
                caller.uid,
                caller.pid,
                session_id.clone(),
                request_id.clone(),
            );
            if let Some(existing) = requests.get(&key) {
                if existing.operation != operation || existing.payload_hash != payload_hash {
                    return Err("request_conflict");
                }
                return Ok(request_response(
                    existing.result.as_ref(),
                    &request_id,
                    &session_id,
                ));
            }
            if requests.len() >= 256 {
                return Err("request_limit");
            }
            let (wire_id, receive) = match bridge.submit_operation(
                caller.source.ok_or("forbidden")?,
                &projection_id,
                &session_id,
                operation,
                payload.clone(),
            ) {
                Ok(value) => value,
                Err(code) => {
                    return Ok(request_response(Some(&Err(code)), &request_id, &session_id))
                }
            };
            requests.insert(
                key,
                AsyncRequest {
                    wire_id,
                    operation,
                    payload_hash,
                    read_only: matches!(
                        operation,
                        Operation::DeviceProfile
                            | Operation::BatteryStatus
                            | Operation::LogicalState
                    ) || (operation == Operation::DeviceFinder
                        && payload["action"] == "state")
                        || (operation == Operation::MediaControl
                            && matches!(
                                payload["control"]["action"].as_str(),
                                Some("QUERY" | "VOLUME_QUERY")
                            ))
                        || (operation == Operation::AppRelay && payload["action"] == "status"),
                    receive,
                    result: None,
                },
            );
            Ok(request_response(None, &request_id, &session_id))
        }
        BridgeCommand::BridgeResult {
            token,
            sequence,
            session_id,
            request_id,
        }
        | BridgeCommand::BridgeCancel {
            token,
            sequence,
            session_id,
            request_id,
        } => {
            authorize(caller, now, &token, sequence)?;
            let snapshot = bridge.snapshot();
            if snapshot.phase != "active" || snapshot.session != session_id {
                return Ok(request_response(
                    Some(&Err("stale_session")),
                    &request_id,
                    &session_id,
                ));
            }
            if request_id.starts_with("file:") {
                return file_result(bridge, caller, &session_id, &request_id, cancelling);
            }
            let mut requests = REQUESTS
                .get_or_init(|| Mutex::new(BTreeMap::new()))
                .lock()
                .map_err(|_| "core_unavailable")?;
            let entry = requests
                .get_mut(&(
                    caller.uid,
                    caller.pid,
                    session_id.clone(),
                    request_id.clone(),
                ))
                .ok_or("request_not_found")?;
            if entry.result.is_none() {
                entry.result = match entry.receive.try_recv() {
                    Ok(value) => Some(value),
                    Err(TryRecvError::Empty) => None,
                    Err(TryRecvError::Disconnected) => Some(Err("peer_offline")),
                };
                if cancelling && entry.result.is_none() {
                    bridge.cancel_operation(&session_id, entry.wire_id)?;
                }
            }
            let response = request_response(entry.result.as_ref(), &request_id, &session_id);
            if entry.result.is_some() && entry.read_only {
                requests.remove(&(caller.uid, caller.pid, session_id, request_id));
            }
            Ok(response)
        }
        BridgeCommand::BridgeOperation {
            token,
            sequence,
            projection_id,
            session_id,
            request_id,
            operation,
            payload,
        } => {
            ENGINE
                .get_or_init(|| Mutex::new(Engine::default()))
                .lock()
                .map_err(|_| "core_unavailable")?
                .authorize(caller, now, &token, sequence)?;
            if request_id.is_empty()
                || request_id.len() > 128
                || !request_id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_:".contains(&b))
            {
                return Err("invalid_request_id");
            }
            if !payload.as_object().is_some_and(|object| object.is_empty()) {
                return Err("invalid_payload");
            }
            let result = bridge.request_operation(
                caller.source.ok_or("forbidden")?,
                &projection_id,
                &session_id,
                operation,
            );
            Ok(match result {
                Ok(result) => {
                    json!({"status":"ok","request_id":request_id,"session_id":session_id,"result":result})
                }
                Err(code) => {
                    json!({"status":"error","request_id":request_id,"session_id":session_id,"code":code})
                }
            })
        }
        BridgeCommand::BridgeProjections { token, sequence } => {
            ENGINE
                .get_or_init(|| Mutex::new(Engine::default()))
                .lock()
                .map_err(|_| "core_unavailable")?
                .authorize(caller, now, &token, sequence)?;
            let source = caller.source.ok_or("forbidden")?;
            let snapshot = bridge.snapshot();
            let mut projection = json!(snapshot
                .projection
                .filter(|p| snapshot.phase == "active" && p.target_sdk == source));
            if let Some(value) = projection.as_object_mut() {
                value.insert(
                    "transport_address".into(),
                    json!(snapshot.transport_address),
                );
                value.insert("local_grants".into(), json!(snapshot.granted_operations));
            }
            Ok(json!({"status":"ok","projection":projection}))
        }
        BridgeCommand::BridgeProfile {
            token,
            sequence,
            projection_id,
        } => {
            ENGINE
                .get_or_init(|| Mutex::new(Engine::default()))
                .lock()
                .map_err(|_| "core_unavailable")?
                .authorize(caller, now, &token, sequence)?;
            let profile = bridge.request(
                caller.source.ok_or("forbidden")?,
                &projection_id,
                Operation::DeviceProfile,
            )?;
            Ok(json!({"status":"ok","real_device":profile,"native_connection_supported":false}))
        }
        _ if !caller.owner => Err("forbidden"),
        BridgeCommand::BridgeStatus => Ok(json!({"status":"ok","bridge":bridge.snapshot()})),
        BridgeCommand::BridgeConfirm {
            session,
            operations,
        } => {
            bridge.confirm_operations(&session, operations)?;
            Ok(json!({"status":"ok"}))
        }
        BridgeCommand::BridgeListen { port } => {
            let port = bridge.listen(([0, 0, 0, 0], port).into())?;
            ENGINE
                .get_or_init(|| Mutex::new(Engine::default()))
                .lock()
                .map_err(|_| "core_unavailable")?
                .handle(caller, now, r#"{"op":"set_enabled","enabled":true}"#);
            Ok(json!({"status":"ok","port":port}))
        }
        BridgeCommand::BridgeConnect { address } => {
            let address: std::net::SocketAddr = address.parse().map_err(|_| "invalid_address")?;
            if address.port() == 0
                || !matches!(address.ip(),std::net::IpAddr::V4(ip) if ip.is_private() || ip.is_loopback() || ip.is_link_local())
            {
                return Err("lan_address_required");
            }
            bridge.connect(address)?;
            ENGINE
                .get_or_init(|| Mutex::new(Engine::default()))
                .lock()
                .map_err(|_| "core_unavailable")?
                .handle(caller, now, r#"{"op":"set_enabled","enabled":true}"#);
            Ok(json!({"status":"ok"}))
        }
        BridgeCommand::BridgeStop => {
            bridge.stop();
            ENGINE
                .get_or_init(|| Mutex::new(Engine::default()))
                .lock()
                .map_err(|_| "core_unavailable")?
                .handle(caller, now, r#"{"op":"set_enabled","enabled":false}"#);
            Ok(json!({"status":"ok"}))
        }
        BridgeCommand::BridgeQueryPeer => {
            let snapshot = bridge.snapshot();
            let projection = snapshot.projection.ok_or("peer_offline")?;
            let profile = bridge.request(
                snapshot.local.platform,
                &projection.id,
                Operation::DeviceProfile,
            )?;
            Ok(json!({"status":"ok","real_device":profile,"native_connection_supported":false}))
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_qiangjian_connect_NativeCore_initialize(
    mut env: JNIEnv,
    class: JClass,
    path: JString,
    profile: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<Value, &'static str> {
        if BRIDGE.get().is_some() {
            return Ok(json!({"status":"ok"}));
        }
        let path: String = env.get_string(&path).map_err(|_| "invalid_path")?.into();
        let profile: String = env
            .get_string(&profile)
            .map_err(|_| "invalid_profile")?
            .into();
        if profile.len() > 8192 {
            return Err("invalid_profile");
        }
        let profile: RealDevice = serde_json::from_str(&profile).map_err(|_| "invalid_profile")?;
        let identity = Identity::load_or_create(std::path::Path::new(&path))?;
        let available = env
            .call_static_method(&class, "hasBatteryReader", "()Z", &[])
            .and_then(|v| v.z())
            .map_err(|_| "executor_setup_failed")?;
        let reader: Option<BatteryReader> = if available {
            let vm = env.get_java_vm().map_err(|_| "executor_setup_failed")?;
            let class = env
                .new_global_ref(class)
                .map_err(|_| "executor_setup_failed")?;
            Some(Arc::new(move || {
                let mut env = vm
                    .attach_current_thread()
                    .map_err(|_| "battery_unavailable")?;
                let class: &JClass = class.as_obj().into();
                let value =
                    env.call_static_method(class, "readBatteryJson", "()Ljava/lang/String;", &[]);
                if value.is_err() {
                    let _ = env.exception_clear();
                    return Err("battery_unavailable");
                }
                let value = JString::from(value.unwrap().l().map_err(|_| "battery_unavailable")?);
                let text: String = env
                    .get_string(&value)
                    .map_err(|_| "battery_unavailable")?
                    .into();
                // Keep the attached thread's local reference table bounded across requests.
                env.delete_local_ref(value)
                    .map_err(|_| "battery_unavailable")?;
                serde_json::from_str(&text).map_err(|_| "battery_unavailable")
            }))
        } else {
            None
        };
        BRIDGE
            .set(Bridge::with_battery_reader(identity, profile, reader)?)
            .map_err(|_| "already_initialized")?;
        Ok(json!({"status":"ok"}))
    }));
    let result = match result {
        Ok(Ok(v)) => v,
        Ok(Err(e)) => qj_core::error(e),
        Err(_) => qj_core::error("core_panic"),
    };
    match env.new_string(result.to_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_qiangjian_connect_NativeCore_dispatch(
    mut env: JNIEnv,
    _: JClass,
    uid: jint,
    pid: jint,
    owner: jboolean,
    source: jint,
    elapsed_ms: jlong,
    input: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let text: String = env.get_string(&input).map_err(|_| "invalid_utf8")?.into();
        if uid < 0 || pid <= 0 || elapsed_ms < 0 {
            return Err("invalid_caller");
        }
        let caller = Caller {
            uid: uid as u32,
            pid: pid as u32,
            owner: owner != 0,
            source: match source {
                1 => Some(Source::HyperOs4),
                2 => Some(Source::ColorOs17),
                _ => None,
            },
        };
        Ok(handle(caller, elapsed_ms as u64, &text)?.to_string())
    }));
    let output = match result {
        Ok(Ok(text)) => text,
        Ok(Err(code)) => qj_core::error(code).to_string(),
        Err(_) => qj_core::error("core_panic").to_string(),
    };
    match env.new_string(output) {
        Ok(s) => s.into_raw(),
        Err(_) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "JNI response allocation failed",
            );
            ptr::null_mut()
        }
    }
}
