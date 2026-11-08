"""Verify only app-relay runtime symbols against the captured original DEX files."""
import hashlib
import json
import zipfile
import sys
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger
logger.remove()
ROOT = Path(__file__).resolve().parents[1]
S = 'Ljava/lang/String;'
M = 'Lcom/xiaomi/mirror/message/Message;'
E = 'Lcom/xiaomi/mirror/message/SimpleEventMessage;'
specs = [
    ('hyperos4/com.xiaomi.mirror/MirrorOS4.apk', '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
        'Lcom/xiaomi/mirror/relay/e;': {'J(Landroid/os/Bundle;)LU3/d;', 'o(Landroid/os/Bundle;)V', f'D({E}Z)V'},
        'Lq4/A;': {f'O({M}{S})V', f'R({M}{S}Z)V'},
        'Lq4/j;': {f'o(Landroid/content/Context;{S}){S}'},
        E: {f'syncAppDataToServerResponseMessage(JI){E}'},
        'Lcom/xiaomi/mirror/j0;': {'m()Lcom/xiaomi/mirror/j0;', 'z()Z'},
        'Lcom/xiaomi/mirror/relay/y0;': {'B(Lcom/xiaomi/mirror/message/RelayAppIntentMessage;)Z', 'J(Lcom/xiaomi/mirror/message/RelayBrowserMessage;)Z', f'K(Landroid/content/Context;{S}Landroid/net/Uri;Landroid/os/Bundle;)Z'},
    }),
    ('coloros17/com.oplus.linker/OPSynergy.apk', 'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b', {
        'Lcom/oplus/synergy/utils/RelayUtils;': {f'getSwitchStatus(Landroid/content/Context;{S})Z', f'checkLocalAbility({S})Z', f'createDLIntent({S}{S})Landroid/content/Intent;', f'createDeepLink(Ljava/util/ArrayList;{S}){S}', f'dealWithReceived({S}I)V'},
        'Lcom/oplus/synergy/engine/ISendDataCallback;': {'onRelayDataSent()V', 'onSendRelayDataFailed(I)V'},
        'Lcom/oplus/pantaconnect/sdk/connectionservice/ability/Ability;': {'create()Lcom/oplus/pantaconnect/sdk/connectionservice/ability/Ability;', f'checkLocalAbility(I{S})Ljava/util/concurrent/CompletableFuture;'},
        'Lcom/oplus/synergy/bean/AbilityProfile;': {'<init>()V', f'setKey({S})V', f'setValue({S})V'},
        'Lcom/oplus/synergy/linkmanager/LinkManager;': {'sendRelayInfo([BLcom/oplus/synergy/engine/ISendDataCallback;Lcom/oplus/synergy/utils/TaskType;)V'},
        'Lcom/oplus/synergy/relay/codec/RelayPayloadCodec;': {f'encodePayload({S}SB)[B', f'decodePayload([B){S}'},
    }),
]
integration = '--integration-only' in sys.argv
round2 = '--round2-only' in sys.argv
if integration:
    specs[0][2].clear()
    specs[1][2].clear()
    specs[0][2].update({
        'Lcom/xiaomi/mirror/message/RelayAppMessage;': set(),
        'Lcom/xiaomi/mirror/message/RecentAppsMessage;': set(),
        'Lcom/xiaomi/mirror/message/RecentAppsMessage$RecentAppsInfo;': set(),
        'LV3/V;': {'I(Landroid/net/Uri;)Lcom/xiaomi/mirror/message/ClipDataMessage$FileInfo;'},
        'Lcom/xiaomi/mirror/message/ClipDataMessage$FileInfo;': set(),
        'Lcom/xiaomi/mirror/message/RelayOfficeMessage;': set(),
    })
    specs[1][2].update({
        'Lcom/oplus/synergy/bean/RelayInfo;': {f'{name}(){S}' for name in ('getPkgName', 'getActivityName', 'getAppIntentStatus', 'getRelayType')} | {'getDeviceType()I'},
        'Lcom/oplus/synergy/bean/AbilityProfile;': {f'getKey(){S}', f'getValue(){S}'},
    })
if round2:
    specs[0][2].clear()
    specs[1][2].clear()
    specs[0][2].update({
        'Lcom/xiaomi/mirror/relay/y0;': {'n()Lcom/xiaomi/mirror/relay/y0;', f'R({S}Lcom/xiaomi/mirror/message/RelayAppMessage;{S})V'},
        'Lcom/xiaomi/mirror/message/RelayAppMessage;': {'<init>()V'},
    })
    specs[1][2].update({
        'Lcom/oplus/synergy/utils/RelayUtils;': {f'dealWithReceived({S}I)V', 'getPeerRelaySwitchStatus(Landroid/content/Context;)Z'},
        'Lcom/oplus/synergy/relay/manager/RelayManagerUtil;': {'parseDataAndCallback([BLjava/util/concurrent/ConcurrentHashMap;)V'},
        'Lcom/oplus/synergy/utils/Config;': set(),
        'Lcom/oplus/synergy/utils/StartEngineUtils;': {'queryUserAgreement(Landroid/content/Context;Lcom/oplus/synergy/callback/UserAgreementStatusCallback;)V'},
        'Lcom/oplus/synergy/callback/UserAgreementStatusCallback;': {'onResult(Ljava/lang/Boolean;)V'},
        'Lcom/oplus/synergy/utils/FeatureConfigUtil;': {'isTabletDevice()Z'},
        'Lcom/oplus/synergy/relay/uiinteraction/DockInteraction;': {'updateUI(Landroid/content/Context;Ljava/util/ArrayList;)V', f'createJson({S}{S}I{S}){S}'},
        'Lcom/oplus/synergy/service/SynergyCoreService;': {'onStartCommand(Landroid/content/Intent;II)I', f'relayImmediateWrapper(I{S}{S})V', f'startCastRelay({S})V'},
        'Lcom/oplus/synergy/linkmanager/LinkManager;': {'getInstance()Lcom/oplus/synergy/linkmanager/LinkManager;', 'getONetInitFlag()Z'},
    })
field_specs = {
    'Lcom/xiaomi/mirror/message/RelayAppMessage;': {'type':'I', 'mDstDeviceType':'I', 'processName':S, 'appName':S,
        'isHideIcon':'Z', 'unregisterRelay':'Z', 'isRelay':'Z', 'supportHandoff':'Z', 'description':S,
        'iconBitmap':'Landroid/graphics/Bitmap;', 'receiveTime':'J'},
    'Lcom/xiaomi/mirror/message/RecentAppsMessage;': {'action':'I', 'appInfoList':'Ljava/util/List;'},
    'Lcom/xiaomi/mirror/message/RecentAppsMessage$RecentAppsInfo;': {'packageName':S},
    'Lcom/xiaomi/mirror/message/ClipDataMessage$FileInfo;': {'url':S},
    'Lcom/xiaomi/mirror/message/RelayOfficeMessage;': {'downloadPATH':S, 'downloadURL':S, 'downloadSize':'J', 'deviceId':S},
}
evidence = []
for relative, expected, required in specs:
    apk = ROOT / 'captures/device-interconnect-20261108' / relative
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == expected, relative
    with zipfile.ZipFile(apk) as archive:
        for entry in sorted(n for n in archive.namelist() if n.endswith('.dex')):
            if not required:
                break
            data = archive.read(entry)
            for cls in DEX(data).get_classes():
                name = cls.get_name()
                if name not in required:
                    continue
                methods = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
                signatures = required.pop(name)
                assert signatures <= methods.keys(), (name, signatures-methods.keys())
                fields = {f.get_name(): f.get_descriptor() for f in cls.get_fields()}
                for field, descriptor in field_specs.get(name, {}).items():
                    assert fields.get(field) == descriptor, (name, field, fields)
                if name == 'Lcom/xiaomi/mirror/relay/e;':
                    assert fields['g'] == S and fields['i'] == 'J' and fields['c'] == 'Lcom/xiaomi/mirror/relay/e$e;', fields
                if round2 and name == 'Lcom/oplus/synergy/utils/RelayUtils;':
                    instructions = '\n'.join(i.get_output() for i in methods[f'dealWithReceived({S}I)V'].get_instructions())
                    for literal in ['com.example.datarelaydemo', 'com.example.datarelaydemo01', 'com.example.datarelaydemo.ui.EditActivity',
                                    'com.example.datarelaydemo.ui.EditActivity01', 'ExtraContentData', 'com.example.richtext.action.application.relay']:
                        assert literal in instructions, literal
                if round2 and name == 'Lcom/oplus/synergy/utils/Config;':
                    constants = {f.get_name(): f.get_init_value().get_value() for f in cls.get_fields() if f.get_init_value() is not None}
                    for field, expected_value in {'RELAY_EXTEND_KEY_DOC_DATA': b'Relay_Extend_Key_Doc_Data',
                                                  'RELAY_EXTEND_KEY_DOC_TITLE': b'Relay_Extend_Key_Doc_Title',
                                                  'SP_NAME_ICON': b'sp_icon', 'START_TYPE': b'startService',
                                                  'KEY_INTENT': b'intent_key', 'METHOD_UPDATE_DOCKER_EXTRA_ITEM': b'update_docker_extra_item',
                                                  'KEY_RELAY_SOURCE_PKG_NAME': b'source_pkg_name',
                                                  'KEY_RELAY_SOURCE_ACTIVITY_NAME': b'source_activity_name'}.items():
                        actual = constants[field]
                        if isinstance(actual, str): actual = actual.encode('utf-8')
                        assert actual == expected_value, (field, actual)
                    for field in ['ACTION_APPLICATION_RELAY_SOURCE_CLICK', 'CLASS_NAME', 'KEY_RELAY_TYPE', 'KEY_ICON_URL', 'KEY_SUB_ICON_URL',
                                  'KEY_CLICK_INTENT_KEY', 'KEY_START_TYPE', 'KEY_GUID_TIP_RES', 'KEY_EXTRA_DATA', 'KEY_ICON', 'KEY_APP_NAME',
                                  'RELAY_EXTEND_KEY_REMIND_TYPE']:
                        assert fields.get(field) == S, field
                evidence.append(dict(apk=relative, apk_sha256=expected, dex=entry, dex_sha256=hashlib.sha256(data).hexdigest(),
                                     class_name=name, fields=fields if name.endswith('/relay/e;') or name in field_specs else {},
                                     methods={sig: [f'{i.get_name()} {i.get_output()}' for i in methods[sig].get_instructions()] for sig in sorted(signatures)}))
    assert not required, required
out = ROOT / 'build/app-relay-validation'
out.mkdir(parents=True, exist_ok=True)
(out / ('dex-round2-evidence.json' if round2 else 'dex-integration-evidence.json' if integration else 'dex-evidence.json')).write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'PASS: {len(evidence)} original DEX classes; exact methods, Xiaomi owner fields, and both APK SHA256 pins.')
