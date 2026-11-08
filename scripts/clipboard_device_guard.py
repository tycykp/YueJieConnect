"""Package-only foreground check. Never persist raw activity dumps or capture the screen."""
import re
import subprocess

PACKAGES = {'b59f8656': {'com.milink.service', 'com.xiaomi.mirror'},
            '3B161Q00YN200000': {'com.oplus.linker'}}


def foreground_component(dump):
    for line in dump.splitlines():
        if 'topResumedActivity' in line or 'mResumedActivity' in line:
            match = re.search(r'\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)', line)
            if match:
                return match.group(1), match.group(2)
    return None


def check_native_foreground(serial):
    value = subprocess.run(['adb', '-s', serial, 'shell', 'dumpsys', 'activity', 'activities'],
                           capture_output=True, timeout=15)
    component = foreground_component(value.stdout.decode('utf-8', 'replace')) if value.returncode == 0 else None
    if component is None or component[0] not in PACKAGES[serial]:
        raise RuntimeError('requires_foreground_native_activity: ' + serial)
    return '/'.join(component)
