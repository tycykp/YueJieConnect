package io.github.qiangjian.connect;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Consumes a bound remote candidate through Mirror's original y0.R, preserving the real native peer. */
final class XiaomiRelayDirectory implements AppRelayDirectory.Consumer {
    private final Context context;
    private final AppRelayPeer peer;
    private final String nativeDeviceName;
    private final BooleanSupplier current, ownsNativeSlot;
    private final BooleanSupplier documentReady;
    private final Class<?> message;
    private final Object helper;
    private final Method update, settings, enabled;
    private AppRelayCatalog.Candidate shown;
    XiaomiRelayDirectory(Context context, ClassLoader loader, AppRelayPeer peer, String nativeDeviceName,
                         BooleanSupplier current, BooleanSupplier ownsNativeSlot) throws Exception {
        this(context, loader, peer, nativeDeviceName, current, ownsNativeSlot, () -> false);
    }
    XiaomiRelayDirectory(Context context, ClassLoader loader, AppRelayPeer peer, String nativeDeviceName,
                         BooleanSupplier current, BooleanSupplier ownsNativeSlot, BooleanSupplier documentReady) throws Exception {
        if (!"com.xiaomi.mirror".equals(context.getPackageName())) throw new SecurityException("invalid_executor_context");
        this.context = context; this.peer = peer; this.nativeDeviceName = AppRelayCatalog.text(nativeDeviceName, 256);
        this.current = current; this.ownsNativeSlot = ownsNativeSlot;
        this.documentReady = documentReady;
        message = Class.forName("com.xiaomi.mirror.message.RelayAppMessage", false, loader);
        Class<?> owner = Class.forName("com.xiaomi.mirror.relay.y0", false, loader);
        helper = owner.getMethod("n").invoke(null); update = owner.getMethod("R", String.class, message, String.class);
        Class<?> config = Class.forName("com.xiaomi.mirror.j0", false, loader);
        settings = config.getMethod("m"); enabled = config.getMethod("z");
    }
    @Override public synchronized void apply(AppRelayCatalog.Update change) throws Exception {
        requireCurrent();
        if (change.clearAll()) { clear(); return; }
        if (!Boolean.TRUE.equals(enabled.invoke(settings.invoke(null)))) { clear(); throw new IllegalStateException("native_relay_disabled"); }
        for (AppRelayCatalog.Candidate candidate : change.candidates()) {
            if (!candidate.visible() || !candidate.advertisesDataRelay()) {
                if (shown != null && shown.packageName().equals(candidate.packageName())) clear();
                continue;
            }
            if (candidate.kind() == AppRelayCatalog.Kind.DOCUMENT && !documentReady.getAsBoolean()) {
                if (shown != null && shown.packageName().equals(candidate.packageName())) clear();
                throw new IllegalStateException("file_transfer_required");
            }
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(candidate.packageName(), 0);
            if (!candidate.eligible(true, app.enabled && (app.flags & ApplicationInfo.FLAG_SUSPENDED) == 0))
                throw new IllegalStateException("target_app_disabled");
            Object nativeMessage = nativeMessage(candidate, false);
            nativeMessage.getClass().getField("iconBitmap").set(nativeMessage,
                    AppRelayDirectoryIcon.bitmap(context.getPackageManager().getApplicationIcon(candidate.packageName())));
            requireCurrent();
            update.invoke(helper, peer.nativeId(), nativeMessage, nativeDeviceName);
            shown = candidate;
        }
    }
    private Object nativeMessage(AppRelayCatalog.Candidate value, boolean hide) throws Exception {
        Object result = message.getConstructor().newInstance();
        int type = switch (value.kind()) { case APP -> 3; case BROWSER -> 1; case DOCUMENT -> 2; default -> throw new IllegalArgumentException("not_application_handoff"); };
        message.getField("type").setInt(result, type);
        message.getField("processName").set(result, value.packageName());
        message.getField("appName").set(result, value.label()); message.getField("description").set(result, "");
        message.getField("mDstDeviceType").setInt(result, 2); message.getField("isRelay").setBoolean(result, true);
        message.getField("supportHandoff").setBoolean(result, !hide); message.getField("isHideIcon").setBoolean(result, hide);
        // unregisterRelay is ignored by y0.R while its own task is active; explicit isHideIcon
        // is the native withdrawal path, rather than unregistering an application capability.
        message.getField("unregisterRelay").setBoolean(result, false); message.getField("receiveTime").setLong(result, System.currentTimeMillis());
        return result;
    }
    private void requireCurrent() { if (!current.getAsBoolean() || !ownsNativeSlot.getAsBoolean()) throw new IllegalStateException("native_peer_unavailable"); }
    @Override public synchronized void clear() throws Exception {
        if (shown == null) return;
        // The shared owner keeps this native UI slot lease until withdrawal, or transfers ownership
        // before a different real OEM peer replaces it. Never remove another peer's current icon.
        if (ownsNativeSlot.getAsBoolean()) update.invoke(helper, peer.nativeId(), nativeMessage(shown, true), nativeDeviceName);
        shown = null;
    }
}
