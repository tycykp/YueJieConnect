"""ADB-only bridge command. Requires the debug APK's DUMP-protected activity."""
import base64
import json
import subprocess
import sys
import time
import uuid

def command(serial, payload):
    request_id=uuid.uuid4().hex
    encoded=base64.b64encode(json.dumps(payload).encode()).decode()
    launch=subprocess.run(['adb','-s',serial,'shell','am','start','-W','-n',
        'io.github.yuejie.connect/.DebugControlActivity','--es','request_id',request_id,
        '--es','command_b64',encoded],capture_output=True,timeout=15)
    if b'Error' in launch.stdout or b'Exception' in launch.stderr:
        raise RuntimeError('Debug activity launch failed: '+launch.stderr.decode('utf-8','replace'))
    deadline=time.monotonic()+9
    while time.monotonic()<deadline:
        log=subprocess.check_output(['adb','-s',serial,'shell','logcat','-d','-v','brief','-s','YueJieTest:I','*:S']).decode('utf-8','replace')
        for line in log.splitlines():
            marker=request_id+' '
            if marker in line:
                return json.loads(base64.b64decode(line.split(marker,1)[1]))
        time.sleep(.2)
    raise TimeoutError('No debug response (no state-changing retry performed)')

if __name__=='__main__':
    result=command(sys.argv[1],json.loads(sys.argv[2]))
    print(json.dumps(result,ensure_ascii=False,indent=2))
