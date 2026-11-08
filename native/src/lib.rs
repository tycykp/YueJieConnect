//! Actual JNI ABI only; there is no fabricated Rust libxposed binding.
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jlong, jstring},
    JNIEnv,
};
use qj_core::gateway::{Operation, RealDevice};
use qj_core::{Caller, Engine, Source};
use qj_transport::bridge::{BatteryReader, Bridge, Identity};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    panic::{catch_unwind, AssertUnwindSafe},
    ptr,
    sync::{Arc, Mutex, OnceLock},
};

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();
static BRIDGE: OnceLock<Arc<Bridge>> = OnceLock::new();

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
#[allow(clippy::enum_variant_names)] // Mirrors the versioned IPC namespace, not vendor APIs.
enum BridgeCommand {
    BridgeListen {
        port: u16,
    },
    BridgeConnect {
        address: String,
    },
    BridgeConfirm {
        session: String,
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
    match command {
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
            Ok(
                json!({"status":"ok","projection":bridge.projection(caller.source.ok_or("forbidden")?)}),
            )
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
        BridgeCommand::BridgeConfirm { session } => {
            bridge.confirm(&session)?;
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
