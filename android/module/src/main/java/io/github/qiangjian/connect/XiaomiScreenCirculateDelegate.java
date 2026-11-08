package io.github.qiangjian.connect;

import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** The shared Circulate selection hook calls select for an actual user-selected proxy target.
 * Opens the same OEM SDK path as serviceType 196609; never constructs a window, terminal or permit.
 * The later real Mirror window is resolved separately by XiaomiScreenRequestAdapter. */
public final class XiaomiScreenCirculateDelegate {
    public static final int SCREEN_SERVICE = 196609;
    private final Class<?> clientType, deviceType, paramType;
    private final Field selectedId, requestedService, protocol, serviceDeviceId, sdk, context, relayDevice;
    private final Method findService, openMirror;
    private final Constructor<?> callback;

    public XiaomiScreenCirculateDelegate(ClassLoader loader) throws ReflectiveOperationException {
        Objects.requireNonNull(loader);
        clientType = Class.forName("com.miui.circulate.api.protocol.miuiplus.MiuiPlusClient", false, loader);
        deviceType = Class.forName("com.miui.circulate.api.service.CirculateDeviceInfo", false, loader);
        paramType = Class.forName("com.miui.circulate.api.bean.CirculateParam", false, loader);
        Class<?> service = Class.forName("com.miui.circulate.api.service.CirculateServiceInfo", false, loader);
        Class<?> base = Class.forName("com.miui.circulate.api.protocol.impl.b", false, loader);
        Class<?> nativeSdk = Class.forName("com.xiaomi.mirror.synergy.MiuiSynergySdk", false, loader);
        Class<?> relay = Class.forName("com.xiaomi.mirror.synergy.MiuiSynergySdk$RelayAppCallback", false, loader);
        selectedId = deviceType.getField("id"); requestedService = paramType.getField("circulateServiceInfo");
        protocol = service.getField("protocolType"); serviceDeviceId = service.getField("deviceId");
        findService = deviceType.getMethod("find", int.class);
        sdk = field(clientType, "mMiuiSynergySdk"); context = field(base, "mContext");
        relayDevice = field(clientType, "mRelayDevice");
        callback = Class.forName(clientType.getName() + "$e", false, loader).getDeclaredConstructor(clientType, int.class);
        callback.setAccessible(true);
        openMirror = nativeSdk.getMethod("openRemoteDeviceMirror3", Context.class, String.class, relay);
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    /** Returns null for app relay (196608) and other native services. The caller must check this
     * target is in the ORIGINAL selected list and its current authenticated proxy directory. */
    public Selection select(Object client, Object selectedDevice, Object circulateParam) throws ReflectiveOperationException {
        if (!clientType.isInstance(client) || !deviceType.isInstance(selectedDevice) || !paramType.isInstance(circulateParam)) return null;
        Object requested = requestedService.get(circulateParam);
        if (requested == null || protocol.getInt(requested) != SCREEN_SERVICE) return null;
        String id = (String) selectedId.get(selectedDevice);
        Object service = findService.invoke(selectedDevice, 196608); // Grouping protocol; not the requested business.
        String nativeId = service == null ? null : (String) serviceDeviceId.get(service);
        if (!valid(id) || !valid(nativeId) || "local_device_id".equals(id)) return null;
        return new Selection(client, selectedDevice, circulateParam, service, id, nativeId);
    }
    private static boolean valid(String id) { return id != null && !id.isBlank() && id.length() <= 256; }

    public final class Selection {
        private final Object client, selectedDevice, param, service;
        private final String id, nativeId;
        private boolean consumed;
        private Selection(Object client, Object selectedDevice, Object param, Object service, String id, String nativeId) {
            this.client = client; this.selectedDevice = selectedDevice; this.param = param; this.service = service;
            this.id = id; this.nativeId = nativeId;
        }
        public Object nativeClient() { return client; }
        public Object selectedDevice() { return selectedDevice; }
        public String selectedDeviceId() { return id; }
        public String nativeDeviceId() { return nativeId; }
        /** One attempt only, off the UI/Binder thread if the OEM SDK may block. The public control
         * plane must first retain this exact selection and create its request expectation. Returning
         * from this method is only SDK dispatch; consent, screenId, prepared and render are separate. */
        public synchronized void openNativeWindow(BooleanSupplier currentSelectedRequest) throws ReflectiveOperationException {
            Objects.requireNonNull(currentSelectedRequest);
            if (consumed) throw new IllegalStateException("native_screen_selection_consumed");
            consumed = true;
            Object requested = requestedService.get(param);
            if (!currentSelectedRequest.getAsBoolean() || requested == null || protocol.getInt(requested) != SCREEN_SERVICE
                    || !id.equals(selectedId.get(selectedDevice)) || findService.invoke(selectedDevice, 196608) != service
                    || !nativeId.equals(serviceDeviceId.get(service)))
                throw new IllegalStateException("native_screen_selection_changed");
            Object nativeSdk = sdk.get(client), nativeContext = context.get(client);
            if (nativeSdk == null || !(nativeContext instanceof Context)) throw new IllegalStateException("native_screen_sdk_unavailable");
            relayDevice.set(client, selectedDevice); // Same original MiuiPlusClient branch and failure callback.
            openMirror.invoke(nativeSdk, nativeContext, nativeId, callback.newInstance(client, SCREEN_SERVICE));
        }
        @Override public String toString() { return "NativeScreenSelection"; }
    }
}
