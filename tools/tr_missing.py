"""List English strings in a region of mood.html that have no Turkish translation yet.

usage: python tools/tr_missing.py "<text where the region starts>" "<text where it ends>"
prints how many are missing and writes them to tools/missing.json
(only catches tl('...') / label: '...' literals — strings picked by a ternary inside tl() need a manual look)
"""
import re, json, sys, os
here = os.path.dirname(os.path.abspath(__file__))
s = open(os.path.join(here, '..', 'mood.html'), encoding='utf-8').read()
a = s.index(sys.argv[1]); b = s.index(sys.argv[2], a)
js = s[a:b]
BS = chr(92)
lit = r"'((?:[^'\\]|\\.)*)'"
keys = set()
for pat in [r"tl\(" + lit, r"label: " + lit, r"hint: " + lit, r"(?:personal|shared|goal): " + lit]:
    for m in re.finditer(pat, js):
        keys.add(m.group(1).replace(BS + "'", "'"))
d0 = s.index("const I18N"); dtr = s.index("tr:", d0); dend = s.index("\n};", dtr)
dic = s[dtr:dend]
def has(k):
    return ("'" + k.replace("'", BS + "'") + "':") in dic or ('"' + k + '":') in dic
miss = sorted(k for k in keys if not has(k) and re.search('[a-zA-Z]', k))
open(os.path.join(here, 'missing.json'), 'w', encoding='utf-8').write(json.dumps(miss, ensure_ascii=False, indent=0))
print(len(miss))
