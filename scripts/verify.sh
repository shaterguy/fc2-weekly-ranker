#!/usr/bin/env bash
set -euo pipefail

python3 - <<'PY'
from collections import Counter
from html.parser import HTMLParser
from urllib.parse import parse_qs, urljoin, urlparse
from urllib.request import Request, urlopen

UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/151 Mobile Safari/537.36"
BASE = "https://01.avsee.is/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=FC2PPV&sop=and&gr_id=&srows=10&onetable=javfc2&page={}"

class ProbeParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.anchors = []

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        self.stack.append((tag.lower(), data))
        if tag.lower() == "a":
            ancestors = [dict(item) for _, item in self.stack[:-1]]
            self.anchors.append((data, ancestors))

    def handle_startendtag(self, tag, attrs):
        if tag.lower() == "a":
            self.anchors.append((dict(attrs), [dict(item) for _, item in self.stack]))

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

    parser = ProbeParser()
    parser.feed(body)
    result = []
    pagination = []
    for attrs, ancestors in parser.anchors:
        href = attrs.get("href", "").strip()
        classes = set(attrs.get("class", "").split())
        absolute = urljoin(final_url, href) if href else ""
        parsed = urlparse(absolute) if absolute else None
        query = parse_qs(parsed.query) if parsed else {}
        ancestor_classes = [a.get("class", "") for a in ancestors if a.get("class")]
        ancestor_ids = [a.get("id", "") for a in ancestors if a.get("id")]
        if "sch_res_title" in classes:
            result.append((attrs, absolute, parsed, query, ancestor_classes, ancestor_ids))
        if "pg_end" in classes or "pg_next" in classes or "pg_page" in classes:
            pagination.append((attrs, absolute, query, ancestor_classes, ancestor_ids))

    ids = []
    tables = []
    fragments = []
    for attrs, absolute, parsed, query, ancestor_classes, ancestor_ids in result:
        tables.append(query.get("bo_table", [""])[0])
        ids.append(query.get("wr_id", [""])[0])
        fragments.append(parsed.fragment)

    print(f"PROBE_PAGE page={page} status={status} final={final_url} bytes={len(body)} result_links={len(result)}", flush=True)
    print("PROBE_RESULT_TABLES " + repr(dict(sorted(Counter(tables).items()))), flush=True)
    print("PROBE_RESULT_IDS " + repr(ids), flush=True)
    print("PROBE_RESULT_DUPLICATES " + repr(sorted(k for k, v in Counter(ids).items() if k and v > 1)), flush=True)
    print("PROBE_RESULT_FRAGMENTS " + repr([value for value in fragments if value][:20]), flush=True)
    for attrs, absolute, parsed, query, ancestor_classes, ancestor_ids in result[:15]:
        print("PROBE_RESULT_ANCHOR " + repr({
            "class": attrs.get("class", ""),
            "href": attrs.get("href", ""),
            "bo_table": query.get("bo_table", [""])[0],
            "wr_id": query.get("wr_id", [""])[0],
            "fragment": parsed.fragment,
            "ancestor_classes": ancestor_classes[-6:],
            "ancestor_ids": ancestor_ids[-6:],
        }), flush=True)
    for attrs, absolute, query, ancestor_classes, ancestor_ids in pagination:
        classes = attrs.get("class", "")
        if "pg_end" in classes or "pg_next" in classes:
            print("PROBE_PAGING_ANCHOR " + repr({
                "class": classes,
                "href": attrs.get("href", ""),
                "page": query.get("page", [""])[0],
                "ancestor_classes": ancestor_classes[-4:],
                "ancestor_ids": ancestor_ids[-4:],
            }), flush=True)

raise SystemExit(72)
PY

python3 -m py_compile tools/derive_test_signing_identity.py tools/derive_stable_signing_identity.py
bash -n tools/sign_test.sh
bash -n tools/sign_stable.sh
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug
