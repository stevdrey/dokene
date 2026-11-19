#!/usr/bin/env python3
"""ABOUTME: Builds issue-340-evidence/outputs.txt from the suite's sanitized run outputs: the final result lines, the
ABOUTME: person-driven browser observations, the real-browser harness outputs and the superseded harness-error runs."""
import pathlib
import sys

OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path.home() / "qa340/out")
DEST = pathlib.Path(__file__).resolve().parent.parent / "outputs.txt"

def lines(name):
    p = OUT / name
    return p.read_text(encoding="utf-8").splitlines() if p.exists() else []

# Superseded runs were saved as <run>-attempt<N>.txt next to the final outputs.
attempts = {p.name: lines(p.name) for p in sorted(OUT.glob("*-attempt*.txt"))}
superseded = {l for ls in attempts.values() for l in ls}
# The first #310 run (REG-06) used two wrong harness assumptions and was rerun. Only its two recorded lines (one
# timestamp, the original labels) are superseded; any other #310 failure stays in the final results.
HARNESS310 = {("#310 25 identical denials -> 25 x 403 with one body", "2026-11-19T04:25:49Z"),
              ("#310 at most 10 denial rows for the key in the 15-minute window (rows before: 0)", "2026-11-19T04:25:49Z")}
harness310 = [l for l in lines("results.tsv")
              if l.split("\t")[0] == "REG-06" and tuple(l.split("\t")[1:4:2]) in HARNESS310]
superseded.update(harness310)

main = [l for l in lines("results.tsv") if l not in superseded and "\t" in l]
def count(ls, verdict):
    return sum(1 for l in ls if l.split("\t")[2:3] == [verdict])

out = []
out.append("# Suite #340 evidence bundle (sanitized). Candidate 753cdff45252e4f64e4eec65df3e23717c730cef.")
out.append("# Columns of the result lines: case, check, verdict, UTC time, sanitized note.")
out.append(f"# Final result lines: {count(main, 'PASS')} PASS, {count(main, 'FAIL')} FAIL, {count(main, 'NOTE')} NOTE.")
out.append("")
out.append("## 1. Result lines (API / DB / stub checks through the real BFF session; final runs)")
out += main
out.append("")
out.append("## 2. Person-driven browser observations (in-app Chromium pane unless stated)")
out += lines("browser-notes.tsv")
out.append("")
for n, title in (("reg02-copy.txt", "REG-02 copy leg, headless Chromium with clipboard permission, real OpenAI"),
                 ("reg04-ui-chromium.txt", "REG-04/REG-05 real-browser harness, Chromium"),
                 ("reg04-ui-firefox.txt", "REG-04/REG-05 real-browser harness, Firefox")):
    out.append(f"## 3. {title}")
    out += lines(n)
    out.append("")
out.append("## 4. #64 clean Keycloak start in a disposable compose project (docker compose up --wait)")
out += [l for l in lines("kc-clean.log") if l.strip()]
out.append("")
out.append("## 5. Superseded runs (HARNESS-ERROR; see the report's setup notes). Not counted above.")
for n, ls in attempts.items():
    out.append(f"### {n}")
    out += [f"HARNESS-ERROR\t{l}" for l in ls if l.strip()]
out.append("### first #310 run")
out += [f"HARNESS-ERROR\t{l}" for l in harness310]
DEST.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"{DEST}: {count(main, 'PASS')} PASS, {count(main, 'FAIL')} FAIL, {count(main, 'NOTE')} NOTE")
