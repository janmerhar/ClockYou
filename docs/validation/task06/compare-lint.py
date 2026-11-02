import collections, json, pathlib, xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[3]
def read(path):
    issues = ET.parse(path).getroot().findall('issue')
    return collections.Counter((i.get('severity'), i.get('id'), i.get('message'),
        (i.find('location').get('file', '') if i.find('location') is not None else '').replace('\\', '/').removeprefix(str(ROOT).replace('\\', '/') + '/')) for i in issues)
baseline = read(ROOT / 'docs/validation/baseline/lint-results-debug.xml')
current = read(ROOT / 'app/build/reports/lint-results-debug.xml')
result = {
    'baseline': dict(collections.Counter({s: sum(n for k,n in baseline.items() if k[0] == s) for s in set(k[0] for k in baseline)})),
    'current': dict(collections.Counter({s: sum(n for k,n in current.items() if k[0] == s) for s in set(k[0] for k in current)})),
    'new': [dict(severity=k[0], id=k[1], message=k[2], file=k[3], count=n) for k,n in (current-baseline).items()],
    'removed': [dict(severity=k[0], id=k[1], message=k[2], file=k[3], count=n) for k,n in (baseline-current).items()]
}
print(json.dumps(result, indent=2))
