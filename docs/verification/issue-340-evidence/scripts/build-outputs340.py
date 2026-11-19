#!/usr/bin/env python3
"""ABOUTME: Builds issue-340-evidence/outputs.txt from the suite's sanitized run outputs, and fails closed: a missing or
ABOUTME: empty input, a FAIL result line, or a browser log that failed, errored or did not finish writes nothing, exit 1.

Usage: build-outputs340.py [OUT_DIR (default ~/qa340/out)] [DEST (default ../outputs.txt)]"""
import pathlib
import sys

OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path.home() / "qa340/out")
DEST = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else pathlib.Path(__file__).resolve().parent.parent / "outputs.txt"
REQUIRED = ("results.tsv", "browser-notes.tsv", "reg02-copy.txt", "reg04-ui-chromium.txt", "reg04-ui-firefox.txt",
            "kc-clean.log")
# Expected values of the REG-02 copy log, the same as EXPECT in reg02-copy340.mjs.
REG02_EXPECT = {"generated_is_spanish": "true", "generated_no_link_or_price": "true",
                "clipboard_equals_edited_draft": "true", "edited_suffix_in_clipboard": "true",
                "copy_feedback": "Borrador copiado", "local_edit_notice": "true",
                "no_send_or_approve_without_review": "true"}
REG04_MIN_ASSERTS = 12  # reg04-ui340.mjs: 8 REG-04 assertions + 4 REG-05 layout assertions
errors = []


def lines(name):
    p = OUT / name
    return p.read_text(encoding="utf-8").splitlines() if p.exists() else []


for name in REQUIRED:
    if not [l for l in lines(name) if l.strip()]:
        errors.append(f"{name}: missing or empty")

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


if count(main, "FAIL"):
    errors.append(f"results.tsv: {count(main, 'FAIL')} FAIL line(s)")
notes = [l for l in lines("browser-notes.tsv") if l.strip()]
if any(l.split("\t")[3:4] == ["FAIL"] for l in notes):
    errors.append("browser-notes.tsv: a FAIL observation")

reg02 = dict(l.split("\t", 1) for l in lines("reg02-copy.txt") if "\t" in l)
if "ERROR" in reg02:
    errors.append("reg02-copy.txt: the run raised an exception")
for k, v in REG02_EXPECT.items():
    if reg02.get(k) != v:
        errors.append(f"reg02-copy.txt: {k} expected {v!r}, got {reg02.get(k, 'missing')!r}")
if not reg02.get("generated_length", "0").isdigit() or int(reg02.get("generated_length", "0")) == 0:
    errors.append("reg02-copy.txt: no generated draft")

for name in ("reg04-ui-chromium.txt", "reg04-ui-firefox.txt"):
    ls = lines(name)
    asserts = [l.split("\t") for l in ls if l.startswith("ASSERT\t")]
    if any(l.startswith("ERROR\t") for l in ls):
        errors.append(f"{name}: the run raised an exception")
    if len(asserts) < REG04_MIN_ASSERTS:
        errors.append(f"{name}: {len(asserts)} assertion(s), at least {REG04_MIN_ASSERTS} expected (incomplete run)")
    if any(a[4:5] != ["PASS"] for a in asserts):  # ASSERT, case, browser, check, verdict, detail
        errors.append(f"{name}: a failed assertion")
    if not any(l.startswith("DONE\t") and l.endswith("\tfailed=0") for l in ls):
        errors.append(f"{name}: no successful DONE line (incomplete or failed run)")

if errors:
    print("refusing to build the evidence bundle:", *errors, sep="\n  ", file=sys.stderr)
    sys.exit(1)

out = []
out.append("# Suite #340 evidence bundle (sanitized). Candidate 753cdff45252e4f64e4eec65df3e23717c730cef.")
out.append("# Columns of the result lines: case, check, verdict, UTC time, sanitized note.")
out.append(f"# Final result lines: {count(main, 'PASS')} PASS, {count(main, 'FAIL')} FAIL, {count(main, 'NOTE')} NOTE.")
out.append("")
out.append("## 1. Result lines (API / DB / stub checks through the real BFF session; final runs)")
out += main
out.append("")
out.append("## 2. Person-driven browser observations (in-app Chromium pane unless stated)")
out += notes
out.append("")
for n, title in (("reg02-copy.txt", "REG-02 copy leg, headless Chromium with clipboard permission, real OpenAI"),
                 ("reg04-ui-chromium.txt", "REG-04/REG-05 real-browser harness, Chromium (fail-closed assertions)"),
                 ("reg04-ui-firefox.txt", "REG-04/REG-05 real-browser harness, Firefox (fail-closed assertions)")):
    out.append(f"## 3. {title}")
    out += lines(n)
    out.append("")
out.append("## 4. #64 clean Keycloak start in a disposable compose project (docker compose up --wait)")
out += [l for l in lines("kc-clean.log") if l.strip()]
out.append("")
out.append("## 5. Superseded runs (harness errors or earlier harness revisions; see the report's setup notes). Not counted.")
for n, ls in attempts.items():
    out.append(f"### {n}")
    out += [f"SUPERSEDED\t{l}" for l in ls if l.strip()]
out.append("### first #310 run")
out += [f"SUPERSEDED\t{l}" for l in harness310]
DEST.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"{DEST}: {count(main, 'PASS')} PASS, {count(main, 'FAIL')} FAIL, {count(main, 'NOTE')} NOTE")
