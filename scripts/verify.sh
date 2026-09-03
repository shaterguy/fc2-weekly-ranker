#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from collections import Counter
from html.parser import HTMLParser
from urllib.parse import parse_qs, urljoin, urlparse
from urllib.request import Request, urlopen

UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151 Mobile Safari/537.36"
BASE = "https://01.avsee.is/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=FC2PPV&sop=and&gr_id=&srows=10&onetable=&page={}"

class LinkParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links = []
    def handle_starttag(self, tag, attrs):
        if tag.lower() == "a":
            self.links.append(dict(attrs))

for page in (1, 2):
    requested = BASE.format(page)
    req = Request(requested, headers={"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8"})
    try:
        with urlopen(req, timeout=30) as response:
            final_url = response.geturl()
            status = response.status
            body = response.read().decode(response.headers.get_content_charset() or "utf-8", errors="replace")
    except Exception as exc:
        print(f"PROBE_ERROR page={page} type={type(exc).__name__} value={exc}", flush=True)
        continue

    parser = LinkParser()
    parser.feed(body)
    resolved = []
    for attrs in parser.links:
        href = attrs.get("href", "").strip()
        if not href:
            continue
        absolute = urljoin(final_url, href)
        parsed = urlparse(absolute)
        query = parse_qs(parsed.query)
        resolved.append((attrs, absolute, parsed, query))

    board = [item for item in resolved if item[2].path.endswith("/bbs/board.php") and item[3].get("wr_id")]
    search = [item for item in resolved if item[2].path.endswith("/bbs/search.php") and item[3].get("page")]
    tables = Counter(item[3].get("bo_table", [""])[0] for item in board)
    fc2_ids = [item[3].get("wr_id", [""])[0] for item in board if item[3].get("bo_table", [""])[0] == "javfc2"]
    duplicate_ids = sorted(k for k, v in Counter(fc2_ids).items() if v > 1)

    print(f"PROBE_PAGE page={page} status={status} final={final_url} bytes={len(body)} links={len(resolved)} board_links={len(board)}", flush=True)
    print("PROBE_TABLES " + repr(dict(sorted(tables.items()))), flush=True)
    print("PROBE_FC2_IDS " + repr(fc2_ids[:30]), flush=True)
    print("PROBE_DUPLICATE_FC2_IDS " + repr(duplicate_ids[:30]), flush=True)

    for attrs, absolute, parsed, query in board[:30]:
        compact_attrs = {k: v for k, v in attrs.items() if k in {"href", "class", "id", "rel", "title"}}
        print("PROBE_BOARD_ANCHOR " + repr(compact_attrs), flush=True)
    for attrs, absolute, parsed, query in search[:30]:
        compact_attrs = {k: v for k, v in attrs.items() if k in {"href", "class", "id", "rel", "title"}}
        print("PROBE_SEARCH_ANCHOR " + repr(compact_attrs), flush=True)

raise SystemExit(72)
PY

python3 -m py_compile tools/derive_test_signing_identity.py tools/derive_stable_signing_identity.py
bash -n tools/sign_test.sh
bash -n tools/sign_stable.sh
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug
