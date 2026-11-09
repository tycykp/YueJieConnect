"""One targeted native-shade action per direction; no direct PendingIntent/test-RPC bypass."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from debug_control import command

ROOT = Path(__file__).resolve().parents[1]
OWNER = 'notifications 01a1bbe4-dde6-7732-9ead-763df753f644'
PKG = 'io.github.yuejie.connect'
DEVICES = ['b59f8656', '3B161Q00YN200000']
OPS = ['device_profile', 'battery_status', 'notification_post', 'notification_remove', 'notification_action']
COMPONENT = PKG + '/.NotificationSyncListener'
HELPER = ROOT / 'build/notifications-validation/repair-round3/ui-probe/probe.jar'
REMOTE_HELPER = '/data/local/tmp/yj-notification-ui-repair.jar'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def adb(serial, *args):
    p = subprocess.run(['adb', '-s', serial, *args], capture_output=True, timeout=35)
    if p.returncode or (args[:2] != ('shell', 'cat') and (b'Error:' in p.stdout or b'Exception' in p.stderr)):
        raise RuntimeError('ADB command failed: ' + ' '.join(args[:3]) + ' (exit ' + str(p.returncode) + ')')
    return p.stdout.decode('utf-8', 'replace')


def fixture(serial, action, **kw):
    return command(serial, {'op': 'notification_fixture', 'action': action, **kw})


def until(read, predicate, seconds=12):
    deadline = time.monotonic() + seconds
    while True:
        value = read()
        if predicate(value):
            return value
        if time.monotonic() >= deadline:
            raise RuntimeError('Required observation did not become ready')
        time.sleep(.35)


def states():
    return {s: command(s, {'op': 'bridge_status'}) for s in DEVICES}


def ready(value, projection=False):
    for state in value.values():
        if not set(OPS).issubset(state['bridge']['local']['operations']):
            return False
        if projection and not set(OPS).issubset((state['bridge'].get('projection') or {}).get('operations', [])):
            return False
    return True


def helper(serial, identifier, operation):
    return adb(serial, 'shell', 'uiautomator', 'runtest', REMOTE_HELPER, '-c', 'NotificationSyncUiProbe#testSynthetic', '-e', 'id', str(identifier), '-e', 'operation', operation)


def hierarchy(serial, identifier):
    # The shell helper reads the real accessibility tree without UiAutomator's idle wait.
    # It emits only the synthetic row and fixed labels; it never writes a raw UI dump.
    data = ''
    if serial == DEVICES[0]:
        data = helper(serial, identifier, 'snapshot')
    begin = data.find('<?xml')
    if begin < 0:
        temporary = '/data/local/tmp/yj-notification-actions-repair.xml'
        try:
            adb(serial, 'shell', 'uiautomator', 'dump', '--compressed', temporary)
            data = adb(serial, 'shell', 'cat', temporary)
            return ET.fromstring(data)
        finally:
            adb(serial, 'shell', 'rm', '-f', temporary)
    end = data.find('</hierarchy>', begin)
    if end < 0:
        raise RuntimeError('Synthetic native UI snapshot incomplete')
    return ET.fromstring(data[begin:end+len('</hierarchy>')])


def bounds(node):
    values = [int(x) for x in re.findall(r'\d+', node.get('bounds', ''))]
    if len(values) != 4 or values[0] >= values[2] or values[1] >= values[3]:
        raise RuntimeError('Native UI target has no usable bounds')
    return values


def tap(serial, node):
    x1, y1, x2, y2 = bounds(node)
    adb(serial, 'shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))


def card(tree, identifier):
    title = '越界合成通知 ' + str(identifier)
    candidates = [n for n in tree.iter('node') if any(c.get('text') == title for c in n.iter('node'))]
    # Smallest real notification container, not an unrelated notification's expand control.
    for n in reversed(candidates):
        if n.get('clickable') == 'true' and any(c.get('resource-id', '').endswith(
                ('notification_children_container','status_bar_latest_event_content','expanded')) for c in list(n)):
            return n
    for n in reversed(candidates):
        if n.get('resource-id', '').endswith('expandableNotificationRow') and any(
                'expand_button' in c.get('resource-id', '') or c.get('text') in ['测试动作', '测试回复']
                or 'remote_input' in c.get('resource-id', '') for c in n.iter('node')):
            return n
    for n in reversed(candidates):
        descendants = list(n.iter('node'))
        if any('expand_button' in c.get('resource-id', '')
               or c.get('text') in ['测试动作', '测试回复'] for c in descendants):
            return n
    return candidates[-1] if candidates else None


def safe_tree(tree, identifier):
    selected = card(tree, identifier)
    allowed = ['越界合成通知 ' + str(identifier), '测试动作', '测试回复', '在来源设备移除', '展开', 'Expand', '收起', 'Collapse', 'YJ-test-reply']
    return {'synthetic_card_found': selected is not None, 'nodes': [
        {'text': n.get('text') if n.get('text') in allowed else '',
         'resource_id': n.get('resource-id', ''), 'class': n.get('class', ''),
         'bounds': n.get('bounds', ''), 'clickable': n.get('clickable', ''),
         'focused': n.get('focused', '')}
        for n in (selected.iter('node') if selected is not None else [])
        if n.get('text') in allowed or 'expand' in n.get('resource-id', '') or 'remote_input' in n.get('resource-id', '')]}


def native_action(serial, identifier, kind, record):
    adb(serial, 'shell', 'cmd', 'statusbar', 'expand-notifications')
    time.sleep(.7)
    tree = hierarchy(serial, identifier)
    record['collapsed_ui'] = safe_tree(tree, identifier)
    selected = card(tree, identifier)
    label = '测试动作' if kind == 'action' else '测试回复'
    matches = [n for n in selected.iter('node') if n.get('text') == label] if selected is not None else []
    if not matches and selected is not None and selected.get('clickable') == 'true' and any(
            c.get('resource-id', '').endswith('notification_children_container') for c in list(selected)):
        record['native_group_bounds'] = bounds(selected)
        tap(serial, selected)
        time.sleep(.5)
        tree = hierarchy(serial, identifier)
        selected = card(tree, identifier)
        record['group_expanded_ui'] = safe_tree(tree, identifier)
        matches = [n for n in selected.iter('node') if n.get('text') == label] if selected is not None else []
    expanded = set()
    for level in range(3):
        if matches or selected is None:
            break
        expand = [n for n in selected.iter('node') if ('expand_button' in n.get('resource-id', '')
                  and n.get('clickable') == 'true') or n.get('content-desc') in ['展开', 'Expand']]
        exact = [n for n in expand if n.get('resource-id', '').endswith('expand_button')]
        if len(exact) == 1:
            expand = exact
        else:
            expand = list({n.get('bounds'): n for n in expand}.values())
        if len(expand) == 1:
            target_bounds = tuple(bounds(expand[0]))
            if target_bounds in expanded:
                break
            expanded.add(target_bounds)
            tap(serial, expand[0])
            record.setdefault('expansions', []).append({'level': level, 'bounds': list(target_bounds)})
            time.sleep(.5)
            tree = hierarchy(serial, identifier)
            selected = card(tree, identifier)
            record.setdefault('expanded_ui', []).append(safe_tree(tree, identifier))
            matches = [n for n in selected.iter('node') if n.get('text') == label] if selected is not None else []
        else:
            break
    if not matches and selected is not None and serial == DEVICES[0]:
        titles = [n for n in selected.iter('node') if n.get('text') == '越界合成通知 ' + str(identifier)]
        if len(titles) == 1:
            if 'native_expand_gesture_sent' not in helper(serial,identifier,'expand-card'):
                raise RuntimeError('Native synthetic card expansion was not injected')
            record['native_expand_gesture'] = 'two_pointer_native_touch'
            time.sleep(.5)
            tree = hierarchy(serial, identifier)
            selected = card(tree, identifier)
            record['gesture_ui'] = safe_tree(tree, identifier)
            matches = [n for n in selected.iter('node') if n.get('text') == label] if selected is not None else []
    if len(matches) != 1:
        raise RuntimeError('Unique synthetic action unavailable in native notification card')
    record['button_bounds'] = bounds(matches[0])
    tap(serial, matches[0])
    record['button_tapped_once'] = True
    if kind == 'reply':
        time.sleep(.6)
        tree = hierarchy(serial, identifier)
        inputs = [n for n in tree.iter('node') if n.get('class') == 'android.widget.EditText' and n.get('focused') == 'true']
        if len(inputs) != 1:
            raise RuntimeError('Unique native RemoteInput field unavailable')
        if 'synthetic_actions' in record['mirror_observed']:
            adb(serial, 'shell', 'input', 'text', 'YJ-test-reply')
            record['reply_input'] = 'fixed_ascii_platform_keyboard'
        elif 'reply_text_set' in helper(serial, identifier, 'reply-text'):
            record['reply_input'] = 'fixed_chinese_native_edit_text'
        else:
            raise RuntimeError('Native synthetic reply text was not set')
        tree = hierarchy(serial, identifier)
        # Only a send control inside the exact artificial notification can be selected.
        selected = card(tree, identifier)
        if selected is None:
            raise RuntimeError('Synthetic card disappeared before reply send')
        sends = [n for n in selected.iter('node') if n.get('resource-id', '').endswith('remote_input_send')]
        if len(sends) != 1:
            raise RuntimeError('Unique native RemoteInput send control unavailable')
        record['reply_ui'] = safe_tree(tree, identifier)
        tap(serial, sends[0])
        record['reply_sent_once'] = True


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--inputs', type=Path, required=True)
    p.add_argument('--kind', choices=['action', 'reply'], required=True)
    p.add_argument('--direction', choices=['both','xiaomi-to-oplus','oplus-to-xiaomi'], default='both')
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    lease = ROOT / 'build/coordination-locks/devices.lock'
    if not lease.exists() or OWNER not in lease.read_text(encoding='utf-8-sig'):
        raise RuntimeError('Missing exclusive notifications device lease')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    if args.out.exists():
        raise RuntimeError('Evidence already exists; no automatic mutation retry')
    aapt = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk/build-tools/37.0.0/aapt2.exe'
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(args.apk)], timeout=15).decode('utf-8', 'replace')
    version = re.search(r"versionCode='(\d+)' versionName='([^']+)'", badging)
    if not version:
        raise RuntimeError('APK version unavailable')
    record = {'utc': datetime.now(timezone.utc).isoformat(), 'command': ' '.join(__import__('sys').argv),
              'result': 'incomplete', 'kind': args.kind, 'apk': str(args.apk), 'apk_sha256': sha(args.apk),
              'direction': args.direction,
              'apk_version_code': int(version.group(1)), 'apk_version_name': version.group(2),
              'runner_sha256': sha(Path(__file__)), 'input_ledger_sha256': sha(args.inputs),
              'tested_source_sha256': json.loads(args.inputs.read_text(encoding='utf-8-sig')),
              'ui_helper_sha256': sha(HELPER), 'ui_helper_source_sha256': sha(ROOT / 'scripts/notifications/NotificationSyncUiProbe.java'),
              'devices': {}, 'setup': {}, 'directions': [], 'cleanup': {}}
    restoration = {}
    helper_devices = []
    opened_session = False
    try:
        for serial in DEVICES:
            path = adb(serial, 'shell', 'pm', 'path', PKG).strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk', path):
                raise RuntimeError('Unexpected installed APK path')
            installed = adb(serial, 'shell', 'sha256sum', path[8:]).split()[0]
            record['devices'][serial] = {'installed_sha256': installed}
            if installed != record['apk_sha256']:
                raise RuntimeError('Installed APK differs from requested candidate')
            adb(serial, 'push', str(HELPER), REMOTE_HELPER)
            helper_devices.append(serial)
            adb(serial, 'shell', 'input', 'keyevent', '224')
        initial = states()
        record['initial_states'] = initial
        if any(v['bridge']['phase'] not in ['stopped', 'disconnected'] for v in initial.values()):
            raise RuntimeError('Existing session belongs to another task; leave it unchanged')
        for serial in DEVICES:
            listeners = adb(serial, 'shell', 'settings', 'get', 'secure', 'enabled_notification_listeners').strip().split(':')
            had_access = COMPONENT in listeners or PKG+'/'+PKG+'.NotificationSyncListener' in listeners
            raw = subprocess.run(['adb', '-s', serial, 'shell', 'run-as', PKG, 'cat', 'shared_prefs/notification_policy.xml'], capture_output=True, timeout=12)
            packages = []
            if raw.returncode == 0:
                packages = [n.text for n in ET.fromstring(raw.stdout).findall("set[@name='packages']/string") if n.text]
            restoration[serial] = {'access': had_access, 'packages': packages}
            record['setup'][serial] = {'prior_access': had_access, 'prior_package_count': len(packages)}
            command(serial, {'op': 'notification_packages', 'packages': [PKG]})
            if not had_access:
                adb(serial, 'shell', 'cmd', 'notification', 'allow_listener', COMPONENT)
            fixture(serial, 'clear')
            record['setup'][serial]['readiness'] = until(lambda: fixture(serial, 'state'), lambda v: v.get('access_granted') and v.get('posting_granted'))
        address = adb(DEVICES[0], 'shell', 'ip', '-4', 'addr', 'show', 'wlan0')
        match = re.search(r'inet (\d+\.\d+\.\d+\.\d+)/', address)
        if not match:
            raise RuntimeError('WLAN address unavailable')
        opened_session = True
        record['listen'] = command(DEVICES[0], {'op': 'bridge_listen', 'port': 38271})
        record['connect'] = command(DEVICES[1], {'op': 'bridge_connect', 'address': match.group(1)+':38271'})
        handshake = until(states, lambda v: all(x['bridge']['phase'] == 'awaiting_confirmation' for x in v.values()), 20)
        # listen/connect keep BridgeService in the foreground. A stopped, temporarily bound
        # debug service is not a reliable executor-registration readiness boundary.
        record['executor_ready'] = until(states, ready)
        a, b = [handshake[s]['bridge'] for s in DEVICES]
        if not re.fullmatch('[0-9a-f]{64}', a.get('session', '')) or not a.get('comparison_code') or a['session'] != b.get('session') or a['comparison_code'] != b.get('comparison_code'):
            raise RuntimeError('Fresh bilateral session/comparison code mismatch; do not confirm')
        record['session_compared'] = {'session': a['session'], 'comparison_code': a['comparison_code']}
        record['confirm'] = {s: command(s, {'op': 'bridge_confirm', 'session': a['session'], 'operations': OPS}) for s in DEVICES}
        record['effective_ready'] = until(states, lambda v: ready(v, True) and all(x['bridge']['phase'] == 'active' and x['bridge'].get('local_confirmed') and x['bridge'].get('remote_confirmed') for x in v.values()))
        for index, (source, target) in enumerate([DEVICES, DEVICES[::-1]]):
            if args.direction != 'both' and args.direction != ['xiaomi-to-oplus','oplus-to-xiaomi'][index]:
                continue
            identifier = (900002 if args.kind == 'action' else 900004) + index
            row = {'source': source, 'target': target, 'id': identifier, 'result': 'incomplete'}
            record['directions'].append(row)
            try:
                counter = 'action_count' if args.kind == 'action' else 'reply_count'
                before = fixture(source, 'state')[counter]
                row['source_counter_before'] = before
                row['post_once'] = fixture(source, 'post', id=identifier, revision=1)
                row['mirror_observed'] = until(lambda: fixture(target, 'state'), lambda v: {'id': identifier, 'revision': 1} in v.get('synthetic_mirrors', []))
                native_action(target, identifier, args.kind, row)
                row['source_original_callback'] = until(lambda: fixture(source, 'state'), lambda v: v[counter] == before+1)
                row['result'] = 'passed_original_remote_' + args.kind
            except Exception as error:
                row.update(result='blocked', error=str(error))
            finally:
                fixture(source, 'remove', id=identifier)
                adb(target, 'shell', 'cmd', 'statusbar', 'collapse')
        record['result'] = 'passed' if all(v['result'].startswith('passed_') for v in record['directions']) else 'blocked'
    except Exception as error:
        record.update(result='blocked', error_type=type(error).__name__, error=str(error))
        try:
            record['last_states'] = states()
        except Exception:
            record['last_states_unavailable'] = True
    finally:
        for serial, saved in restoration.items():
            row = record['cleanup'][serial] = {}
            try:
                fixture(serial, 'clear')
                command(serial, {'op': 'notification_packages', 'packages': saved['packages']})
                if not saved['access']:
                    adb(serial, 'shell', 'cmd', 'notification', 'disallow_listener', COMPONENT)
                adb(serial, 'shell', 'cmd', 'statusbar', 'collapse')
                if opened_session:
                    row['stop'] = command(serial, {'op': 'bridge_stop'})
                row['final_synthetic_state'] = until(lambda: fixture(serial,'state'), lambda v:
                    not v.get('synthetic') and not v.get('synthetic_mirrors')
                    and bool(v.get('access_granted')) == saved['access'], seconds=7)
                row['restored'] = True
            except Exception as error:
                row.update(restored=False, error=str(error))
        for serial in helper_devices:
            try:
                adb(serial, 'shell', 'rm', '-f', REMOTE_HELPER)
                record['cleanup'].setdefault(serial, {})['helper_removed'] = True
            except Exception:
                record['cleanup'].setdefault(serial, {})['helper_removed'] = False
        args.out.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'result': record['result'], 'evidence': str(args.out), 'directions': [v['result'] for v in record['directions']], 'error': record.get('error'), 'cleanup': {s:v.get('restored') for s,v in record['cleanup'].items()}}, ensure_ascii=False))
    if record['result'] != 'passed' or any(not v.get('restored') or v.get('helper_removed') is False for v in record['cleanup'].values()):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
