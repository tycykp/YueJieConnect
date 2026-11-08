//! Fixed OS ServiceManager phone Binder lookup. Native owner includes this module; no arbitrary name.
//! A returned Binder alone is not authorization: Java must perform the pinned phone reverse challenge.
use jni::objects::JClass;
use jni::sys::jobject;
use jni::JNIEnv;

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use std::ffi::{c_char, c_int, c_void};
    use std::sync::OnceLock;
    type Check = unsafe extern "C" fn(*const c_char) -> *mut c_void;
    type ToJava = unsafe extern "C" fn(*mut jni::sys::JNIEnv, *mut c_void) -> jobject;
    type Dec = unsafe extern "C" fn(*mut c_void);
    struct Api { check: Check, to_java: ToJava, dec: Dec }
    #[link(name = "dl")]
    extern "C" {
        fn dlopen(name: *const c_char, flags: c_int) -> *mut c_void;
        fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
        fn dlclose(handle: *mut c_void) -> c_int;
    }
    static API: OnceLock<Option<Api>> = OnceLock::new();
    unsafe fn load() -> Option<Api> {
        // binder_manager is a platform interface, so do not assume it is in the public NDK link stub.
        // Query fixed actual exports. Failure denies proof; no hidden-API exemption or su fallback.
        let handle = unsafe { dlopen(c"libbinder_ndk.so".as_ptr(), 2) };
        if handle.is_null() { return None; }
        let check = unsafe { dlsym(handle, c"AServiceManager_checkService".as_ptr()) };
        let to_java = unsafe { dlsym(handle, c"AIBinder_toJavaBinder".as_ptr()) };
        let dec = unsafe { dlsym(handle, c"AIBinder_decStrong".as_ptr()) };
        if check.is_null() || to_java.is_null() || dec.is_null() { unsafe { dlclose(handle); } return None; }
        // Retain this single fixed library handle for the process lifetime: cached function pointers.
        Some(Api { check: unsafe { std::mem::transmute::<*mut c_void, Check>(check) },
            to_java: unsafe { std::mem::transmute::<*mut c_void, ToJava>(to_java) },
            dec: unsafe { std::mem::transmute::<*mut c_void, Dec>(dec) } })
    }
    pub unsafe fn phone(env: &JNIEnv<'_>) -> jobject {
        let Some(api) = API.get_or_init(|| unsafe { load() }) else { return std::ptr::null_mut(); };
        let binder = unsafe { (api.check)(c"phone".as_ptr()) };
        if binder.is_null() { return std::ptr::null_mut(); }
        let raw_env = env.get_raw();
        let object = unsafe { (api.to_java)(raw_env, binder) };
        unsafe { (api.dec)(binder); }
        if object.is_null() { return object; }
        // The NDK conversion may return a local or global reference. Always return a fresh local
        // and release the original according to its actual JNI reference kind.
        let functions = unsafe { &**raw_env };
        let kind = unsafe { functions.GetObjectRefType.expect("JNI ABI")(raw_env, object) };
        if matches!(kind, jni::sys::jobjectRefType::JNIInvalidRefType) { return std::ptr::null_mut(); }
        let local = unsafe { functions.NewLocalRef.expect("JNI ABI")(raw_env, object) };
        match kind {
            jni::sys::jobjectRefType::JNILocalRefType => unsafe { functions.DeleteLocalRef.expect("JNI ABI")(raw_env, object) },
            jni::sys::jobjectRefType::JNIGlobalRefType => unsafe { functions.DeleteGlobalRef.expect("JNI ABI")(raw_env, object) },
            jni::sys::jobjectRefType::JNIWeakGlobalRefType => unsafe { functions.DeleteWeakGlobalRef.expect("JNI ABI")(raw_env, object) },
            _ => return std::ptr::null_mut(),
        }
        local
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_qiangjian_connect_TelephonySyncSystemBinder_phoneBinderNative(
    env: JNIEnv<'_>, _class: JClass<'_>,
) -> jobject {
    #[cfg(target_os = "android")]
    { unsafe { android::phone(&env) } }
    #[cfg(not(target_os = "android"))]
    { let _ = env; std::ptr::null_mut() }
}
