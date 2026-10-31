#!/usr/bin/env python3
"""Builds guia.json, the TV guide of Curitiba's free-to-air channels, from meuguia.tv (the networks' national guide).

Run by .github/workflows/guide.yml every 8 hours; the TV (NetGuide) downloads the file from the "guia" release.
  python3 tools/guia_br.py guia.json            fetch every channel
  python3 tools/guia_br.py --test page.html     parse one saved page and print what it found
Only the standard library: no installs on the runner.
"""
import html
import json
import re
import sys
import time
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

TZ = ZoneInfo("America/Sao_Paulo")
# Curitiba's virtual channel, the name on screen and meuguia.tv's id for the network's guide.
CHANNELS = [
    ("2.1", "Band", "BAN"),
    ("4.1", "Massa (SBT)", "SBT"),
    ("7.1", "RIC (Record)", "REC"),
    ("10.1", "TV Senado", "SEN"),
    ("10.4", "TV Câmara", "CAM"),
    ("12.1", "RPC (Globo)", "GRD"),
    ("26.1", "Rede Vida", "VDA"),
    ("44.1", "TV Aparecida", "TAP"),
    ("46.1", "Record News", "RCN"),
    ("49.1", "RedeTV!", "RTV"),
    ("51.1", "Canção Nova", "CNV"),
]
URL = "https://meuguia.tv/programacao/canal/{}"

DAY = re.compile(r'<li class="subheader[^"]*">[^<,]*,\s*(\d{1,2})/(\d{1,2})\s*</li>')
ITEM = re.compile(r"<div class=['\"]lileft time['\"]>\s*(\d{1,2}):(\d{2})\s*</div>\s*"
                  r"<div class=\"licontent\">\s*<h2>(.*?)</h2>", re.S)


def parse(page, now=None):
    """[(start, end, title)] in epoch seconds, in page order; each ends where the next starts."""
    now = now or datetime.now(TZ)
    page = re.sub(r"<!--.*?-->", "", page, flags=re.S)   # the page carries its template in comments
    events = sorted([(m.start(), "day", m) for m in DAY.finditer(page)]
                    + [(m.start(), "item", m) for m in ITEM.finditer(page)], key=lambda e: e[0])
    day, out = None, []
    for _, kind, m in events:
        if kind == "day":
            d, mo = int(m.group(1)), int(m.group(2))
            year = now.year
            if mo == 1 and now.month == 12:
                year += 1          # the guide runs into January
            elif mo == 12 and now.month == 1:
                year -= 1
            day = (year, mo, d)
        elif day:
            start = datetime(day[0], day[1], day[2], int(m.group(1)), int(m.group(2)), tzinfo=TZ)
            title = html.unescape(re.sub(r"\s+", " ", m.group(3))).strip()
            out.append([int(start.timestamp()), 0, title])
    for i in range(len(out) - 1):
        out[i][1] = out[i + 1][0]
    if out:
        out[-1][1] = out[-1][0] + 3600   # the last one: an hour, until the next file
    return [p for p in out if p[1] > p[0]]


def fetch(site_id):
    req = urllib.request.Request(URL.format(site_id), headers={"User-Agent": "Mozilla/5.0 (TV guide for one TV)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def main(argv):
    if len(argv) == 3 and argv[1] == "--test":
        for start, end, title in parse(open(argv[2], encoding="utf-8").read()):
            print(datetime.fromtimestamp(start, TZ).strftime("%d/%m %H:%M"),
                  datetime.fromtimestamp(end, TZ).strftime("%H:%M"), title)
        return 0
    out_path = argv[1] if len(argv) > 1 else "guia.json"
    now = int(time.time())
    channels, failed = [], 0
    for number, name, site_id in CHANNELS:
        try:
            programs = [p for p in parse(fetch(site_id)) if p[1] > now - 3600 and p[0] < now + 36 * 3600]
        except Exception as e:   # one channel down doesn't stop the others
            print(f"{name}: {e}", file=sys.stderr)
            programs, failed = [], failed + 1
        print(f"{number} {name}: {len(programs)} programs")
        channels.append({"n": number, "name": name, "p": programs})
        time.sleep(1)
    if failed == len(CHANNELS):
        print("no channel answered: keeping the last guide", file=sys.stderr)
        return 1
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"made": now, "channels": channels}, f, ensure_ascii=False, separators=(",", ":"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
