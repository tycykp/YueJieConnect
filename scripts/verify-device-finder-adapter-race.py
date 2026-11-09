"""Exercise only the real Xiaomi finder adapter's queued card-release lifecycle.

Android scheduling/broadcasts and the authenticated transport are host doubles. This
is not OEM UI, physical playback, or shared transport validation. Existing controller
and original DEX checks are deliberately not rerun.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/device-finder-adapter-race"
MAIN = ROOT / "android/module/src/main/java/io/github/yuejie/connect"
TEST = ROOT / "android/module/src/test/java/io/github/yuejie/connect/DeviceFinderAdapterRaceCheck.java"
JDK = Path(r"C:\Program Files\Android\Android Studio\jbr\bin")
ANDROID = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-37.0/android.jar"
JSON = ROOT / "build/file-transfer-validation/json-src/org/json"
if not (JSON / "JSONObject.java").exists():
    JSON = ROOT / "build/notifications-validation/json-src/org/json"
if not (JSON / "JSONObject.java").exists():
    raise SystemExit("Existing local AOSP org.json test sources required; no network or device fallback.")

stubs = {
    "android/os/Looper.java": """package android.os;
public final class Looper {private static final Looper MAIN=new Looper();
public static Looper getMainLooper(){return MAIN;}}""",
    "android/os/Handler.java": """package android.os;
public final class Handler {public Handler(Looper looper){}
public boolean postDelayed(Runnable action,long delay){return true;}
public void removeCallbacks(Runnable action){}}""",
    "android/content/Context.java": """package android.content;
public abstract class Context {public abstract Context getApplicationContext();
public abstract void sendBroadcast(Intent intent);}""",
    "android/content/ContextWrapper.java": """package android.content;
public class ContextWrapper extends Context {private final Context base;
public ContextWrapper(Context base){this.base=base;}
public Context getApplicationContext(){return base.getApplicationContext();}
public void sendBroadcast(Intent intent){base.sendBroadcast(intent);}}""",
    "android/content/Intent.java": """package android.content;
public final class Intent {public Intent(String action){}
public Intent setClassName(String pkg,String cls){return this;}
public Intent addCategory(String category){return this;}
public Intent putExtra(String key,String value){return this;}
public Intent putExtra(String key,int value){return this;}
public Intent putExtra(String key,byte[] value){return this;}}""",
}
generated = []
for name, content in stubs.items():
    path = OUT / "sources" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    generated.append(path)
sources = [MAIN / "XiaomiFinderAdapter.java", MAIN / "XiaomiFinderCodec.java", TEST,
           *generated, *sorted(JSON.glob("*.java"))]
commands = [
    [JDK / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-cp", ANDROID,
     "-d", OUT / "android", MAIN / "XiaomiFinderAdapter.java", MAIN / "XiaomiFinderCodec.java", TEST],
    [JDK / "javac.exe", "--release", "17", "-encoding", "UTF-8", "-d", OUT / "classes", *sources],
    [JDK / "java.exe", "-cp", OUT / "classes", "io.github.yuejie.connect.DeviceFinderAdapterRaceCheck"],
]
results = []
for command in commands:
    result = subprocess.run([str(item) for item in command], cwd=ROOT, capture_output=True,
                            text=True, errors="replace", timeout=30)
    results.append(dict(command=[str(item) for item in command], exit_code=result.returncode,
                        output=result.stdout + result.stderr))
    print((result.stdout + result.stderr).strip())
    if result.returncode:
        break
record = dict(commands=results, source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                             for p in [*sources, Path(__file__)]},
              dependencies_sha256={str(ANDROID): hashlib.sha256(ANDROID.read_bytes()).hexdigest()},
              scope="Real Android API compilation plus host adapter lifecycle; no OEM UI/playback/APK/install/device operation")
(OUT / "verification.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
raise SystemExit(results[-1]["exit_code"])
