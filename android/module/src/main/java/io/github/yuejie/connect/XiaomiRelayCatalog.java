package io.github.yuejie.connect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Original Mirror message fields, including package-private RecentAppsInfo fields. */
final class XiaomiRelayCatalog {
    static AppRelayCatalog.Update relay(Object message) throws ReflectiveOperationException {
        AppRelayCatalog.requireClass(message, "com.xiaomi.mirror.message.RelayAppMessage");
        int type = (Integer) field(message, "type"), destination = (Integer) field(message, "mDstDeviceType");
        AppRelayCatalog.Kind kind = switch (type) {
            case 0 -> AppRelayCatalog.Kind.RECENT;
            case 1 -> AppRelayCatalog.Kind.BROWSER;
            case 2 -> AppRelayCatalog.Kind.DOCUMENT;
            case 3 -> AppRelayCatalog.Kind.APP;
            default -> throw new IllegalArgumentException("invalid_relay_type");
        };
        boolean visible = !(Boolean) field(message, "isHideIcon") && !(Boolean) field(message, "unregisterRelay");
        boolean advertised = visible && destination == 2 && type != 0
                && (Boolean) field(message, "isRelay") && (Boolean) field(message, "supportHandoff");
        // description can contain a browser/document title; never copy it to the directory.
        return new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(
                packageOfProcess((String) field(message, "processName")), (String) field(message, "appName"), kind,
                visible, advertised, destination, "")));
    }
    static AppRelayCatalog.Update recent(Object message) throws ReflectiveOperationException {
        AppRelayCatalog.requireClass(message, "com.xiaomi.mirror.message.RecentAppsMessage");
        if ((Integer) field(message, "action") != 2) throw new IllegalArgumentException("not_recent_apps_response");
        Object raw = field(message, "appInfoList");
        if (!(raw instanceof List<?> list) || list.size() > 4) throw new IllegalArgumentException("invalid_recent_apps");
        ArrayList<AppRelayCatalog.Candidate> entries = new ArrayList<>();
        for (Object info : list) {
            AppRelayCatalog.requireClass(info, "com.xiaomi.mirror.message.RecentAppsMessage$RecentAppsInfo");
            // Fold/sub-screen/out-screen flags describe mirroring, not data-relay support.
            entries.add(new AppRelayCatalog.Candidate((String) field(info, "packageName"), "",
                    AppRelayCatalog.Kind.RECENT, true, false, 0, ""));
        }
        return new AppRelayCatalog.Update(false, entries);
    }
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    static String packageOfProcess(String process) {
        if (process == null) throw new IllegalArgumentException("invalid_package");
        int suffix = process.indexOf('~');
        String pkg = suffix < 0 ? process : process.substring(0, suffix);
        if (suffix >= 0 && !process.substring(suffix + 1).matches("[1-9][0-9]{0,9}"))
            throw new IllegalArgumentException("invalid_native_process");
        AppRelayRequest.packageName(pkg); return pkg;
    }
    private XiaomiRelayCatalog() {}
}
