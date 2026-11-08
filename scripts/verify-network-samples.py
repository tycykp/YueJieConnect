"""Check the exact OEM entry points used by this feature against existing local decompilation.
Read-only; this is sample provenance/signature evidence, not a runtime or network test.
"""
from pathlib import Path
import hashlib
import json
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[1]
xiaomi = Path("captures/device-interconnect-20261108/hyperos4/analysis/jadx/com.milink.service/sources")
oplus = Path("captures/device-interconnect-20261108/coloros17/analysis/jadx/linker/sources")
samples = {
    xiaomi / "com/miui/circulate/api/protocol/synergy/manager/CellularSynergyManager.java": [
        "void connectCellularTether(String str)", "void disconnectCellularTether(String str)",
        "int getCellularTetherState(String str)", "void onCellularStateChange(String str, int i10)"],
    xiaomi / "com/miui/circulate/api/protocol/synergy/cellular/CellularHelper.java": [
        '"miui.bluetooth.mible.EasyTetherCoreService"', '"com.xiaomi.bluetooth"',
        "connectHotspotForEasyTether(str, 6)", "disconnectCelluarTether(str)",
        "STATE_TETHERED_CELLULAR = 3", "STATE_TETHERED_WIFI = 4"],
    oplus / "com/oplus/linker/synergy/common/utils/QuickActionsUtils.java": [
        "void setShareNetworkEnabled(Context context, boolean enabled)",
        'publishDataBusMessage("PhoneUsePhoneNetStatus", enabled ? 1 : 0)',
        "publishDataBusMessage(Config.DEVICE_CARD_PAD_RECOVER_PHONE_NETWORK_STATUS, 1)"],
    oplus / "com/oplus/linker/synergy/common/utils/PadPanelDataBusHelper.java": [
        'SHARE_NETWORK = "share_network"', 'SHARE_NETWORK_ENABLE = "share_network_enable"',
        'STATUS_ON = "3"', 'STATUS_STARTING = "2"', 'STATUS_STOPPING = "4"'],
    oplus / "com/oplus/linker/synergy/ui/widgets/MyPadDeviceOnPhonePreference.java": [
        "private void toggleShareNetwork()", "private MyDeviceMessage mMyDeviceMessage", "public void setActionState(", "mQuickActionViewMap;"],
    oplus / "com/oplus/linker/synergy/ui/widgets/MyPhoneDeviceOnTabletPreference.java": [
        "private void onShareNetworkClick(QuickActionItemView", "private MyDeviceMessage mMyDeviceMessage",
        '"share_network_for_card", str', "public void setActionState(", "mQuickActionViewMap;"],
    oplus / "com/oplus/linker/synergy/ui/widgets/QuickActionItemView.java": ["enum State", "IDLE,", "LOADING,", "ON"],
}
result = {"time_utc": datetime.now(timezone.utc).isoformat(), "scope": "existing JADX source signatures, not device execution",
          "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "samples": []}
for relative, needles in samples.items():
    path = root / relative
    data = path.read_bytes()
    source = data.decode("utf-8")
    missing = [needle for needle in needles if needle not in source]
    result["samples"].append({"path": str(relative), "sha256": hashlib.sha256(data).hexdigest(),
                              "checked": needles, "missing": missing})
out = root / "build/network-sharing/oem-samples.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
failed = [entry for entry in result["samples"] if entry["missing"]]
if failed:
    print(json.dumps(failed, ensure_ascii=True))
    raise SystemExit(1)
print(f"PASS: {len(samples)} OEM source files, {sum(map(len, samples.values()))} signature/constants checks; no device operations.")
print("Evidence: build/network-sharing/oem-samples.json")
