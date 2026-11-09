"""One explicit integration update, real session/code comparison, then native clipboard probe.
Requires the caller to own devices.lock. Never retries a state-changing command automatically.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from debug_control import command
from clipboard_device_guard import check_native_foreground

ROOT = Path(__file__).resolve().parents[1]
DEVICES = ['b59f8656', '3B161Q00YN200000']


def session_action(states):
    phases = [s.get('phase') for s in states]
    if len(states) != 2:
        raise RuntimeError('Two live device states required')
    if all(p in {'stopped', 'disconnected'} for p in phases):
        return 'new'
    if all(p in {'active', 'awaiting_confirmation'} for p in phases):
        session = states[0].get('session')
        code = states[0].get('comparison_code')
        if (not re.fullmatch('[0-9a-f]{64}', session or '') or not code
                or states[1].get('session') != session or states[1].get('comparison_code') != code):
            raise RuntimeError('Real session/comparison code mismatch; confirmation withheld')
        if all(p == 'active' for p in phases):
            if not all(s.get('local_confirmed') and s.get('remote_confirmed') for s in states):
                raise RuntimeError('Incomplete current session confirmation')
            return 'reuse'
        return 'confirm'
    raise RuntimeError('Existing device sessions differ; no destructive pairing retry performed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--lock-owner', required=True)
    parser.add_argument('--installed', action='store_true', help='Verify and reuse the already installed APK; do not reinstall/restart')
    parser.add_argument('--format', choices=['plain', 'html', 'uri'], default='plain')
    parser.add_argument('--build-ledger', type=Path, default=ROOT / 'build/clipboard-validation/integrated-ledger.json')
    parser.add_argument('--stop-created-session', action='store_true', help='Stop only a fresh session created by this invocation after restoration')
    parser.add_argument('--focus-native', action='store_true', help='Explicitly authorized navigation to known OEM test activities, without screenshots')
    parser.add_argument('--prepare-only', action='store_true', help='Leave a verified session for the same lock holder native URI window; no copy or probe')
    parser.add_argument('--record-path', type=Path)
    args = parser.parse_args()
    lock = ROOT / 'build/coordination-locks/devices.lock'
    if not lock.exists() or f'clipboard {args.lock_owner} ' not in lock.read_text(encoding='utf-8-sig'):
        raise SystemExit('Missing owned device lock')
    expected_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    record = dict(apk_sha256=expected_hash, session_compared=False, confirmed=False, format=args.format,
                  runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    created_session = None
    required_operation = 'clipboard_uri_write' if args.format == 'uri' else 'clipboard_rich_write' if args.format == 'html' else 'clipboard_write'
    out = ROOT / 'build/clipboard-validation'

    def adb(serial, *argv):
        result = subprocess.run(['adb', '-s', serial, *argv], capture_output=True, timeout=30)
        if result.returncode or b'Error:' in result.stdout or b'Exception' in result.stderr:
            raise RuntimeError('adb failed: ' + argv[0])
        return result.stdout.decode('utf-8', 'replace')

    def status(serial):
        try:
            return command(serial, {'op': 'bridge_status'})['bridge']
        except TimeoutError:
            # Read-only retry for cold startup; state-changing commands are never retried.
            return command(serial, {'op': 'bridge_status'})['bridge']

    try:
        if args.focus_native:
            record['native_navigation'] = {}
            # Explicit user approval applies only to these known OEM components, never arbitrary taps.
            adb(DEVICES[0], 'shell', 'am', 'start', '-W', '-n', 'com.milink.service/com.miui.circulate.world.CirculateWorldActivity')
            adb(DEVICES[1], 'shell', 'su', '-c', 'am start -W -n com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity')
            record['native_navigation'] = {serial: check_native_foreground(serial) for serial in DEVICES}
        if args.format != 'plain':
            record['foreground_before_window'] = {serial: check_native_foreground(serial) for serial in DEVICES}
        if not args.installed:
            # Stop old injected hosts before replacing/restarting the module service. Stopping
            # Mirror after the new proof binder was established invalidates that proof binding.
            adb(DEVICES[0], 'shell', 'am', 'force-stop', 'com.xiaomi.mirror')
            adb(DEVICES[1], 'shell', 'am', 'force-stop', 'com.oplus.linker')
        for serial in DEVICES:
            if args.installed:
                path = adb(serial, 'shell', 'pm', 'path', 'io.github.yuejie.connect').strip()
                if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_=+./~-]+\.apk', path):
                    raise RuntimeError('Unexpected APK path')
                if adb(serial, 'shell', 'sha256sum', path[8:]).split()[0] != expected_hash:
                    raise RuntimeError('Installed APK does not match')
            else:
                record['stage'] = 'install_' + serial
                value = adb(serial, 'install', '-r', str(args.apk.resolve()))
                if 'Success' not in value:
                    raise RuntimeError('APK install failed')
        states = []
        for serial in DEVICES:
            record['stage'] = 'initial_status_' + serial
            states.append(status(serial))
        action = session_action(states)
        if action == 'new':
            address = adb(DEVICES[0], 'shell', 'ip', '-4', 'addr', 'show', 'wlan0')
            match = re.search(r'inet (\d+\.\d+\.\d+\.\d+)/', address)
            if not match:
                raise RuntimeError('Xiaomi WLAN IPv4 unavailable')
            command(DEVICES[0], {'op': 'bridge_listen', 'port': 38271})
            record['stage'] = 'connect'
            command(DEVICES[1], {'op': 'bridge_connect', 'address': match.group(1) + ':38271'})
            deadline = time.monotonic() + 15
            while True:
                record['stage'] = 'handshake_status'
                states = [status(s) for s in DEVICES]
                if all(s['phase'] == 'awaiting_confirmation' for s in states):
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError('Real handshake did not reach confirmation')
                time.sleep(0.4)
            action = session_action(states)
            created_session = states[0].get('session')
        first, second = states
        session = first.get('session')
        code = first.get('comparison_code')
        if (not re.fullmatch('[0-9a-f]{64}', session or '') or not code
                or session != second.get('session') or code != second.get('comparison_code')):
            raise RuntimeError('Real session/comparison code mismatch; confirmation withheld')
        record.update(session_compared=True, session=session, comparison_code=code)
        record['reused_current_session'] = action == 'reuse'
        for serial in DEVICES:
            record['stage'] = 'confirm_' + serial
            live = status(serial)
            if live.get('session') != session or live.get('comparison_code') != code or live['phase'] not in {'active', 'awaiting_confirmation'}:
                raise RuntimeError('Session changed before confirmation')
            if not live.get('local_confirmed'):
                approval = {'op': 'bridge_confirm', 'session': session}
                if args.format == 'html':
                    approval['operations'] = ['device_profile', 'battery_status', 'clipboard_write', 'clipboard_rich_write']
                elif args.format == 'uri':
                    approval['operations'] = ['device_profile', 'battery_status', 'clipboard_write', 'file_transfer', 'clipboard_uri_write']
                command(serial, approval)
        # Existing exported native settings Activity supplies the real foreground clipboard read context.
        record['stage'] = 'native_focus'
        # The OEM activity is signature-permission protected. Use existing rooted test-device
        # administration to open the unmodified OEM UI; no app permission/settings are granted.
        adb(DEVICES[1], 'shell', "su -c 'am start -W -n com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity'")
        deadline = time.monotonic() + 12
        while True:
            record['stage'] = 'executor_status'
            states = [status(s) for s in DEVICES]
            if all(s['phase'] == 'active' and s.get('local_confirmed') and s.get('remote_confirmed')
                   and required_operation in (s.get('projection') or {}).get('operations', []) for s in states):
                break
            if time.monotonic() >= deadline:
                record['phases'] = [s['phase'] for s in states]
                record['operations'] = [(s.get('projection') or {}).get('operations', []) for s in states]
                raise RuntimeError('Real clipboard executors did not become available')
            time.sleep(0.5)
        record['confirmed'] = True
        print('Real session and comparison codes match; both endpoints active with ' + required_operation, flush=True)
        if args.prepare_only:
            return 0
        # DebugControlActivity finishes after each query. Focus the native settings screen again.
        adb(DEVICES[1], 'shell', "su -c 'am start -W -n com.oplus.linker/.synergy.ui.activity.ConnectPCSettingsActivity'")
        result = subprocess.run([sys.executable.strip(), str(ROOT / 'scripts/validate-clipboard-native.py'),
                                 '--apk', str(args.apk), '--lock-owner', args.lock_owner, '--format', args.format,
                                 '--build-ledger', str(args.build_ledger)])
        record['native_probe_exit'] = result.returncode
        return result.returncode
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        record['failure'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('Preparation failed: ' + record['failure'], flush=True)
        return 1
    finally:
        if args.stop_created_session and created_session is not None:
            record['session_cleanup'] = {}
            for serial in DEVICES:
                try:
                    current = status(serial)
                    if current.get('session') == created_session:
                        record['session_cleanup'][serial] = command(serial, {'op': 'bridge_stop'})
                    else:
                        record['session_cleanup'][serial] = {'status': 'not_our_session_not_stopped'}
                except Exception:
                    record['session_cleanup'][serial] = {'status': 'cleanup_unverified'}
        record_path = args.record_path or out / ('device-session-' + expected_hash[:12] + '-' + str(time.time_ns()) + '.json')
        record_path.write_text(
            json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    raise SystemExit(main())
