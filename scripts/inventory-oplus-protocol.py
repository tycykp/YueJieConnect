"""Index the sampled declared Synergy RPC and command surface; declarations are not support."""
import hashlib
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
base = root / 'captures/device-interconnect-20261108/coloros17/analysis/jadx/linker/sources'
services = []
for path in sorted((base / 'com/oplus/linker/synergy').glob('*ServiceGrpc.java')):
    source = path.read_text(encoding='utf-8')
    name = re.search(r'SERVICE_NAME = "([^"]+)"', source).group(1)
    methods = sorted(set(re.findall(r'wy1\.a\(SERVICE_NAME, "([^"]+)"\)', source)))
    services.append(dict(service=name, methods=methods, source=path.relative_to(base).as_posix(),
                         sha256=hashlib.sha256(path.read_bytes()).hexdigest(), status='declared_not_interoperable'))
commands_path = base / 'com/oplus/linker/synergy/protocol/SynergyCmd.java'
commands = []
for line, text in enumerate(commands_path.read_text(encoding='utf-8').splitlines(), 1):
    match = re.search(r'public static final (String|int) (\w+) = (.+);', text)
    if match:
        commands.append(dict(name=match[2], type=match[1], value_literal=match[3], line=line,
                             status='declared_not_interoperable'))
out = dict(sample='com.oplus.linker 17.7.30 / PLQ110_17.0.0.104(CN01)',
           scope='All declarations in the four Synergy *ServiceGrpc classes and SynergyCmd; excludes OShare. '
                 'This is not an assertion that every declaration is reachable on this phone.',
           rpc_services=services, commands_source=commands_path.relative_to(base).as_posix(),
           commands_sha256=hashlib.sha256(commands_path.read_bytes()).hexdigest(), commands=commands)
target = root / 'adapters/coloros17/capability-inventory.json'
target.write_text(json.dumps(out, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(f'Indexed {len(services)} services, {sum(len(s["methods"]) for s in services)} RPCs, {len(commands)} command/key constants.')
