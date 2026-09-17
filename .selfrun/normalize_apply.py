from pathlib import Path

p = Path('.selfrun/apply_dev36.py')
text = p.read_text()
old = '''    count = text.count(old)\n    if count != 1:\n        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")\n    p.write_text(text.replace(old, new, 1))'''
new = '''    count = text.count(old)\n    if count < 1:\n        raise SystemExit(f"{path}: expected at least one match, found {count}: {old[:100]!r}")\n    p.write_text(text.replace(old, new, 1))'''
if text.count(old) != 1:
    raise SystemExit('apply_dev36 helper shape changed unexpectedly')
p.write_text(text.replace(old, new, 1))
print('normalized apply_dev36 first-match guard')
