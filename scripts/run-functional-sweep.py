"""One-pass test evidence. No production edits and no automatic state-changing retry."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import queue
import threading
import uuid
from datetime import datetime, timezone

from debug_control import command

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/functional-test-20261108'
APK = ROOT / 'build/native-pairing-validation/module-v19-round2-release-candidate.apk'
INPUTS = ROOT / 'build/native-pairing-validation/v19-round2-release-candidate-inputs.json'
DEVICES = ['b59f8656', '3B161Q00YN200000']
OWNER = '01a1bbcf-5271-7900-bb0d-bee38aff77b9'
EXPECTED = '2bac018151aab2c48c2e76681d6f4ee43034bbc375e2880288264913cff112e7'
PKG = 'io.github.qiangjian.connect'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def adb(serial, *args):
    result = subprocess.run(['adb', '-s', serial, *args], capture_output=True, timeout=45)
    if result.returncode or b'Exception' in result.stderr or b'Error:' in result.stdout:
        raise RuntimeError('adb command failed: ' + args[0])
    return result.stdout.decode('utf-8', 'replace')


def require_lock():
    path = ROOT / 'build/coordination-locks/devices.lock'
    if not path.exists() or 'functional-test ' + OWNER not in path.read_text(encoding='utf-8-sig'):
        raise RuntimeError('Missing exclusive device lease')


def baseline(record):
    inputs = json.loads(INPUTS.read_text(encoding='utf-8-sig'))
    hashes = {name: digest(ROOT / name) if (ROOT / name).exists() else None for name in inputs}
    mismatches = {name: {'expected': old, 'actual': hashes[name]}
                  for name, old in inputs.items() if hashes[name] != old.lower()}
    record.update(apk_sha256=digest(APK), input_count=len(inputs), mismatches=mismatches,
                  source_sha256=hashes, input_ledger_sha256=digest(INPUTS),
                  reused_public_ledger='build/native-pairing-validation/v19-final-ledger.json')
    if record['apk_sha256'] != EXPECTED or mismatches:
        raise RuntimeError('Artifact/input mismatch; no rebuild or repair performed')
    clipboard = {p: h for p, h in hashes.items() if 'Clipboard' in p}
    write('clipboard-input-ledger.json', {'apk_sha256': EXPECTED, 'source_sha256': clipboard,
          'scope': 'Candidate input provenance; independent tests remain in clipboard workstream'})


def prepare(record):
    require_lock()
    prior = json.loads((OUT / 'baseline.json').read_text(encoding='utf-8'))
    if prior['result'] != 'passed' or digest(APK) != EXPECTED:
        raise RuntimeError('Verified baseline required')
    record['devices'] = {}
    # This call follows an explicit user report that the missing device has connected.
    record['connected_after_user_report'] = subprocess.check_output(['adb', 'devices', '-l'], timeout=15).decode()
    if not all(re.search(r'^' + re.escape(s) + r'\s+device\b', record['connected_after_user_report'], re.M) for s in DEVICES):
        raise RuntimeError('Both named devices must be online')
    for serial in DEVICES:
        row = record['devices'][serial] = {}
        row['model'] = adb(serial, 'shell', 'getprop', 'ro.product.model').strip()
        row['stage'] = 'stop_old_hosts_before_update'
        packages = ['com.xiaomi.mirror', 'com.milink.service'] if serial == DEVICES[0] else ['com.oplus.linker', 'com.coloros.findmyphone']
        for package in packages:
            adb(serial, 'shell', 'am', 'force-stop', package)
        row['stage'] = 'install_once'
        row['install_output'] = adb(serial, 'install', '-r', str(APK))
        if 'Success' not in row['install_output']:
            raise RuntimeError('Install did not report Success')
        row['stage'] = 'verify_installed_apk'
        path = adb(serial, 'shell', 'pm', 'path', PKG).strip()
        if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk', path):
            raise RuntimeError('Unexpected installed APK path')
        row['installed_sha256'] = adb(serial, 'shell', 'sha256sum', path[8:]).split()[0]
        if row['installed_sha256'] != EXPECTED:
            raise RuntimeError('Installed APK mismatch')
        row['stage'] = 'ready'
    # Start original OEM UI once after the updated module is installed.
    record['native_ui'] = {}
    record['native_ui']['oplus'] = adb(DEVICES[1], 'shell', "su -c 'am start -W -n com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity'")
    record['native_ui']['xiaomi'] = adb(DEVICES[0], 'shell', 'monkey', '-p', 'com.milink.service', '-c', 'android.intent.category.LAUNCHER', '1')
    record['status'] = {s: command(s, {'op': 'bridge_status'}) for s in DEVICES}


def pair(record):
    require_lock()
    states = {s: command(s, {'op': 'bridge_status'})['bridge'] for s in DEVICES}
    record['initial'] = states
    if not all(v.get('phase') in ['stopped', 'disconnected'] for v in states.values()):
        raise RuntimeError('Unexpected existing session; no destructive pairing retry')
    address = adb(DEVICES[0], 'shell', 'ip', '-4', 'addr', 'show', 'wlan0')
    match = re.search(r'inet (\d+\.\d+\.\d+\.\d+)/', address)
    if not match:
        raise RuntimeError('Xiaomi WLAN address unavailable')
    record['listen'] = command(DEVICES[0], {'op': 'bridge_listen', 'port': 38271})
    record['connect'] = command(DEVICES[1], {'op': 'bridge_connect', 'address': match.group(1) + ':38271'})
    deadline = time.monotonic() + 18
    while True:
        states = {s: command(s, {'op': 'bridge_status'})['bridge'] for s in DEVICES}
        record['handshake'] = states
        if all(v.get('phase') == 'awaiting_confirmation' for v in states.values()):
            break
        if time.monotonic() >= deadline or any(v.get('last_error') for v in states.values()):
            raise RuntimeError('Handshake did not reach bilateral confirmation')
        time.sleep(0.5)
    a, b = (states[s] for s in DEVICES)
    if not re.fullmatch('[0-9a-f]{64}', a.get('session', '')) or not a.get('comparison_code') or a['session'] != b.get('session') or a['comparison_code'] != b.get('comparison_code'):
        raise RuntimeError('Current session/comparison code mismatch; not confirmed')
    record['session_compared'] = True
    record['session'] = a['session']
    record['comparison_code'] = a['comparison_code']
    operations = ['device_profile', 'battery_status', 'clipboard_write', 'clipboard_rich_write', 'device_finder', 'notification_post', 'notification_remove', 'notification_action']
    record['confirm'] = {s: command(s, {'op': 'bridge_confirm', 'session': a['session'], 'operations': operations}) for s in DEVICES}
    deadline = time.monotonic() + 8
    while True:
        states = {s: command(s, {'op': 'bridge_status'})['bridge'] for s in DEVICES}
        record['final'] = states
        if all(v.get('phase') == 'active' and v.get('local_confirmed') and v.get('remote_confirmed') for v in states.values()):
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('Confirmed session not active')
        time.sleep(0.5)
    record['scope'] = 'Real authenticated bridge; original Pantaconnect/DataBus/OAF connection separately tested'


def native_ui(record):
    require_lock()
    record['xiaomi_launch'] = adb(DEVICES[0], 'shell', 'am', 'start', '-W', '-n', 'com.milink.service/com.miui.circulate.world.CirculateWorldActivity')
    record['screens'] = {}
    time.sleep(1.5)
    for serial in DEVICES:
        image = subprocess.check_output(['adb', '-s', serial, 'exec-out', 'screencap', '-p'], timeout=20)
        path = OUT / (serial + '-native-ui.png')
        path.write_bytes(image)
        record['screens'][serial] = str(path.relative_to(ROOT))
    record['status_after_native_hosts_started'] = {s: command(s, {'op': 'bridge_status'}) for s in DEVICES}


def profiles(record):
    require_lock()
    record['native_api'] = {}
    for serial, package, action, tag in [
        (DEVICES[0], 'com.milink.service', 'XIAOMI_PROFILE_PROBE', 'QiangJianXiaomiTest'),
        (DEVICES[1], 'com.oplus.linker', 'OPLUS_PROFILE_PROBE', 'QiangJianOplusTest')]:
        row = record['native_api'][serial] = {}
        before = adb(serial, 'shell', 'logcat', '-d', '-v', 'epoch', '-s', tag + ':I', '*:S').splitlines()
        row['trigger_once'] = adb(serial, 'shell', 'am', 'broadcast', '-a', 'io.github.qiangjian.connect.' + action, '-p', package)
        deadline = time.monotonic() + 15
        while True:
            lines = [v for v in adb(serial, 'shell', 'logcat', '-d', '-v', 'epoch', '-s', tag + ':I', '*:S').splitlines() if v not in before]
            row['new_probe_logs'] = lines
            if any(any(k in v for k in ['native_onResponse', 'native_onSyncFail', 'native_timeout', 'probe_error', 'native_battery_received', 'native_query_error', 'probe_peer_unavailable']) for v in lines):
                break
            if time.monotonic() >= deadline:
                row['timeout'] = True
                break
            time.sleep(0.4)
        row['native_result_received'] = any('native_onResponse' in v or 'native_battery_received' in v for v in lines)
    record['bridge_query_peer'] = {s: command(s, {'op': 'bridge_query_peer'}) for s in DEVICES}
    record['scope'] = 'Original vendor DeviceProfile/DeviceKit API to real remote execution and original callback; UI connection separately assessed'
    if not all(v['native_result_received'] for v in record['native_api'].values()):
        raise RuntimeError('At least one native battery/profile callback failed; no trigger repeated')


def native_select(record):
    require_lock()
    # Coordinates come from the two screenshots captured by native-ui.
    record['xiaomi_card_tap'] = adb(DEVICES[0], 'shell', 'input', 'tap', '500', '550')
    record['oplus_add_device_tap'] = adb(DEVICES[1], 'shell', 'input', 'tap', '280', '2485')
    time.sleep(2)
    for serial in DEVICES:
        path = OUT / (serial + '-native-select.png')
        path.write_bytes(subprocess.check_output(['adb', '-s', serial, 'exec-out', 'screencap', '-p'], timeout=20))
    record['scope'] = 'Navigation to original device card/search; screenshot observation required'


def native_connect(record):
    require_lock()
    record['tap_once'] = adb(DEVICES[1], 'shell', 'input', 'tap', '620', '1000')
    time.sleep(12)
    for serial in DEVICES:
        path = OUT / (serial + '-native-connect.png')
        path.write_bytes(subprocess.check_output(['adb', '-s', serial, 'exec-out', 'screencap', '-p'], timeout=20))
    record['bridge_status_after_original_connect'] = {s: command(s, {'op': 'bridge_status'}) for s in DEVICES}
    record['scope'] = 'One original native connect click; screenshot determines actual result'


def notifications(record, actions=False):
    require_lock()
    component = PKG + '/.NotificationSyncListener'
    restoration = {}
    record['setup'] = {}
    record['directions'] = []

    def fixture(serial, action, **fields):
        return command(serial, {'op': 'notification_fixture', 'action': action, **fields})

    def observe(serial, wanted, seconds=10):
        deadline = time.monotonic() + seconds
        state = None
        while time.monotonic() < deadline:
            state = fixture(serial, 'state')
            if wanted(state):
                return state
            time.sleep(0.3)
        return state

    try:
        for serial in DEVICES:
            enabled = adb(serial, 'shell', 'settings', 'get', 'secure', 'enabled_notification_listeners').strip().split(':')
            had_access = component in enabled or PKG + '/' + PKG + '.NotificationSyncListener' in enabled
            # Keep original package policy only in process memory; never log unrelated app names.
            raw = subprocess.run(['adb', '-s', serial, 'shell', 'run-as', PKG, 'cat', 'shared_prefs/notification_policy.xml'], capture_output=True, timeout=15)
            packages = []
            if raw.returncode == 0:
                tree = ET.fromstring(raw.stdout)
                packages = [v.text for v in tree.findall("set[@name='packages']/string") if v.text]
            restoration[serial] = {'access': had_access, 'packages': packages}
            row = record['setup'][serial] = {'prior_listener_enabled': had_access, 'prior_package_count': len(packages)}
            row['configure_synthetic_package_only'] = command(serial, {'op': 'notification_packages', 'packages': [PKG]})
            if not had_access:
                row['allow_listener'] = adb(serial, 'shell', 'cmd', 'notification', 'allow_listener', component)
            state = observe(serial, lambda v: v.get('access_granted') and v.get('posting_granted'), seconds=6)
            row['readiness'] = state
        identifiers = [900002, 900003] if actions else [900000, 900001]
        for source, target, identifier in [(DEVICES[0], DEVICES[1], identifiers[0]), (DEVICES[1], DEVICES[0], identifiers[1])]:
            row = {'source': source, 'target': target, 'id': identifier, 'result': 'incomplete'}
            record['directions'].append(row)
            try:
                if not all(v['readiness'].get('access_granted') and v['readiness'].get('posting_granted') for v in record['setup'].values()):
                    raise RuntimeError('Listener or notification posting permission unavailable')
                row['post_once'] = fixture(source, 'post', id=identifier, revision=1)
                row['post_observed'] = observe(target, lambda v: {'id': identifier, 'revision': 1} in v.get('synthetic_mirrors', []))
                if {'id': identifier, 'revision': 1} not in row['post_observed'].get('synthetic_mirrors', []):
                    raise RuntimeError('Synthetic post did not reach remote OS notification list')
                if actions:
                    row['source_action_count_before'] = fixture(source, 'state').get('action_count', 0)
                    adb(target, 'shell', 'cmd', 'statusbar', 'expand-notifications')
                    time.sleep(0.7)
                    temporary = '/data/local/tmp/qj-synthetic-actions.xml'
                    def hierarchy():
                        adb(target, 'shell', 'uiautomator', 'dump', temporary)
                        data = adb(target, 'shell', 'cat', temporary)
                        adb(target, 'shell', 'rm', '-f', temporary)
                        return ET.fromstring(data)
                    tree = hierarchy()
                    buttons = [n for n in tree.iter('node') if n.get('text') == '测试动作']
                    if not buttons:
                        # Expand only the card proven to contain this synthetic title.
                        candidates = [n for n in tree.iter('node') if any(c.get('text') == '强兼合成通知 ' + str(identifier) for c in n.iter('node'))]
                        selected = None
                        for parent in reversed(candidates):
                            selected = next((n for n in parent.iter('node') if n.get('resource-id', '').endswith('expand_button') or n.get('content-desc') in ['展开', 'Expand']), None)
                            if selected is not None:
                                break
                        if selected is not None:
                            values = [int(x) for x in re.findall(r'\d+', selected.get('bounds', ''))]
                            if len(values) == 4:
                                adb(target, 'shell', 'input', 'tap', str((values[0]+values[2])//2), str((values[1]+values[3])//2))
                                time.sleep(0.4)
                                tree = hierarchy()
                                buttons = [n for n in tree.iter('node') if n.get('text') == '测试动作']
                    if len(buttons) != 1:
                        raise RuntimeError('Unique synthetic notification action button unavailable in native shade')
                    values = [int(x) for x in re.findall(r'\d+', buttons[0].get('bounds', ''))]
                    row['synthetic_action_bounds'] = values
                    if len(values) != 4:
                        raise RuntimeError('Synthetic action bounds unavailable')
                    adb(target, 'shell', 'input', 'tap', str((values[0]+values[2])//2), str((values[1]+values[3])//2))
                    row['action_tapped_once'] = True
                    observed = observe(source, lambda v: v.get('action_count', 0) == row['source_action_count_before'] + 1)
                    row['source_after_remote_action'] = observed
                    if observed.get('action_count', 0) != row['source_action_count_before'] + 1:
                        raise RuntimeError('Remote synthetic action did not reach original PendingIntent callback')
                    row['result'] = 'passed_original_remote_action'
                    fixture(source, 'remove', id=identifier)
                    adb(target, 'shell', 'cmd', 'statusbar', 'collapse')
                    continue
                row['update_once'] = fixture(source, 'post', id=identifier, revision=2)
                row['update_observed'] = observe(target, lambda v: {'id': identifier, 'revision': 2} in v.get('synthetic_mirrors', []))
                if {'id': identifier, 'revision': 2} not in row['update_observed'].get('synthetic_mirrors', []):
                    raise RuntimeError('Synthetic update did not reach remote OS notification list')
                row['remove_once'] = fixture(source, 'remove', id=identifier)
                row['remove_observed'] = observe(target, lambda v: not any(x.get('id') == identifier for x in v.get('synthetic_mirrors', [])))
                if any(x.get('id') == identifier for x in row['remove_observed'].get('synthetic_mirrors', [])):
                    raise RuntimeError('Synthetic remote mirror was not removed')
                row['result'] = 'passed_post_update_remove'
            except Exception as error:
                row.update(result='failed', error=str(error))
        record['actions'] = 'One remote native shade button per direction; source original PendingIntent counter checked' if actions else 'Not stimulated in this phase; system route and OEM route remain separate acceptance scopes'
    finally:
        record['cleanup'] = {}
        for serial, saved in restoration.items():
            row = record['cleanup'][serial] = {}
            try:
                for identifier in identifiers if 'identifiers' in locals() else [900000, 900001]:
                    fixture(serial, 'remove', id=identifier)
                row['restore_package_policy'] = command(serial, {'op': 'notification_packages', 'packages': saved['packages']})
                if not saved['access']:
                    row['restore_listener_disabled'] = adb(serial, 'shell', 'cmd', 'notification', 'disallow_listener', component)
                row['restored'] = True
            except Exception as error:
                row.update(restored=False, error=str(error))
    if any(v['result'] == 'failed' for v in record['directions']) or not record['directions'] or not all(v.get('restored') for v in record['cleanup'].values()):
        raise RuntimeError('Notification phase recorded failures; no post/update retry')


def notification_actions(record):
    notifications(record, actions=True)


def finder_readiness(record):
    require_lock()
    record['start_native_host_once'] = adb(DEVICES[1], 'shell', 'am', 'start', '-W', '-n', 'com.coloros.findmyphone/com.oplus.findphone.client.map.FindMainActivity')
    time.sleep(2)
    states = {s: command(s, {'op': 'bridge_status'})['bridge'] for s in DEVICES}
    record['readiness'] = {s: {'phase': v.get('phase'), 'local_operations': v.get('local', {}).get('operations'),
            'effective_operations': (v.get('projection') or {}).get('operations')} for s, v in states.items()}
    record['native_endpoint_available_both'] = all('device_finder' in (v.get('projection') or {}).get('operations', []) for v in states.values())
    record['scope'] = 'Real native host launch and current capability prerequisite, not an audible ring result'
    if not record['native_endpoint_available_both']:
        raise RuntimeError('Native finder endpoint not available on both devices; no synthetic ringing substitute')


def rich_and_disconnect(record):
    require_lock()
    probe = OUT / 'html-probe/probe.jar'
    remote = '/data/local/tmp/qj-functional-html-probe.jar'
    run = uuid.uuid4().hex
    record.update(run=run, probe_sha256=digest(probe), helpers={}, directions=[], cleanup={})
    processes = {}
    streams = {}

    def read(serial, seconds=12):
        deadline = time.monotonic() + seconds
        allowed = {'armed', 'read_permission_unavailable', 'snapshot_denied', 'copied', 'matches', 'not_matched', 'restored', 'unchanged_or_new_content_preserved'}
        while time.monotonic() < deadline:
            try:
                value = streams[serial].get(timeout=0.3).strip()
            except queue.Empty:
                if processes[serial].poll() is not None:
                    raise RuntimeError('HTML helper exited before expected result')
                continue
            if value in allowed:
                return value
        raise RuntimeError('HTML helper response timeout')

    def send(serial, text):
        processes[serial].stdin.write((text + '\n').encode())
        processes[serial].stdin.flush()
        return read(serial)

    try:
        for serial in DEVICES:
            adb(serial, 'push', str(probe), remote)
            proc = subprocess.Popen(['adb', '-s', serial, 'shell', 'CLASSPATH=' + remote, 'app_process', '/system/bin', 'QjHtmlClipboard', run], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            processes[serial] = proc
            streams[serial] = queue.Queue()
            def reader(process=proc, output=streams[serial]):
                for line in iter(process.stdout.readline, b''):
                    output.put(line.decode('utf-8', 'replace'))
            threading.Thread(target=reader, daemon=True).start()
            state = read(serial)
            record['helpers'][serial] = state
            if state != 'armed':
                raise RuntimeError('HTML test precondition unavailable: ' + state)
        for leg, source, target in [(1, DEVICES[0], DEVICES[1]), (2, DEVICES[1], DEVICES[0])]:
            row = {'source': source, 'target': target, 'leg': leg}
            record['directions'].append(row)
            row['platform_html_copy_once'] = send(source, 'copy' + str(leg))
            deadline = time.monotonic() + 10
            while True:
                observed = send(target, 'check' + str(leg))
                if observed == 'matches' or time.monotonic() >= deadline:
                    break
                time.sleep(0.25)
            row['remote_exact_plain_and_html_match'] = observed == 'matches'
        record['scope'] = 'Synthetic platform HTML copy observed by installed OEM source and exact remote clipboard readback; no RPC called by helper; native rich ACK not independently instrumented'
    finally:
        # Revoke both transports before restoring any original private ClipData.
        record['disconnect_once'] = {}
        for serial in DEVICES:
            try:
                record['disconnect_once'][serial] = command(serial, {'op': 'bridge_stop'})
            except Exception as error:
                record['disconnect_once'][serial] = {'error': str(error)}
        time.sleep(0.8)
        record['final_status'] = {s: command(s, {'op': 'bridge_status'})['bridge'] for s in DEVICES}
        disconnected = all(v.get('phase') in ['stopped', 'disconnected'] and not v.get('projection') for v in record['final_status'].values())
        record['disconnected_before_restore'] = disconnected
        for serial, proc in processes.items():
            row = record['cleanup'][serial] = {}
            try:
                if record['helpers'].get(serial) == 'armed' and disconnected:
                    row['clipboard_restore'] = send(serial, 'finish_after_disconnect')
                elif record['helpers'].get(serial) == 'armed':
                    row['clipboard_restore'] = 'blocked_until_confirmed_disconnect'
                proc.wait(timeout=5)
            except Exception as error:
                row['error'] = str(error)
                proc.terminate()
            finally:
                adb(serial, 'shell', 'rm', '-f', remote)
                row['temporary_probe_removed'] = True
        adb(DEVICES[0], 'shell', 'am', 'start', '-W', '-n', 'com.milink.service/com.miui.circulate.world.CirculateWorldActivity')
        time.sleep(1)
        (OUT / 'xiaomi-after-disconnect.png').write_bytes(subprocess.check_output(['adb', '-s', DEVICES[0], 'exec-out', 'screencap', '-p'], timeout=20))
    if not record.get('directions') or not all(v.get('remote_exact_plain_and_html_match') for v in record['directions']) or not record.get('disconnected_before_restore'):
        raise RuntimeError('Rich clipboard or test prerequisite failed; disconnect and cleanup recorded without retry')


def cleanup_ui(record):
    require_lock()
    record['cleanup'] = {}
    for serial in DEVICES:
        record['cleanup'][serial] = {'collapse_test_shade': adb(serial, 'shell', 'cmd', 'statusbar', 'collapse')}
        adb(serial, 'shell', 'rm', '-f', '/data/local/tmp/qj-synthetic-actions.xml')
    adb(DEVICES[0], 'shell', 'am', 'start', '-W', '-n', 'com.milink.service/com.miui.circulate.world.CirculateWorldActivity')
    time.sleep(1)
    (OUT / 'xiaomi-final-device-center.png').write_bytes(subprocess.check_output(['adb', '-s', DEVICES[0], 'exec-out', 'screencap', '-p'], timeout=20))
    record['scope'] = 'Restore notification shade opened by action test; final device-center observation only, no re-pair or business retry'


def final_observation(record):
    require_lock()
    temporary = '/data/local/tmp/qj-functional-final.xml'
    adb(DEVICES[0], 'shell', 'uiautomator', 'dump', temporary)
    tree = ET.fromstring(adb(DEVICES[0], 'shell', 'cat', temporary))
    adb(DEVICES[0], 'shell', 'rm', '-f', temporary)
    texts = [node.get('text', '') for node in tree.iter('node')]
    record['xiaomi_original_device_center_visible'] = '融合设备中心' in texts
    record['xiaomi_peer_card_remaining'] = any('一加 Ace 6' in value for value in texts)
    record['scope'] = 'Read-only final native UI observation; unrelated device names/text are neither saved nor printed'
    inputs = json.loads(INPUTS.read_text(encoding='utf-8-sig'))
    record['final_source_mismatches'] = [name for name, old in inputs.items() if not (ROOT/name).is_file() or digest(ROOT/name) != old]
    record['apk_sha256'] = digest(APK)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['baseline', 'prepare', 'pair', 'native-ui', 'profiles', 'native-select', 'native-connect', 'notifications', 'notification-actions', 'finder-readiness', 'rich-and-disconnect', 'cleanup-ui', 'final-observation'])
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True, parents=True)
    target = OUT / (args.phase + '.json')
    if target.exists():
        raise SystemExit('Phase already recorded; refusing automatic repeat: ' + args.phase)
    record = {'phase': args.phase, 'utc': datetime.now(timezone.utc).isoformat(), 'result': 'incomplete',
              'apk_version_code': 19, 'apk_version_name': '0.5.3-business-v6',
              'runner_sha256': digest(Path(__file__)), 'command': 'python scripts/run-functional-sweep.py ' + args.phase}
    try:
        globals()[args.phase.replace('-', '_')](record)
        record['result'] = 'passed'
    except Exception as error:
        record.update(result='failed', error_type=type(error).__name__, error=str(error))
    finally:
        write(args.phase + '.json', record)
    print(json.dumps({k: record[k] for k in ['phase', 'result', 'error', 'input_count', 'session_compared'] if k in record}))
    return 0 if record['result'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
