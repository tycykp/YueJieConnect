"""Verify Oplus adapter entry points against original APK DEX, never JADX renamed methods."""
from pathlib import Path
import hashlib
import json
import zipfile
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / 'captures/device-interconnect-20261108/coloros17/com.oplus.linker/OPSynergy.apk'
SHA = 'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b'
L = lambda name: 'L' + name.replace('.', '/') + ';'
C = 'com.oplus.pantaconnect.sdk.connectionservice.connection.'
K = 'com.oplus.synergy.devicekit.'
S, LIST, FUT = 'Ljava/lang/String;', 'Ljava/util/List;', 'Ljava/util/concurrent/CompletableFuture;'
D, O, CB = L(C+'DisplayDevice'), L(C+'GlobalDeviceConnectOptions'), L(C+'GlobalDeviceConnectionCallback')
SR = L('com.oplus.pantaconnect.sdk.SealedResult')
required = {
 L(C+'ConnectionServiceClientsImpl'): {
  f'startGlobalDeviceDiscovery({L(C+"DeviceDiscoveryOptions")}{L(C+"GlobalDeviceDiscoveryCallback")}){SR}',
  f'stopGlobalDeviceDiscovery(){SR}', f'getAvailableDevices(){FUT}', f'getDisplayDeviceList(){FUT}',
  f'requestConnection({D}{O}{CB})V', f'confirmConnection({D}{L(C+"PairAction")}{L(C+"ConfirmType")})V'},
 L(C+'ConnectionServiceImpl'): {f'cancelConnection({D}){FUT}', f'closeConnection({D}{O}){FUT}'},
 L(C+'GlobalDeviceDiscoveryCallback'): {f'onDeviceFound({D})V', f'onDeviceLost({D})V'},
 CB: {f'onConnectionFailure({D}Ljava/lang/Throwable;)V'},
 SR: {'getResultCodeValue()I'},
 L(C+'DeviceDiscoveryOptions'): {'getDeviceTypes()Ljava/util/Set;', 'getDurationMillis()J'},
 D: {f'getProtocolDeviceId(){S}', f'getTempId(){S}', f'getDeviceType(){L("com.oplus.pantaconnect.sdk.DeviceType")}',
  '<init>('+S+S+L('com.oplus.pantaconnect.sdk.DeviceType')+S+L(C+'DeviceState')+L(C+'AccountState')+'ZI'+LIST+'I'+S+L('com.oplus.pantaconnect.sdk.discovery.DiscoveryStrategy')+S+L('com.oplus.pantaconnect.sdk.connection.VLinkType')+S+')V'},
 L(K+'OpDeviceServiceClient'): {
  f'queryOriginalDeviceListWithIds({LIST}{L(K+"callback.ISynergyDeviceInfoCallback")})V',
  f'queryOriginalDeviceList(I{L(K+"callback.ISynergyDeviceInfoCallback")})V',
  f'queryDeviceListByTypes({LIST}{L(K+"callback.ISynergyDeviceInfoCallback")})V'},
 L(K+'callback.ISynergyDeviceInfoCallback'): {f'onResult({LIST})V', f'onError(I{S})V'},
 L(K+'bean.SynergyDeviceInfo'): {'<init>()V', f'getDeviceId(){S}', f'setDeviceId({S})V', f'setName({S})V',
  f'setModelId({S})V', 'setDeviceType(I)V', 'setDeviceState(I)V', 'setDeviceProtocol(I)V', 'setPropertyList(Ljava/util/ArrayList;)V'},
 L(K+'bean.DeviceProperty'): {f'<init>({S}{S})V'},
 L(K+'SynergyDeviceManager'): {'<init>(Landroid/content/Context;)V', f'queryOriginalDeviceList({LIST}{L(K+"callback.ISynergyDeviceInfoCallback")})V', 'close()V'},
 L('com.oplus.linker.synergy.wisetransfer.clipboard.ClipBoardServiceHelper'): {'copyPureTextToClipBoard([BLandroid/content/Context;)V', 'copyCompleteDataToClip(Landroid/content/ClipData;Landroid/content/Context;)V'},
 L('com.oplus.linker.synergy.wisetransfer.clipboard.util.ClipBoardUtil'): {'getSystemClipData()Landroid/content/ClipData;'},
 L('com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.ClipBoardConsumerImpl'): {
  '<init>()V', 'getInstance(Landroid/content/Context;)V', 'copyToClip()V', 'copyToClipOld([B)V', 'getClipData()V', 'destroy()V',
  f'setStatusListener({S}{L("com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.ClipBoardServiceConsumer$StatusListener")})V'},
 L('com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.ClipBoardServiceConsumer$StatusListener'): {f'initSuccess({S})V'},
 L('com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.ClipBoardConsumerImpl$3'): {
  f'<init>({L("com.oplus.linker.synergy.wisetransfer.clipboard.icdf.consumer.ClipBoardConsumerImpl")})V',
  'onCompleted()V', 'onError(Ljava/lang/Throwable;)V', 'onNext(Ljava/lang/Object;)V'},
 'Ld83;': {'onCompleted()V', 'onError(Ljava/lang/Throwable;)V', 'onNext(Ljava/lang/Object;)V'},
 L('com.oplus.linker.synergy.Clipboard$Result'): {f'newBuilder(){L("com.oplus.linker.synergy.Clipboard$Result$Builder")}', 'getStatus()I'},
 L('com.oplus.linker.synergy.Clipboard$Result$Builder'): {f'setStatus(I){L("com.oplus.linker.synergy.Clipboard$Result$Builder")}', f'build(){L("com.oplus.linker.synergy.Clipboard$Result")}'},
}
assert hashlib.sha256(APK.read_bytes()).hexdigest() == SHA, 'Original APK changed'
evidence = []
with zipfile.ZipFile(APK) as apk:
    for entry in ['classes2.dex', 'classes3.dex', 'classes6.dex']:
        data = apk.read(entry)
        dex = DEX(data)
        for cls in dex.get_classes():
            name = cls.get_name()
            if name not in required:
                continue
            actual = {m.get_name()+m.get_descriptor().replace(' ', ''): m for m in cls.get_methods()}
            missing = required[name] - actual.keys()
            assert not missing, (name, sorted(missing))
            methods = [{"descriptor": m, "code_offset": actual[m].get_code_off()} for m in sorted(required.pop(name))]
            evidence.append(dict(class_name=name, dex=entry, dex_sha256=hashlib.sha256(data).hexdigest(), methods=methods))
        del dex
assert not required, required.keys()
out = ROOT / 'build/oplus-adapter-dex-evidence.json'
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(dict(apk_sha256=SHA, classes=evidence), indent=2), encoding='utf-8')
print(f'Verified {len(evidence)} original DEX classes, {sum(len(e["methods"]) for e in evidence)} descriptors.')
