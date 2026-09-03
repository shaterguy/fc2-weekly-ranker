#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from collections import Counter
from html.parser import HTMLParser
from urllib.parse import parse_qs, urljoin, urlparse
from urllib.request import Request, urlopen

UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151 Mobile Safari/537.36"
BASE = "https://01.avsee.is/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=FC2PPV&sop=and&gr_id=&srows=10&onetable=&page={}"

class StructureParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.anchors = []
        self.searchish = Counter()

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        cls = data.get("class", "")
        ident = data.get("id", "")
        marker = f"{tag.lower()}#{ident}.{cls}".lower()
        if any(token in marker for token in ("search", "sch", "result", "list-item", "page", "pg_")):
            self.searchish[(tag.lower(), ident, cls)] += 1
        self.stack.append((tag.lower(), data))
        if tag.lower() == "a":
            self.anchors.append((data, [(t, dict(a)) for t, a in self.stack[:-1]]))

    def handle_endtag(self, tag):
        target = tag.lower()
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == target:
                del self.stack[index:]
                break

for page in (1, 2):
    requested = BASE.format(page)
    req = Request(requested, headers={"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8"})
    with urlopen(req, timeout=30) as response:
        final_url = response.geturl()
        status = response.status
        body = response.read().decode(response.headers.get_content_charset() or "utf-8", errors="replace")

    parser = StructureParser()
    parser.feed(body)
    board = []
    signatures = Counter()
    javfc2_signatures = Counter()
    for attrs, ancestors in parser.anchors:
        href = attrs.get("href", "").strip()
        if not href:
            continue
        absolute = urljoin(final_url, href)
        parsed = urlparse(absolute)
        query = parse_qs(parsed.query)
        if not parsed.path.endswith("/bbs/board.php") or not query.get("wr_id"):
            continue
        chain = tuple(
            f"{tag}#{a.get('id','')}.{a.get('class','')}"
            for tag, a in ancestors[-7:]
            if a.get("id") or a.get("class")
        )
        classes = attrs.get("class", "")
        table = query.get("bo_table", [""])[0]
        item = (table, query.get("wr_id", [""])[0], parsed.fragment, classes, chain, attrs.get("href", ""))
        board.append(item)
        signatures[(classes, chain)] += 1
        if table == "javfc2":
            javfc2_signatures[(classes, chain)] += 1

    print(f"PROBE_PAGE page={page} status={status} bytes={len(body)} board_links={len(board)}", flush=True)
    print("PROBE_SEARCHISH " + repr(parser.searchish.most_common(40)), flush=True)
    print("PROBE_BOARD_SIGNATURES " + repr(signatures.most_common(30)), flush=True)
    print("PROBE_JAVFC2_SIGNATURES " + repr(javfc2_signatures.most_common(20)), flush=True)
    for item in board[:60]:
        print("PROBE_BOARD " + repr({
            "bo_table": item[0],
            "wr_id": item[1],
            "fragment": item[2],
            "anchor_class": item[3],
            "ancestor_chain": item[4],
            "href": item[5],
        }), flush=True)

raise SystemExit(72)
PY

python3 -m py_compile tools/derive_test_signing_identity.py tools/derive_stable_signing_identity.py
bash -n tools/sign_test.sh
bash -n tools/sign_stable.sh
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug
