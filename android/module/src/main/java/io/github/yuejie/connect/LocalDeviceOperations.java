package io.github.yuejie.connect;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import org.json.JSONObject;

/** Real Android execution endpoint, shared by both brands. No inferred or cached values. */
final class LocalDeviceOperations {
    private LocalDeviceOperations() {}
    static String battery(Context context) {
        try {
            BatteryManager battery=context.getSystemService(BatteryManager.class);
            Intent state=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery==null||state==null)return "{}";
            int level=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            int plugged=state.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1);
            if(level<0||level>100||(plugged!=0&&plugged!=1&&plugged!=2&&plugged!=4&&plugged!=8))return "{}";
            return new JSONObject().put("level",level).put("plugged",plugged)
                .put("charging",charging(state.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN))).toString();
        } catch(org.json.JSONException|RuntimeException unavailable) { return "{}"; }
    }
    static Boolean charging(int status) {
        return switch(status){
            case BatteryManager.BATTERY_STATUS_CHARGING -> true;
            case BatteryManager.BATTERY_STATUS_DISCHARGING,BatteryManager.BATTERY_STATUS_NOT_CHARGING,BatteryManager.BATTERY_STATUS_FULL -> false;
            default -> null;
        };
    }
}
