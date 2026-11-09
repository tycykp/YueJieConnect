"""Check only the newly used native sync symbols and original SDK ingress in pinned APK DEX."""
import hashlib
import json
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from androguard.core.dex import DEX
from loguru import logger

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
S = 'Ljava/lang/String;'
M = 'Lcom/xiaomi/mirror/message/RelayAppMessage;'
X = 'Lcom/xiaomi/mirror/relay/y0;'
checks = [
    ('hyperos4/com.xiaomi.mirror/MirrorOS4.apk', '9ad400eb3d4ef69ab9f851bb9381ddeb03cee715fa17a795aef91141dbadd356', {
        'Lcom/xiaomi/mirror/relay/s0;': ({'W()Lcom/xiaomi/mirror/relay/s0;', f'b(){S}', f'R({M}{S}I)V', f'S({M}{S})V'}, {'d':'Lcom/xiaomi/mirror/relay/e;'}),
        'Lcom/xiaomi/mirror/relay/e;': ({f'y({S}{S}J)V', f'v({S})Z', f't({S})Z', 'I(Landroid/os/Bundle;)V',
                                       'G(Landroid/os/Bundle;)V', 'o(Landroid/os/Bundle;)V',
                                       f'C({S}Lcom/xiaomi/mirror/u;)V', f'K({S}Lcom/xiaomi/mirror/u;Z)V'}, {'j':'J'}),
        X: ({f'a({X}{S}{S}{S})V', 'm()Lcom/xiaomi/mirror/relay/F0;'}, {'k':'Lcom/xiaomi/mirror/relay/a;'}),
        'Lcom/xiaomi/mirror/p0;': ({'n()Lcom/xiaomi/mirror/p0;', f'j({S})Lcom/xiaomi/mirror/p0$a;'}, {}),
        'Lcom/xiaomi/mirror/p0$a;': ({'p()Z'}, {}),
        'Lcom/xiaomi/mirror/relay/a;': (set(), {'a':S, 'c':S, 'd':S}),
        'Lcom/xiaomi/mirror/relay/F0;': ({'f()V', 'd(I)V', 'g()V'}, {}),
        'Lcom/xiaomi/mirror/relay/B0;': ({'b()Lcom/xiaomi/mirror/relay/B0;', 'g()Z'}, {}),
        'Lcom/xiaomi/mirror/u;': ({'j0()V', 'M1()V'}, {}),
        'Lcom/xiaomi/mirror/provider/CallProvider;': ({f'call({S}{S}Landroid/os/Bundle;)Landroid/os/Bundle;'}, {}),
        'Lq4/B0;': ({f'm({S}){S}', f'l({S})I'}, {}),
        'Ly3/p;': ({'e()Z'}, {}),
    }),
    ('coloros17/com.oplus.linker/OPSynergy.apk', 'dfac6057390248180adf559b80cb9521c44c952a7c63a4069ba48fbcc902f20b', {
        'Lcom/oplus/synergy/engine/RelayService$1;': ({f'sendRelayData(ILcom/oplus/synergy/engine/ISendDataCallback;{S})V',
            f'triggerRelay({S}Z)V', 'checkChannelStatus()I', 'registerReceiveDataCallback(Lcom/oplus/synergy/engine/IReceivePayloadCallback;)V'}, {}),
        'Lcom/oplus/synergy/bean/RelayInfoStatus;': ({'getDataRelayList()Ljava/util/List;'},
            {'INSTANCE':'Lcom/oplus/synergy/bean/RelayInfoStatus;'}),
        'Lcom/oplus/synergy/bean/DataRelayInfo;': ({f'getPkgName(){S}', f'getRelayType(){S}'}, {}),
        'Lcom/oplus/synergy/relay/manager/RelayPayloadManager;': ({f'writeJsonData({S}Lcom/oplus/synergy/engine/ISendDataCallback;)V'}, {'mTaskType':'Lcom/oplus/synergy/utils/TaskType;'}),
        'Lcom/oplus/synergy/relay/manager/runnable/RelayEventReadRunnable;': ({'<init>([BLjava/util/concurrent/ConcurrentHashMap;Lcom/oplus/synergy/utils/TaskType;)V','run()V'}, {}),
        'Lcom/oplus/synergy/utils/RelayAbilityUtil;': ({f'parseJsonStr({S})Ljava/util/ArrayList;'}, {}),
        'Lcom/oplus/synergy/linkmanager/LinkManager;': (set(), {'mIReceiveDataCallbacks':'Ljava/util/concurrent/ConcurrentHashMap;'}),
        'Lcom/oplus/pantaconnect/sdk/connectionservice/ability/Ability;': ({f'getAppIdByPackageName({S})Ljava/util/concurrent/CompletableFuture;'}, {}),
        'Lcom/oplus/synergy/engine/IReceivePayloadCallback;': ({f'onRelayPayloadReceived({S}I)V'}, {}),
        'Lcom/oplus/synergy/service/SynergyCoreService;': ({'onStartCommand(Landroid/content/Intent;II)I'}, {}),
    }),
]
evidence = []
for path, pin, pending in checks:
    apk = ROOT / 'captures/device-interconnect-20261108' / path
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == pin, path
    with zipfile.ZipFile(apk) as archive:
        for entry in sorted(n for n in archive.namelist() if n.endswith('.dex')):
            if not pending:
                break
            data = archive.read(entry)
            for cls in DEX(data).get_classes():
                name = cls.get_name()
                if name not in pending:
                    continue
                required_methods, required_fields = pending.pop(name)
                methods = {m.get_name()+m.get_descriptor().replace(' ', ''):m for m in cls.get_methods()}
                fields = {f.get_name():f.get_descriptor() for f in cls.get_fields()}
                assert required_methods <= methods.keys(), (name, required_methods-methods.keys())
                assert all(fields.get(field) == desc for field, desc in required_fields.items()), (name, fields)
                instructions = {sig:[f'{i.get_name()} {i.get_output()}' for i in methods[sig].get_instructions()] for sig in required_methods}
                if name == 'Lq4/B0;':
                    assert any('~' in line for line in instructions[f'm({S}){S}']), instructions
                if name == 'Ly3/p;':
                    assert any('pref_key_connectivity_relay_app_state' in line for line in instructions['e()Z']), instructions
                if name == X:
                    assert any('->t0(' in line for line in instructions[f'a({X}{S}{S}{S})V']), instructions
                if name == 'Lcom/xiaomi/mirror/relay/e;':
                    assert any('->i J' in line and line.startswith('iput-wide') for line in instructions['I(Landroid/os/Bundle;)V']), instructions
                evidence.append(dict(apk=path,apk_sha256=pin,dex=entry,dex_sha256=hashlib.sha256(data).hexdigest(),
                                     class_name=name, fields={field:fields[field] for field in required_fields},methods=instructions))
    assert not pending, pending
out = ROOT / 'build/app-relay-validation'
out.mkdir(parents=True, exist_ok=True)
(out/'dex-repair-evidence.json').write_text(json.dumps(dict(utc=datetime.now(timezone.utc).isoformat(),
    script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),result='passed',classes=evidence),ensure_ascii=False,indent=2),encoding='utf-8')
print(f'PASS: {len(evidence)} newly used native sync classes; pinned APKs, exact signatures and fields.')
