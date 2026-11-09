package io.github.yuejie.connect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** DockInteraction's real launcher-provider protocol with bounded native permission/ability queries.
 * The original updateUI uses an unbounded ability.get(), so this consumer builds its exact native
 * intent/provider request after bounded checks. It neither opens a window nor changes consent. */
final class OplusRelayDirectory implements AppRelayDirectory.Consumer {
    static final String EXTRA_NATIVE_PEER = "io.github.yuejie.connect.app_relay.native_peer";
    static final String EXTRA_PROJECTION = "io.github.yuejie.connect.app_relay.projection";
    static final String EXTRA_SESSION = "io.github.yuejie.connect.app_relay.session";
    static final String EXTRA_DIRECTORY_TOKEN = "io.github.yuejie.connect.app_relay.directory_token";
    private final Context context;
    private final ClassLoader loader;
    private final String nativeDeviceName;
    private final AppRelayPeer peer;
    private final BooleanSupplier current, ownsNativeSlot;
    private final BooleanSupplier documentReady;
    private final Class<?> config, utils, features, ability, consentCallback;
    private final Method queryConsent;
    private final SharedPreferences preferences;
    private final List<AppRelayDirectoryIcon> retiring = new ArrayList<>();
    private AppRelayDirectoryIcon icon;
    private String previousIcon, previousName;
    private String shownPackage;
    private String shownClickToken = "";
    synchronized String clickToken() { return shownClickToken; }
    private boolean havePrevious;
    OplusRelayDirectory(Context context, ClassLoader loader, AppRelayPeer peer, String nativeDeviceName,
                        BooleanSupplier current, BooleanSupplier ownsNativeSlot) throws Exception {
        this(context, loader, peer, nativeDeviceName, current, ownsNativeSlot, () -> false);
    }
    OplusRelayDirectory(Context context, ClassLoader loader, AppRelayPeer peer, String nativeDeviceName,
                        BooleanSupplier current, BooleanSupplier ownsNativeSlot, BooleanSupplier documentReady) throws Exception {
        if (!"com.oplus.linker".equals(context.getPackageName()) || peer == null) throw new SecurityException("invalid_executor_context");
        this.context = context; this.loader = loader; this.peer = peer; this.nativeDeviceName = AppRelayCatalog.text(nativeDeviceName, 256);
        this.current = current; this.ownsNativeSlot = ownsNativeSlot;
        this.documentReady = documentReady;
        config = Class.forName("com.oplus.synergy.utils.Config", false, loader);
        utils = Class.forName("com.oplus.synergy.utils.RelayUtils", false, loader);
        features = Class.forName("com.oplus.synergy.utils.FeatureConfigUtil", false, loader);
        ability = Class.forName("com.oplus.pantaconnect.sdk.connectionservice.ability.Ability", false, loader);
        consentCallback = Class.forName("com.oplus.synergy.callback.UserAgreementStatusCallback", false, loader);
        queryConsent = Class.forName("com.oplus.synergy.utils.StartEngineUtils", false, loader)
                .getMethod("queryUserAgreement", Context.class, consentCallback);
        preferences = context.getSharedPreferences(c("SP_NAME_ICON"), 0);
    }
    @Override public synchronized void apply(AppRelayCatalog.Update change) throws Exception {
        requireCurrent(); cleanup();
        if (change.clearAll()) { clear(); return; }
        for (AppRelayCatalog.Candidate candidate : change.candidates()) {
            if (!candidate.visible() || !candidate.advertisesDataRelay()) {
                if (candidate.packageName().equals(shownPackage)) clear();
                continue;
            }
            if (candidate.kind() == AppRelayCatalog.Kind.DOCUMENT && !documentReady.getAsBoolean()) {
                if (candidate.packageName().equals(shownPackage)) clear();
                throw new IllegalStateException("file_transfer_required");
            }
            checkNative(candidate.packageName());
            var app = context.getPackageManager().getApplicationInfo(candidate.packageName(), 0);
            if (!candidate.eligible(true, app.enabled && (app.flags & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) == 0))
                throw new IllegalStateException("target_app_disabled");
            AppRelayDirectoryIcon next = AppRelayDirectoryIcon.create(context, loader, candidate.packageName(), retiring::add);
            retiring.remove(next);
            String attemptIcon = preferences.getString(c("KEY_ICON"), null), attemptName = preferences.getString(c("KEY_APP_NAME"), null);
            try {
                requireCurrent();
                String clickToken = java.util.UUID.randomUUID().toString().replace("-", "");
                Intent intent = new Intent(c("ACTION_APPLICATION_RELAY_SOURCE_CLICK"))
                        .setComponent(new ComponentName("com.oplus.linker", c("CLASS_NAME")))
                        .putExtra(c("KEY_RELAY_SOURCE_PKG_NAME"), candidate.packageName()).putExtra(c("KEY_RELAY_TYPE"), 1)
                        .putExtra(c("KEY_RELAY_SOURCE_ACTIVITY_NAME"), candidate.activity()).putExtra(c("RELAY_EXTEND_KEY_REMIND_TYPE"), "2")
                        .putExtra(EXTRA_NATIVE_PEER, peer.nativeId()).putExtra(EXTRA_PROJECTION, peer.projectionId()).putExtra(EXTRA_SESSION, peer.sessionId())
                        .putExtra(EXTRA_DIRECTORY_TOKEN, clickToken);
                JSONObject extra = new JSONObject().put(c("KEY_ICON_URL"), next.uri.toString())
                        .put(c("KEY_SUB_ICON_URL"), tablet() ? "rectangle_phone_img" : "rectangle_pad_img")
                        .put(c("KEY_CLICK_INTENT_KEY"), c("KEY_INTENT")).put(c("KEY_START_TYPE"), c("START_TYPE"))
                        .put("key_package_name", candidate.packageName()).put("key_device_name", nativeDeviceName);
                JSONObject data = new JSONObject().put("source", 2).put(c("KEY_GUID_TIP_RES"), tablet() ? "app_relay" : "app_relay_on_pad")
                        .put("data", new JSONArray().put(new JSONObject().put(c("KEY_EXTRA_DATA"), extra)));
                Bundle bundle = new Bundle(); bundle.putParcelable(c("KEY_INTENT"), intent);
                if (!havePrevious) {
                    previousIcon = preferences.getString(c("KEY_ICON"), null); previousName = preferences.getString(c("KEY_APP_NAME"), null);
                    havePrevious = true;
                }
                // Bind the exact icon URI to the same native click slot as the original DockInteraction.
                preferences.edit().putString(c("KEY_ICON"), next.uri.toString()).putString(c("KEY_APP_NAME"), candidate.label()).apply();
                context.getContentResolver().call(Uri.parse("content://com.android.launcher.OplusFavoritesProvider"),
                        c("METHOD_UPDATE_DOCKER_EXTRA_ITEM"), data.toString(), bundle);
                if (icon != null) retiring.add(icon); icon = next; next = null; shownPackage = candidate.packageName();
                shownClickToken = clickToken;
                cleanup();
            } finally {
                if (next != null) {
                    retiring.add(next);
                    if (next.uri.toString().equals(preferences.getString(c("KEY_ICON"), null))) {
                        var editor = preferences.edit();
                        if (attemptIcon == null) editor.remove(c("KEY_ICON")); else editor.putString(c("KEY_ICON"), attemptIcon);
                        if (attemptName == null) editor.remove(c("KEY_APP_NAME")); else editor.putString(c("KEY_APP_NAME"), attemptName);
                        editor.apply();
                    }
                    if (icon == null) havePrevious = false;
                    cleanup();
                }
            }
        }
    }
    private void checkNative(String pkg) throws Exception {
        requireCurrent();
        if (!Boolean.TRUE.equals(utils.getMethod("getSwitchStatus", Context.class, String.class).invoke(null, context, "smart_connection_app_transfer"))
                || !Boolean.TRUE.equals(utils.getMethod("getPeerRelaySwitchStatus", Context.class).invoke(null, context)))
            throw new IllegalStateException("native_relay_disabled");
        CompletableFuture<Boolean> consent = new CompletableFuture<>();
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{consentCallback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "RelayDirectoryConsent"; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; default -> null; };
            if ("onResult".equals(method.getName())) consent.complete(args != null && args.length == 1 && Boolean.TRUE.equals(args[0]));
            return null;
        });
        queryConsent.invoke(null, context, callback);
        if (!consent.get(2000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("native_user_agreement_required");
        Object result = ability.getMethod("checkLocalAbility", int.class, String.class).invoke(ability.getMethod("create").invoke(null), 256, pkg);
        if (!(result instanceof CompletableFuture<?> ready)) throw new IllegalStateException("ability_query_failed");
        try { if (!Boolean.TRUE.equals(ready.get(2000, TimeUnit.MILLISECONDS))) throw new IllegalStateException("target_relay_ability_missing"); }
        catch (java.util.concurrent.TimeoutException timeout) { ready.cancel(true); throw new IllegalStateException("ability_query_timeout"); }
        requireCurrent();
    }
    private String c(String name) throws Exception { return (String) config.getField(name).get(null); }
    private boolean tablet() throws Exception { return Boolean.TRUE.equals(features.getMethod("isTabletDevice").invoke(null)); }
    private void requireCurrent() { if (!current.getAsBoolean() || !ownsNativeSlot.getAsBoolean()) throw new IllegalStateException("native_peer_unavailable"); }
    @Override public synchronized void clear() throws Exception {
        if (icon != null) {
            if (ownsNativeSlot.getAsBoolean()) {
                JSONObject empty = new JSONObject().put("source", 2).put(c("KEY_GUID_TIP_RES"), "").put("data", new JSONArray());
                context.getContentResolver().call(Uri.parse("content://com.android.launcher.OplusFavoritesProvider"),
                        c("METHOD_UPDATE_DOCKER_EXTRA_ITEM"), empty.toString(), new Bundle());
            }
            retiring.add(icon); icon = null; shownPackage = null; shownClickToken = "";
        }
        restorePreferences(); cleanup();
    }
    private void restorePreferences() throws Exception {
        if (!havePrevious) return;
        String installed = preferences.getString(c("KEY_ICON"), null);
        if (retiring.stream().anyMatch(value -> value.uri != null && value.uri.toString().equals(installed))) {
            var editor = preferences.edit();
            if (previousIcon == null) editor.remove(c("KEY_ICON")); else editor.putString(c("KEY_ICON"), previousIcon);
            if (previousName == null) editor.remove(c("KEY_APP_NAME")); else editor.putString(c("KEY_APP_NAME"), previousName);
            editor.apply();
        }
        havePrevious = false;
    }
    private void cleanup() throws Exception {
        Exception failure = null;
        for (var iterator = retiring.iterator(); iterator.hasNext();) {
            try { iterator.next().close(); iterator.remove(); }
            catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }
}
