from pathlib import Path

apply_path = Path('.selfrun/apply_dev36.py')
text = apply_path.read_text()
old = '''    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")\n    p.write_text(text.replace(old, new, 1))'''
new = '''    count = text.count(old)\n    if count < 1:\n        raise SystemExit(f"{path}: expected at least one match, found {count}: {old[:100]!r}")\n    p.write_text(text.replace(old, new, 1))'''
if text.count(old) != 1:
    raise SystemExit('apply_dev36 helper shape changed unexpectedly')
apply_path.write_text(text.replace(old, new, 1))

test_path = Path('app/src/test/java/com/shaterguy/fc2weeklyranker/network/AvseeClientDev36RedTest.kt')
test = test_path.read_text()
old_call = '''            "https://example.test/bbs/tag.php?q=test&eq=&page=1",\n        )'''
new_call = '''            "https://example.test/bbs/tag.php?q=test&eq=&page=1",\n            "javfc2",\n        )'''
if test.count(old_call) != 1:
    raise SystemExit('FC2 tag parser test call shape changed unexpectedly')
test_path.write_text(test.replace(old_call, new_call, 1))

legacy_path = Path('app/src/test/java/com/shaterguy/fc2weeklyranker/network/JavTagParserTest.kt')
legacy = legacy_path.read_text()
old_name = 'fun `fc2 detail never exposes jav tag ui data`() {'
new_name = 'fun `fc2 detail exposes the same tag ui data contract`() {'
old_assertion = '        assertTrue(post.tags.isEmpty())'
new_assertion = '''        assertEquals(listOf("#tag"), post.tags.map(RemoteTag::label))
        assertEquals(listOf("#tag"), post.tags.map(RemoteTag::query))'''
if legacy.count(old_name) != 1 or legacy.count(old_assertion) != 1:
    raise SystemExit('legacy FC2 tag parser contract changed unexpectedly')
legacy = legacy.replace(old_name, new_name, 1).replace(old_assertion, new_assertion, 1)
legacy = legacy.replace('import org.junit.Assert.assertTrue\n', '', 1)
legacy_path.write_text(legacy)
print('normalized transformer guard and post-baseline FC2 tag parser contracts')
