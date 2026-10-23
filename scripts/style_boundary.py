#!/usr/bin/env python3
"""PR61 transitional style boundary: exact existing debt, no new violations.

No directory allowlist. Baseline regeneration is an explicit reviewed
operation; --check never updates it. Kit ownership is a future migration.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MAIN = Path('app/src/main')
BASELINE = Path('scripts/style-boundary-baseline.json')
RULES = (
    ('material3-import', r'(?m)^\s*import\s+androidx\.compose\.material3\.(?!Text(?:\s|;|$)|Icon(?:\s|;|$)|MaterialTheme(?:\s|;|$)|LocalContentColor(?:\s|;|$))(?:\*|[A-Za-z_][A-Za-z_0-9]*)'),
    ('material-theme-constructor', r'\bMaterialTheme\s*\('),
    ('color-hex', r'\bColor\s*\(\s*0[xX][0-9a-fA-F_]+'),
    ('legacy-theme', r'(?m)^\s*import\s+com\.polinalinen\.madre\.ui\.theme(?:\.|\s|;)'),
    ('system-font', r'\b(?:FontFamily\.(?:Default|Serif|SansSerif|Monospace|Cursive)|Typeface\.(?:DEFAULT|DEFAULT_BOLD|SERIF|SANS_SERIF|MONOSPACE))\b'),
    ('toast', r'(?m)^\s*import\s+android\.widget\.Toast\b|\bToast\s*\.\s*makeText\s*\('),
    ('raw-dialog', r'(?m)^\s*import\s+androidx\.compose\.ui\.window\.Dialog(?:\s|;|$)|\b(?:AlertDialog|BasicAlertDialog|Dialog)\s*\('),
    ('material3-qualified-control', r'\bandroidx\.compose\.material3\.(?:Button|TextButton|OutlinedButton|FilledTonalButton|Switch|Slider|FilterChip|InputChip|TextField|OutlinedTextField|ModalBottomSheet|AlertDialog|Scaffold)\s*\('),
)


def code_only(text: str) -> str:
    """Mask comments/strings while preserving offsets and newlines (nested Kotlin comments too)."""
    out=list(text);i=0;n=len(text)
    def mask(a,b):
        for j in range(a,b):
            if out[j]!='\n':out[j]=' '
    while i<n:
        if text.startswith('//',i):
            end=text.find('\n',i);end=n if end<0 else end;mask(i,end);i=end
        elif text.startswith('/*',i):
            start=i;i+=2;depth=1
            while i<n and depth:
                if text.startswith('/*',i):depth+=1;i+=2
                elif text.startswith('*/',i):depth-=1;i+=2
                else:i+=1
            mask(start,i)
        elif text.startswith('"""',i):
            end=text.find('"""',i+3);end=n if end<0 else end+3;mask(i,end);i=end
        elif text[i] in ('"',"'"):
            start=i;quote=text[i];i+=1
            while i<n:
                if text[i]=='\\':i+=2
                elif text[i]==quote:i+=1;break
                else:i+=1
            mask(start,min(i,n))
        else:i+=1
    return ''.join(out)


def scan_text(path: str,text: str) -> list[dict]:
    clean=code_only(text);lines=text.splitlines();found=[]
    for rule,pattern in RULES:
        for m in re.finditer(pattern,clean):
            # Leading whitespace may contain blank lines: locate the first code token.
            offset=m.start()+len(m.group())-len(m.group().lstrip())
            line=clean.count('\n',0,offset)+1
            column=offset-clean.rfind('\n',0,offset)
            found.append({'path':path,'line':line,'column':column,'rule':rule,'source':lines[line-1].strip()})
    return sorted(found,key=lambda x:(x['path'],x['line'],x['column'],x['rule']))


def scan(root: Path) -> list[dict]:
    found=[]
    for p in sorted((root/MAIN).rglob('*')):
        if p.suffix not in ('.kt','.java'):continue
        found.extend(scan_text(p.relative_to(root).as_posix(),p.read_text()))
    return found


def identity(item):return tuple(item[k] for k in ('path','line','column','rule','source'))


def check(root: Path) -> dict:
    baseline=json.loads((root/BASELINE).read_text())
    assert baseline['tracking']=='https://github.com/kamenevd/polinalinen-madre/pull/61'
    assert baseline['reason'] and baseline['count']==len(baseline['violations'])
    allowed={identity(x) for x in baseline['violations']}
    actual=scan(root);new=[x for x in actual if identity(x) not in allowed]
    if new:raise AssertionError('NEW style boundary violations:\n'+json.dumps(new,ensure_ascii=False,indent=2))
    return {'status':'PASS','scope':'all main Kotlin/Java, no directory exceptions','existing_legacy':len(actual),'baseline':baseline['count'],'new':0,'zero_legacy':not actual,'tracking':baseline['tracking']}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--write-baseline',action='store_true');args=parser.parse_args()
    if args.write_baseline:
        violations=scan(ROOT)
        data={'tracking':'https://github.com/kamenevd/polinalinen-madre/pull/61','reason':'Authorized transitional exact legacy baseline before ui/kit migration; no new imports, colors, fonts, Toast or Dialog primitives are permitted. Zero legacy is the next kit stage, NOT claimed here.','source_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'count':len(violations),'violations':violations}
        (ROOT/BASELINE).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'baseline_count':len(violations),'file':str(BASELINE)}))
    else:print(json.dumps(check(ROOT),ensure_ascii=False))

if __name__=='__main__':main()
