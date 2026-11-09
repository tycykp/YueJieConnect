"""Finder's session/status debug helper. Child ADB processes cannot consume window stdin."""
import base64,json,subprocess,time,uuid
ALLOWED={'bridge_status','bridge_runtime_status','bridge_listen','bridge_connect','bridge_confirm','bridge_stop'}
def command(serial,payload):
 if payload.get('op')not in ALLOWED:raise ValueError('Finder helper permits only session/status/runtime/cleanup; no business start RPC')
 request=uuid.uuid4().hex;encoded=base64.b64encode(json.dumps(payload).encode()).decode()
 result=subprocess.run(['C:/adb/adb.exe','-s',serial,'shell','am','start','-W','-n','io.github.yuejie.connect/.DebugControlActivity',
  '--es','request_id',request,'--es','command_b64',encoded],capture_output=True,stdin=subprocess.DEVNULL,timeout=15)
 if result.returncode or b'Error'in result.stdout or b'Exception'in result.stderr:raise RuntimeError('Restricted debug activity launch failed')
 until=time.monotonic()+9
 while time.monotonic()<until:
  raw=subprocess.check_output(['C:/adb/adb.exe','-s',serial,'shell','logcat','-d','-v','brief','-s','YueJieTest:I','*:S'],stdin=subprocess.DEVNULL,timeout=10).decode('utf-8','replace')
  for line in raw.splitlines():
   marker=request+' '
   if marker in line:return json.loads(base64.b64decode(line.split(marker,1)[1]))
  time.sleep(.2)
 raise TimeoutError('No debug response; no automatic state-changing retry')
