package io.github.qiangjian.connect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** RelayInfo and icon AbilityProfile conversion; never calls DockInteraction with an unbound peer. */
final class OplusRelayCatalog {
    static AppRelayCatalog.Update relayInfo(Object info) throws ReflectiveOperationException {
        AppRelayCatalog.requireClass(info, "com.oplus.synergy.bean.RelayInfo");
        return candidate(string(info, "getPkgName"), "", string(info, "getActivityName"),
                string(info, "getAppIntentStatus"), string(info, "getRelayType"),
                (Integer) info.getClass().getMethod("getDeviceType").invoke(info));
    }
    static AppRelayCatalog.Update profiles(List<?> profiles) throws ReflectiveOperationException {
        if (profiles == null || profiles.size() > 48) throw new IllegalArgumentException("invalid_relay_profile");
        Map<String, String> fields = new HashMap<>();
        for (Object profile : profiles) {
            AppRelayCatalog.requireClass(profile, "com.oplus.synergy.bean.AbilityProfile");
            String key = AppRelayCatalog.text(string(profile, "getKey"), 128);
            String value = string(profile, "getValue");
            if (key.isEmpty() || fields.containsKey(key)) throw new IllegalArgumentException("invalid_relay_profile");
            // Icons are not decoded or retained. Even the discarded field must be bounded.
            if (value != null && value.length() > ("Relay_Extend_Key_Icon_Data".equals(key) ? 131072 : 512))
                throw new IllegalArgumentException("invalid_relay_profile");
            fields.put(key, "Relay_Extend_Key_Icon_Data".equals(key) ? "" : AppRelayCatalog.text(value, 512));
        }
        if (!"2".equals(fields.get("Relay_Base_Key_Payload_Type"))) throw new IllegalArgumentException("not_relay_icon_profile");
        String type = fields.getOrDefault("Relay_Extend_Key_Peer_Device_Type", "0");
        if (!type.matches("[0-9]{1,3}")) throw new IllegalArgumentException("invalid_device_type");
        return candidate(fields.get("Relay_Base_Key_Pkg_Name"), fields.get("Relay_Base_Key_APP_Name"),
                fields.get("Relay_Extend_Key_Activity_Name"), fields.get("Relay_Extend_Key_Icon_Status"),
                fields.get("Relay_Extend_Key_Relay_Type"), Integer.parseInt(type));
    }
    private static AppRelayCatalog.Update candidate(String pkg, String label, String activity, String status, String type, int device) {
        if (!"show".equals(status) && !"hide".equals(status)) throw new IllegalArgumentException("invalid_icon_status");
        if ("hide".equals(status) && (pkg == null || pkg.isEmpty() || "com.oplus.linker".equals(pkg)))
            return new AppRelayCatalog.Update(true, List.of());
        boolean data = "1".equals(type);
        if (!data && !"2".equals(type) && "show".equals(status)) throw new IllegalArgumentException("invalid_relay_type");
        return new AppRelayCatalog.Update(false, List.of(new AppRelayCatalog.Candidate(pkg, label,
                data ? AppRelayCatalog.Kind.APP : AppRelayCatalog.Kind.CAST, "show".equals(status),
                data && "show".equals(status) && device == 1, device, activity)));
    }
    private static String string(Object object, String method) throws ReflectiveOperationException {
        return (String) object.getClass().getMethod(method).invoke(object);
    }
    private OplusRelayCatalog() {}
}
