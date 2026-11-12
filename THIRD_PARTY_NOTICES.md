# Stash: credits and third-party notices

Stash is free software, released under the **GNU General Public License v3.0** (see `LICENSE`).
It is a modified version of Round Sync, changed for Stash in October and November 2026;
the full source is at <https://github.com/aZuiZui/stash>. Stash is not affiliated with or endorsed
by any of the projects below.

This program comes WITHOUT ANY WARRANTY, to the extent permitted by applicable law.

## Where Stash comes from

| Project | Authors | Licence |
|---|---|---|
| [rcloneExplorer](https://github.com/patrykcoding/rcloneExplorer) | Patryk Kaczmarkiewicz | MIT (`LICENSE_rcloneExplorer-1.7.4`) |
| [RCX](https://github.com/x0b/rcx) | x0b | GPL-3.0 |
| [Round Sync](https://github.com/newhinton/Round-Sync) | Felix Nüsse and contributors | GPL-3.0 |
| [Round-Sync, rclone-extra fork](https://github.com/gulp79/Round-Sync) | gulp79 | GPL-3.0 |

The git history of this repository keeps the authorship of every upstream commit.

## Bundled components

| Component | Authors | Licence | How Stash uses it |
|---|---|---|---|
| [rclone](https://github.com/rclone/rclone) | Nick Craig-Wood and contributors | MIT | `librclone.so` |
| [rclone-extra](https://github.com/gulp79/rclone-extra) | gulp79 | MIT | the rclone fork Stash builds: tag v1.75.2-extra with `rclone/patches/*.patch` |
| [teldrive](https://github.com/tgdrive/teldrive) | divyam234 / tgdrive | MIT (as of the version Stash builds) | `libteldrive.so`: tag 1.8.3 with `teldrive/patches/*.patch` |
| [tgdrive/teldrive#584](https://github.com/tgdrive/teldrive/pull/584) | Jaime Linares | MIT | cron and config fixes, part of `teldrive/patches/0000-teldrive-1.8.3-stash.patch` |
| [teldrive-ui](https://github.com/tgdrive/teldrive-ui) | tgdrive | GPL-3.0 | embedded in the teldrive server (Telegram login page), unmodified release build |
| ONLYOFFICE mobile editors, sdkjs and x2t converter ([ONLYOFFICE](https://github.com/ONLYOFFICE)) | Ascensio System SIA | AGPL-3.0 with additional terms (`app/src/main/assets/licenses/ONLYOFFICE-LICENSE.txt`) | Word / Excel / PowerPoint editor, **modified** (see below) |
| [office-web](https://github.com/wasmtools/office-web) | wasmtools | AGPL-3.0 | packaging of the above plus the self-compiled `x2t.wasm`, commit `80b7e9ebcd2b3d8b6f8208eb4f4d526739d6be5e` |
| ONLYOFFICE fonts ([core-fonts](https://github.com/ONLYOFFICE/core-fonts)) | their respective authors | each font's own licence (OFL, Apache-2.0 and others) | Latin/Greek/Cyrillic/Arabic subset shipped with office-web |
| [PDF.js](https://github.com/mozilla/pdf.js) | Mozilla Foundation and contributors | Apache-2.0 | PDF editor, release 6.5.438, licence files kept in `assets/pdfjs/` |
| [docx-preview](https://github.com/VolodymyrBaydalka/docxjs) | Volodymyr Baydalka | Apache-2.0 | Word viewer |
| [SheetJS Community Edition](https://sheetjs.com) | SheetJS LLC | Apache-2.0 | spreadsheet viewer |
| [JSZip](https://github.com/Stuk/jszip) | Stuart Knightley and contributors | MIT or GPL-3.0 | used by the viewers |
| [AndroidX Media3](https://github.com/androidx/media) | The Android Open Source Project | Apache-2.0 | video and audio player |

The Android libraries inherited from Round Sync are listed in the app under About > Libraries and Credits
and in `README.md`.

## ONLYOFFICE: modification notice

Stash ships a **modified version** of the ONLYOFFICE mobile editors, based on the original ONLYOFFICE
software developed by Ascensio System SIA. Modified on 2026-11-12 (Stash 1.1.0 and 1.2.0):

- `onlyoffice/bundle.py` trims the office-web build (mobile editors, word/cell/slide engines, the
  converter and a font subset) and adds a stylesheet and two scripts to each mobile editor's `index.html`.
- `onlyoffice/stash/stash-ui.css` hides the editor's own toolbar; Stash draws its own.
- `onlyoffice/stash/mobile-shim.js` and `editor.js` make the editors start offline inside the app.

All copyright, licence and warranty notices in the ONLYOFFICE files are kept. In the app, the editor's
⋮ > About the editor shows these notices. ONLYOFFICE is a trademark of Ascensio System SIA; no trademark
rights are granted, and the name is used here only to say where the code comes from.

The corresponding source is this repository plus office-web at the commit above, which `onlyoffice/build.gradle`
checks out.

## Licence texts

The APK carries these licence texts in `assets/licenses/`: GPL-3.0 (Stash, Round Sync, RCX, teldrive-ui),
AGPL-3.0 and ONLYOFFICE's additional terms, and MIT (rclone, rclone-extra, teldrive, rcloneExplorer).
PDF.js, docx-preview, SheetJS and JSZip carry their own licence files next to their code.
