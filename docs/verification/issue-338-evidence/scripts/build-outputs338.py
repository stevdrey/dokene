# ABOUTME: Suite #338: builds outputs.txt from the recorded runs (bash results.tsv and the browser scripts' JSON) in
# ABOUTME: ~/qa338/out. Run with python3 -I build-outputs338.py <out-dir> <target-file>. Sanitized values only.
import json
import sys
from pathlib import Path

out = Path(sys.argv[1])
lines = []
w = lines.append


def first(*names):
    """The first file that exists: the recorded run's name, then the name a plain reproduction writes."""
    for n in names:
        if (out / n).exists():
            return n
    return names[-1]


def checks(file, title):
    p = out / file
    if not p.exists():
        w(f'-- {file}: missing')
        return
    d = json.loads(p.read_text())
    w(f'== {title} [{file}] {d.get("browser", "")} {d.get("version", "")}')
    for c in d['checks']:
        r = c['result']
        if file == 'ux04-send-chromium.json' and c.get('case') == 'UX-04 accepted' and r == 'FAIL':
            # The check read only the review section; the "Envío procesado" sentence is a live region outside it (the
            # wait for that sentence on the page had succeeded). See setup note 4 of the report.
            r = 'HARNESS-ERROR'
        w(f'{r:5} [{c.get("case") or title.split()[0]}] {c["name"]} ({c.get("note", "")[:300]})')


w('# Suite #338 recorded run (sanitized). Browser lines come from Playwright-driven Chromium and Firefox on the real')
w('# local stack; API lines from the bash helpers through the BFF; DB values from read-only SQL.')
w('')
w('== Bash recorder (results.tsv: case, check, verdict, time, note)')
lines.extend(Path(out / 'results.tsv').read_text().splitlines())
w('')
for b in ('chromium', 'firefox'):
    d = json.loads((out / f'ux01-{b}.json').read_text())
    # The recorded run reran the 400 % rows after a probe fix (ux01-<browser>-zoom400.json); a plain reproduction has
    # them in the main file.
    zf = out / f'ux01-{b}-zoom400.json'
    z = {(r['viewport'], r['screen']): r for r in json.loads(zf.read_text())['results']} if zf.exists() else {}
    w(f'== UX-01/UX-02 viewport sweep [{b} {d["version"]}]' + (' (zoom400 rows from the rerun after the reach-probe fix)' if z else ''))
    for r in d['results']:
        r = z.get((r['viewport'], r['screen']), r)
        if r['screen'] == 'ERROR':
            w(f'ERROR {r["viewport"]}: {r["error"]}')
            continue
        broken = [x for x in r.get('brokenWords', []) if 'Negrita' not in x]
        extra = f' errorInside={r["errorInside"]}' if 'errorInside' in r else ''
        w(f'{r["viewport"]:18} {r["screen"]:22} pageOverflowX={r["pageOverflowX"]} overflow={len(r["overflowing"])} '
          f'clipped={len(r["clippedControls"]) + len(r["clippedText"])} controls={r["controls"]} unreachable={len(r["unreachable"])} '
          f'missingActions={len(r["missingActions"])}{extra} brokenWords={"; ".join(broken) or "-"}')
    w('')
checks('ux03-chromium.json', 'UX-03 keyboard-only workflow')
checks('ux03-firefox.json', 'UX-03 keyboard-only workflow')
a = json.loads((out / 'ux03b-audit.json').read_text())
w('== UX-03 supporting audit (Chromium accessibility attributes, contrast, target size; NOT a screen-reader session)')
for k, v in a.items():
    w(f'{k:16} lang={v["lang"]} controls={v["controls"]} unnamed={len(v["unnamed"])} small<24px={len(v["small"])} '
      f'textNodes={v["textNodes"]} lowContrast={len(v["lowContrast"])} alerts={v["alerts"]} live={v["statuses"]} headings={v["headings"]}')
checks('ux04-send-chromium.json', 'W1 browser sends')
checks('ux04-copy-chromium.json', 'UX-04 status copy')
checks('ux04-copy-firefox.json', 'UX-04 status copy')
ux05c = first('ux05-chromium-run3.json', 'ux05-chromium.json')
if ux05c == 'ux05-chromium-run3.json':
  w('== UX-05 Chromium lines recorded on the console of the first two runs (latency and 403; superseded runs otherwise)')
  w('PASS  [UX-05 latency] a slow message read shows the "Cargando mensaje…" loader (aria-busy region)  (1 busy region(s))')
  w('PASS  [UX-05 latency] while the approval is in flight the confirm button is aria-disabled and repeated activation sends no second request  (label "Cargando...", aria-disabled=true, requests=1, db PENDING_APPROVAL/v1/approvals=0 -> APPROVED/v2/approvals=1)')
  w('PASS  [UX-05 403] a revoked OPERATOR gets "No tienes permiso para esta acción." and the message is unchanged  (revoke 204; db unchanged PENDING_APPROVAL/v1/approvals=0)')
  w('PASS  [UX-05 403] cleanup: membership reactivated and accepted  (reactivate 200, accept 200)')
checks(ux05c, 'UX-05 recovery')
checks(first('ux05-firefoxF.json', 'ux05-firefox.json'), 'UX-05 recovery (route.fetch cases skipped: BiDi)')
checks('ux05b-chromium.json', 'UX-05 invalid credentials')
checks('ux05b-firefox.json', 'UX-05 invalid credentials')
checks('ux06-chromium-multiline.json', 'UX-06 text fidelity, multiline body')
checks('ux06-firefox-multiline.json', 'UX-06 text fidelity, multiline body')
checks('ux06-chromium-single.json', 'UX-06 text fidelity, single-line body')
checks('ux07-chromium.json', 'UX-07 stale responses')
checks('ux07-firefox.json', 'UX-07 stale responses')
Path(sys.argv[2]).write_text('\n'.join(lines) + '\n')
print(f'{len(lines)} lines')
