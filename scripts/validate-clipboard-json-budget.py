"""New Android JSONStringer slash-escaping risk only; no repeated clipboard behavior tests.
Computes an exact valid-UTF8/base64 slash bound using a UTF8 DFA and a base64 bit-carry DFA,
then measures its witness and specified adversarial strings with actual Android org.json.
"""
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rich_tests', ROOT / 'scripts/validate-clipboard-rich.py')
rich = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rich)
OUT = ROOT / 'build/clipboard-validation/json-budget'
rich.OUT = OUT


def next_utf8(state, byte):
    if state == 0:
        if byte <= 0x7f: return 0
        if 0xc2 <= byte <= 0xdf: return 1
        if byte == 0xe0: return 4
        if 0xe1 <= byte <= 0xec or 0xee <= byte <= 0xef: return 2
        if byte == 0xed: return 5
        if byte == 0xf0: return 6
        if 0xf1 <= byte <= 0xf3: return 3
        if byte == 0xf4: return 7
    elif state <= 3 and 0x80 <= byte <= 0xbf: return state - 1
    elif state == 4 and 0xa0 <= byte <= 0xbf: return 1
    elif state == 5 and 0x80 <= byte <= 0x9f: return 1
    elif state == 6 and 0x90 <= byte <= 0xbf: return 2
    elif state == 7 and 0x80 <= byte <= 0x8f: return 2
    return None


def next_base64(state, byte):
    if state == 0: return 1 + (byte & 3), int(byte >> 2 == 63)
    if state <= 4: return 5 + (byte & 15), int(((state - 1) << 4 | byte >> 4) == 63)
    return 0, int(((state - 5) << 2 | byte >> 6) == 63) + int(byte & 63 == 63)


def worst_pair():
    edges = []
    for node in range(8 * 21):
        utf8, base64 = divmod(node, 21)
        unique = {}
        for byte in range(256):
            after = next_utf8(utf8, byte)
            if after is None: continue
            bits, reward = next_base64(base64, byte)
            target = after * 21 + bits
            if target not in unique or reward > unique[target][0]: unique[target] = reward, byte
        edges.append([(target, reward, byte) for target, (reward, byte) in unique.items()])
    scores = [-10000] * 168
    scores[0] = 0
    best = [(0, 0)]
    parents = [None]
    for length in range(1, 4097):
        after = [-10000] * 168
        back = [None] * 168
        for node, score in enumerate(scores):
            if score < 0: continue
            for target, reward, byte in edges[node]:
                if score + reward > after[target]:
                    after[target] = score + reward; back[target] = node, byte
        state = max(range(21), key=lambda n: after[n])
        best.append((after[state], state)); parents.append(back); scores = after
    # Track two independently encoded UTF8 strings and all raw totals <=4096. HTML is nonempty.
    encoded = lambda n: (n + 2) // 3 * 4
    worst, split = -1, None
    # The maximum one-part cost is monotonic, so best prefix lets us search all <=4096 pairs.
    prefix = [(0, 0)]
    for n in range(1, 4097):
        cost = encoded(n) + best[n][0]
        prefix.append((cost, n) if cost > prefix[-1][0] else prefix[-1])
    for html in range(1, 4097):
        plain_cost, plain = prefix[4096-html]
        cost = encoded(html) + best[html][0] + plain_cost
        if cost > worst: worst, split = cost, (plain, html)

    def witness(length):
        node = best[length][1]
        raw = bytearray()
        for n in range(length, 0, -1):
            node, byte = parents[n][node]; raw.append(byte)
        raw.reverse()
        text = raw.decode('utf-8', 'strict')
        assert text.encode('utf-8') == raw
        return text
    return worst, split, witness(split[0]), witness(split[1])


harness = r'''package io.github.qiangjian.connect;
import org.json.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;
public class ClipboardJsonBudgetHarness {
 static int cases,max;static final String ID="qj:"+"f".repeat(64),SESSION="a".repeat(64);
 static JSONObject envelope(ClipboardRichContent.Html html)throws Exception {return new JSONObject().put("op","bridge_submit").put("projection_id",ID)
 .put("session_id",SESSION).put("request_id","r".repeat(128)).put("operation","clipboard_rich_write")
 .put("payload",ClipboardRichCodec.encode(html)).put("token","f".repeat(128)).put("sequence",Long.MAX_VALUE);}
 static void check(ClipboardRichContent.Html h,String label)throws Exception {String wire=envelope(h).toString();int bytes=wire.getBytes(StandardCharsets.UTF_8).length;
 if(bytes>8192)throw new AssertionError(label+" exceeded Binder budget "+bytes);if(!ClipboardRichCodec.decode(new JSONObject(wire).getJSONObject("payload")).equals(h))throw new AssertionError("pair changed");
 max=Math.max(max,bytes);cases++;System.out.println(label+": "+bytes+" bytes");}
 static String fill(String unit,int max){int n=max/unit.getBytes(StandardCharsets.UTF_8).length;return unit.repeat(n);}
 public static void main(String[] args)throws Exception {
  if(!new JSONObject().put("x","/").toString().contains("\\/"))throw new AssertionError("requires actual Android slash escaping");
  for(String unit:new String[]{"?","\uffff",new String(Character.toChars(0x10ffff)),new String(Character.toChars(0x3ffff)),"?\uffff",new String(Character.toChars(0x3ffff))+"?","\u0000/?\uffff"}){
   check(new ClipboardRichContent.Html("",fill(unit,4096)),"nonempty HTML-only case "+cases);
   check(new ClipboardRichContent.Html(fill(unit,2048),fill(unit,2048)),"two buffers case "+cases);
  }
  String plain=Files.readString(Path.of(args[0])),html=Files.readString(Path.of(args[1]));
  check(new ClipboardRichContent.Html(plain,html),"exact valid-UTF8 DFA worst slash/padding witness");
  int maximumInteger=Integer.parseInt(args[2]);int emptyEnvelope=envelope(new ClipboardRichContent.Html("","x")).toString().getBytes(StandardCharsets.UTF_8).length-4;
  if(emptyEnvelope+maximumInteger>8192)throw new AssertionError("DFA upper bound exceeds Binder budget");
  System.out.println("PASS "+cases+" true Android JSON envelopes; maximum="+max+", proven bound="+(emptyEnvelope+maximumInteger)+", limit=8192");
 }
}'''


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    worst, split, plain, html = worst_pair()
    p = OUT / 'plain-witness.txt'
    h = OUT / 'html-witness.txt'
    p.write_text(plain, encoding='utf-8', newline='')
    h.write_text(html, encoding='utf-8', newline='')
    sources = OUT / 'sources'
    classes = OUT / 'classes'
    classes.mkdir(exist_ok=True)
    stubs = {k: v for k, v in rich.stubs.items() if k.startswith('android/')}
    stubs[rich.PKG+'ClipboardJsonBudgetHarness.java'] = harness
    for relative, body in stubs.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
    actual = [rich.MAIN / name for name in ['ClipboardRichContent.java','ClipboardRichCodec.java','ClipboardWireCodec.java','OplusClipboardCodec.java']]
    json_sources = sorted((ROOT / 'build/file-transfer-validation/json-src/org/json').glob('*.java'))
    rich.run('compile', rich.JDK / 'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
        *actual, *json_sources, *sorted(sources.rglob('*.java')))
    rich.run('json-budget-tests', rich.JDK / 'java.exe', '-cp', classes, 'io.github.qiangjian.connect.ClipboardJsonBudgetHarness', p, h, worst)
    (OUT / 'evidence.json').write_text(json.dumps(dict(command='python scripts/validate-clipboard-json-budget.py',
        source_sha256={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [*actual,Path(__file__),ROOT/'scripts/validate-clipboard-rich.py']},
        android_json_sha256={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in json_sources},
        escaped_base64_chars_exact_upper_bound=worst,worst_raw_split=split,
        scope='all valid UTF8 <=4096 bytes split into plain+nonempty HTML; current qj:64-hex ID, conservative 128-byte token/request',
        result='passed',apk=None,device_operations=False),indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
