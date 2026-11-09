"""Only the new :ui support delegate; reuses old SCClient DEX signature proof."""
from pathlib import Path
import os,hashlib,json,subprocess
root=Path(__file__).resolve().parents[1]
out=root/'build/device-finder-repair/xiaomi-ui';out.mkdir(parents=True,exist_ok=True)
main=root/'android/module/src/main/java/io/github/yuejie/connect'
java=Path('C:/Program Files/Android/Android Studio/jbr/bin')
android=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/platforms/android-37.0/android.jar'
api=root/'build/device-finder-validation/libxposed-api.jar'
fixtures=root/'build/device-finder-repair/ui-host/sources'
extra={
'android/app/Application.java':'package android.app;public class Application {public static String process;public static String getProcessName(){return process;}}',
'com/miui/circulate/ringfind/sc/ui2client/SCClient.java':'''package com.miui.circulate.ringfind.sc.ui2client;import io.github.libxposed.api.XposedModule;public class SCClient {public boolean isSupportGetStatus(String id){return (Boolean)XposedModule.call(SCClient.class,"isSupportGetStatus",new Class[]{String.class},this,new Object[]{id},args->false);}}''',
}
generated=[]
for name,body in extra.items():
 p=out/'sources'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');generated.append(p)
source=main/'XiaomiFinderUiSupport.java';test=root/'scripts/device-finder-ui/XiaomiFinderUiCheck.java'
json_src=root/'build/file-transfer-validation/json-src/org/json'
inputs=[source,test,*generated,*fixtures.rglob('*.java'),*json_src.glob('*.java')]
shared=[main/name for name in ('BridgeClient.java','IBridge.java','NativeLogicalSessions.java')]
commands=[
 [java/'javac.exe','--release','17','-encoding','UTF-8','-d',out/'host',*inputs],
 [java/'java.exe','-cp',out/'host','io.github.yuejie.connect.XiaomiFinderUiCheck'],
 [java/'javac.exe','--release','17','-encoding','UTF-8','-cp',str(android)+os.pathsep+str(api),'-d',out/'android',source,*shared],
]
results=[]
for command in commands:
 p=subprocess.run([str(v)for v in command],capture_output=True,text=True,errors='replace',timeout=40)
 results.append(dict(command=[str(v)for v in command],exit_code=p.returncode,output=p.stdout+p.stderr));print(results[-1]['output'].strip())
 if p.returncode:break
record=dict(commands=results,source_sha256={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest()for p in [*inputs,*shared,Path(__file__)]},
 dependencies_sha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest()for p in (android,api)},
 reused_dex='build/device-finder-validation/verification.json SCClient.isSupportGetStatus(Ljava/lang/String;)Z, unchanged original APK',
 scope='New actual UI-process support delegate host behavior/real Android compile. No cloud trust-list mutation, new device/ringing scenario or old baseline rerun.')
(out/'verification.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(results[-1]['exit_code'])
