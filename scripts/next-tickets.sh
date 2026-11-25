#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
import glob
import os
import re

issue_files = sorted(glob.glob(".scratch/heart-zone-monitor/issues/[0-9][0-9]-*.md"))
tickets = {}

for path in issue_files:
    name = os.path.basename(path)
    num = int(name.split("-", 1)[0])
    status = "unknown"
    blockers = []
    title = name
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("# "):
                title = s[2:].strip()
            m_status = re.match(r"^(?:\*\*)?Status:(?:\*\*)?\s*(.+)$", s, re.IGNORECASE)
            if m_status:
                status = m_status.group(1).strip().strip("*").strip()
            m_blocked = re.match(r"^(?:\*\*)?Blocked by:(?:\*\*)?\s*(.+)$", s, re.IGNORECASE)
            if m_blocked:
                raw = m_blocked.group(1).strip()
                if not raw.lower().startswith("none"):
                    # Extract leading 2-digit ticket numbers (e.g. "14 (...), 17 (...)")
                    blockers = [int(x) for x in re.findall(r"(?:^|,\s*)(\d{2})\b", raw)]
    tickets[num] = {
        "num": num,
        "path": path,
        "title": title,
        "status": status,
        "blockers": blockers,
    }

unblocked = []
blocked = []

for num in sorted(tickets):
    t = tickets[num]
    if t["status"] != "ready-for-agent":
        continue
    unresolved = [
        b for b in t["blockers"]
        if b in tickets and tickets[b]["status"] != "resolved"
    ]
    if unresolved:
        blocked.append((t, unresolved))
    else:
        unblocked.append(t)

print("UNBLOCKED (ready-for-agent):")
if not unblocked:
    print("  (none)")
else:
    for t in unblocked:
        print(f"  - {t['title']} ({t['path']})")

if blocked:
    print("\nBLOCKED (ready-for-agent):")
    for t, unresolved in blocked:
        b_str = ", ".join(f"{b:02d}" for b in unresolved)
        print(f"  - {t['title']} [blocked by: {b_str}] ({t['path']})")
PY
