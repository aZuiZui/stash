"""Copies a trimmed PDF.js release into the APK assets and wires in Stash's files.

usage: bundle.py <pdfjs-dist.zip> <stash dir> <output dir>
"""
import os
import shutil
import sys
import zipfile

src_zip, stash_dir, out = sys.argv[1:4]

# English UI only; no source maps, debugger or sample document
KEEP_LOCALES = ('web/locale/locale.json', 'web/locale/en-US/')
DROP_SUFFIX = ('.map',)
DROP_FILES = {'web/debugger.mjs', 'web/debugger.css', 'web/compressed.tracemonkey-pldi-09.pdf'}

if os.path.isdir(out):
    shutil.rmtree(out)
os.makedirs(out)

count = 0
with zipfile.ZipFile(src_zip) as z:
    for info in z.infolist():
        name = info.filename
        if info.is_dir() or name.endswith(DROP_SUFFIX) or name in DROP_FILES:
            continue
        if name.startswith('web/locale/') and not name.startswith(KEEP_LOCALES):
            continue
        if '..' in name.split('/') or name.startswith('/'):
            raise SystemExit('unsafe path in zip: ' + name)
        dest = os.path.join(out, name)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with z.open(info) as i, open(dest, 'wb') as o:
            shutil.copyfileobj(i, o)
        count += 1

# Stash's look and glue, loaded before the viewer's own modules
viewer = os.path.join(out, 'web', 'viewer.html')
html = open(viewer, encoding='utf-8').read()
anchor = '<script src="../build/pdf.mjs" type="module"></script>'
assert html.count(anchor) == 1, 'viewer.html changed: find a new place for the Stash scripts'
html = html.replace(anchor, '<script src="stash-pdf.js"></script>\n' + anchor)
anchor = '<link rel="stylesheet" href="viewer.css" />'
assert html.count(anchor) == 1, 'viewer.html changed: find a new place for stash-pdf.css'
html = html.replace(anchor, anchor + '\n    <link rel="stylesheet" href="stash-pdf.css" />')
open(viewer, 'w', encoding='utf-8').write(html)

for f in os.listdir(stash_dir):
    shutil.copy(os.path.join(stash_dir, f), os.path.join(out, 'web', f))
    count += 1

size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(out) for f in fs)
print('>>> pdf.js bundle: %d files, %.1f MB' % (count, size / 1e6))
