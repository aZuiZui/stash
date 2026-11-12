#!/usr/bin/env python3
"""Build Stash's trimmed ONLYOFFICE bundle (app/src/main/assets/onlyoffice/).

usage: bundle.py <office-web/web> <onlyoffice/stash> <out dir>

Keeps only what the offline mobile editors need (about 240 MB raw, ~70 MB compressed in the APK,
from office-web's 676 MB):
  - web-apps: api, the three *mobile* editors (+ main/offline-shim.js), the few vendor libs they load
  - sdkjs: word / cell / slide engines and common (no PDF editor, no desktop sdk-all.bin snapshots,
    no presentation theme gallery)
  - the x2t.wasm converter (without the .orig duplicate)
  - Latin/Greek/Cyrillic/Arabic fonts; CJK, Korean, Japanese and other very large fonts are dropped
    and listed in fonts-fallback.json, so the app can answer for them with a Latin font
Each mobile index.html gets offline-shim.js and Stash's mobile-shim.js. The files in onlyoffice/stash/
(editor.html, editor.js, mobile-shim.js, stash-ui.css) are copied to the root.
"""
import json
import os
import re
import shutil
import sys

WEB, STASH, OUT = (os.path.abspath(a) for a in sys.argv[1:4])
VENDOR = os.path.join(WEB, 'vendor')

EDITORS = ('documenteditor', 'spreadsheeteditor', 'presentationeditor')
WEBAPPS_VENDOR = ('jquery', 'socketio', 'underscore', 'xregexp', 'requirejs')
# Fonts dropped from the bundle (by family name): CJK, Korean, Japanese and a few very large fonts.
DROP_FONTS = re.compile(r'AR PL|WenQuanYi|Noto (Sans|Serif) (SC|TC|KR|JP|HK|CJK)|Nanum|Takao|Tibetan|'
                        r'Droid Sans Fallback|IPA|UKai|UMing|Baekmuk|Unifont|Symbola|FreeSerif|FreeSans|FreeMono')
FALLBACK_FAMILY = 'Liberation Sans'


def font_plan():
    s = open(os.path.join(VENDOR, 'sdkjs/common/AllFonts.js'), encoding='utf-8-sig').read()
    files = re.findall(r'"(\d{3})"', s.split('__fonts_infos')[0])
    infos = re.findall(r'\["([^"]+)",(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+)\]', s)
    keep, drop, fallback = set(), set(), None
    for name, *idx in infos:
        used = {files[int(i)] for i in idx[::2] if int(i) >= 0}
        (drop if DROP_FONTS.search(name) else keep).update(used)
        if name == FALLBACK_FAMILY and int(idx[0]) >= 0:
            fallback = files[int(idx[0])]
    drop -= keep
    if not fallback:
        raise SystemExit('fallback font %r not found in AllFonts.js' % FALLBACK_FAMILY)
    return drop, fallback


def wanted(rel, drop_fonts):
    """rel: path relative to web/vendor, with forward slashes."""
    if rel.endswith(('.orig', '.map')):
        return False
    parts = rel.split('/')
    if parts[0] == 'web-apps':
        if parts[1] == 'vendor':
            return len(parts) > 2 and parts[2] in WEBAPPS_VENDOR
        if parts[1] != 'apps' or len(parts) < 3:
            return False
        app = parts[2]
        if app == 'api':
            return True
        if app == 'common':
            return len(parts) > 3 and parts[3] not in ('main', 'embed')
        if app in EDITORS and len(parts) > 3:
            return parts[3] == 'mobile' or rel.endswith('/main/offline-shim.js')
        return False
    if parts[0] == 'sdkjs':
        if parts[1] == 'pdf' or rel.startswith('sdkjs/slide/themes/') or rel.endswith('sdk-all.bin'):
            return False
        return True
    if parts[0] == 'fonts':
        return parts[-1] not in drop_fonts
    return len(parts) == 1  # top-level files (plugins.json, themes.json)


def patch_mobile_index(path):
    s = open(path, encoding='utf-8').read()
    tag = '<script defer="defer" src="dist/js/app.js"></script>'
    if s.count(tag) != 1:
        raise SystemExit('unexpected mobile index.html layout: ' + path)
    shims = ('<!-- Modified by Stash on 2026-11-12: Stash stylesheet and offline shims added. Based on the '
             'original ONLYOFFICE software developed by Ascensio System SIA (AGPL-3.0 with additional terms). -->'
             '<link rel="stylesheet" href="../../../../../stash-ui.css">'
             '<script src="../main/offline-shim.js"></script>'
             '<script src="../../../../../mobile-shim.js"></script>')
    open(path, 'w', encoding='utf-8').write(s.replace(tag, shims + tag))


def main():
    drop_fonts, fallback = font_plan()
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    n = size = 0
    for dirpath, _, files in os.walk(VENDOR):
        for f in files:
            src = os.path.join(dirpath, f)
            rel = os.path.relpath(src, VENDOR).replace(os.sep, '/')
            if not wanted(rel, drop_fonts):
                continue
            dst = os.path.join(OUT, 'vendor', rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            n += 1
            size += os.path.getsize(src)
    for e in EDITORS:
        patch_mobile_index(os.path.join(OUT, 'vendor/web-apps/apps', e, 'mobile/index.html'))
    for f in os.listdir(STASH):
        shutil.copyfile(os.path.join(STASH, f), os.path.join(OUT, f))
    with open(os.path.join(OUT, 'fonts-fallback.json'), 'w') as fp:
        json.dump({'fallback': fallback, 'dropped': sorted(drop_fonts)}, fp)
    print('>>> onlyoffice bundle: %d files, %.1f MB (%d fonts dropped, fallback %s)'
          % (n, size / 1e6, len(drop_fonts), fallback))


if __name__ == '__main__':
    main()
