# Stash

**Stash**: all your clouds in one place. Android client for teldrive (Telegram storage) plus Google Drive,
TeraBox, SMB (NAS) and phone storage. A fork of [gulp79/Round-Sync](https://github.com/gulp79/Round-Sync)
(git remote `upstream`), stripped down to teldrive, bundling:

| Part | Source | Lands as |
|---|---|---|
| teldrive server | [tgdrive/teldrive](https://github.com/tgdrive/teldrive) tag `1.8.3` plus `teldrive/patches/*.patch` | `app/lib/<abi>/libteldrive.so` |
| rclone | [gulp79/rclone-extra](https://github.com/gulp79/rclone-extra) tag `v1.75.2-extra` plus `rclone/patches/*.patch` | `app/lib/<abi>/librclone.so` |
| UI | Round-Sync (this repo) + teldrive-ui (embedded in the server, used for the Telegram login) | APK |
| PDF editor | [Mozilla PDF.js](https://github.com/mozilla/pdf.js) release zip at a pinned version + SHA-256 (Apache-2.0), trimmed by `pdfjs/bundle.py` | `app/src/main/assets/pdfjs/` (8 MB) |
| Office editor | [wasmtools/office-web](https://github.com/wasmtools/office-web) at a pinned commit (ONLYOFFICE 9.4 mobile editors + x2t.wasm, AGPL-3.0), trimmed by `onlyoffice/bundle.py` | `app/src/main/assets/onlyoffice/` (~70 MB in the APK) |

Android package: `io.github.azuizui.stash`. Android 15+ (minSdk 35), arm64-v8a by default.

Name kept on purpose: the teldrive `session-instance = "teldrive-apk"` (changing it logs Telegram out).

## What the app does

1. First run: asks for your teldrive **database IP, user and password** (port and DB name are pre-filled).
2. Starts the bundled teldrive server on `127.0.0.1:18080` against that DB.
3. Opens the teldrive login page: log in with Telegram (QR or phone).
4. Creates one rclone remote, `teldrive`, from the session, with the `rclone.conf` settings baked in at build
   time (chunk size, upload concurrency, encryption). No per-channel remotes.
5. From then on a foreground service keeps the server running (also after reboot).

The phone instance shares the database of your main teldrive server (for example on a NAS), so it is set
up to stay out of that server's way:
`db.migrate = false` (never touches the schema), cron jobs off, and Telegram bot sessions kept
in a local JSON file (patch 0002; bbolt corrupted itself on Android) instead of the shared DB.

## Features (1.0.0)

- Bundled teldrive server + rclone; one `teldrive` remote; folder → Telegram channel mappings
  (seeded at build time from `channel-folders.txt`, see `td.channelFolders`).
- Remotes: teldrive, Google Drive, TeraBox, SMB, phone storage (simplified sign-in forms).
- **Send to…** from any remote to any other (copies run in the background).
- **Tabs** (+ at the top of the browser) and **Pin to drawer** for any folder or file.
- Built-in **viewer**, Google Drive style: swipe through a folder's files; video/audio (Media3:
  double-tap ±10 s, speed, audio/subtitle tracks, resume), images (zoom, swipe down to close),
  PDF, and a **text editor** (saves back to the remote).
- Simplified menus (hidden items are only switched off in `res/menu/*.xml`).
- Phone teldrive server: bot sessions in a JSON file (bbolt corrupts on Android), streams through
  one warm bot (`bots-limit = 1`).

## Office editor (1.1.0)

Word, Excel and PowerPoint files are edited inside Stash, offline, with the ONLYOFFICE *mobile* editors
(`OfficeEditorActivity`): the viewer's ✎ button opens them, ✓ / ← / back saves, the viewer uploads the
result like any edit (Google Docs/Sheets are converted back in place). Google Docs/Sheets look: Stash draws
the top bar (done, undo, redo, ⋮) and the formatting bar (B I U, colour, highlight, alignment) and calls the
engine through `window.stashCmd()`; ONLYOFFICE's own toolbar is hidden by `onlyoffice/stash/stash-ui.css`.
Auto-save writes a draft to `filesDir/drafts/<sha1(remote+path)>/` every 30 s and when the app is left;
an editor closed before Done offers Continue / Discard next time.

- `:onlyoffice:buildAll` (a `preBuild` dependency) checks office-web out into `onlyoffice/cache/` and runs
  `bundle.py`: mobile editors, word/cell/slide engines, the converter and Latin fonts only (238 MB raw).
  Dropped fonts (CJK…) are answered with Liberation Sans (`fonts-fallback.json`).
- Offline fixes Stash adds (`onlyoffice/stash/`): `mobile-shim.js` signals "server connected" after the file
  loads (the mobile app never calls `auth()`, so the page stayed blank); `editor.js` opens in page layout
  (`customization.mobile.standardView`, not the reflowed Mobile View) and watches both event families
  (Word `asc_onCanUndo`, spreadsheet `asc_onCanUndoChanged` / `asc_onEditCell`).
- 1.2.0: editor and converter load in parallel (`mobile-shim.js` holds `_openEmptyDocument` until the
  converted file is ready, ~1.2 s to open); the offline licence reports build 9.4.0 so the mobile app no
  longer shows "Editor updated, the page will be reloaded"; ⋮ Undo all changes; edits are uploaded when the
  app is left (`EditUploads`). `.xls` saves back as `.xls`; old binary `.ppt` can't be written, so it is
  preview-only.

## PDF editor (1.2.0)

PDFs open in the same `OfficeEditorActivity` (PDF mode) with PDF.js: fill in form fields by tapping them,
and a bottom bar with Highlight, Draw, Add text, Sign (type / draw / image) and Add image. Saving uses
`pdfDocument.saveDocument()`, so form values and markup are written into the PDF; drafts, ✓, undo/redo and
upload-on-leave work as for Office files.

- `:pdfjs:buildAll` downloads the pinned release into `pdfjs/cache/` (checksum checked) and runs
  `bundle.py`: no source maps, debugger, sample PDF or other UI languages; injects `pdfjs/stash/`.
- `stash-pdf.js` sets the viewer options (no preferences, no PDF scripting, signature tool on) and speaks
  the editor's StashBridge contract; `stash-pdf.css` hides PDF.js's toolbar (Stash draws its own bars).

## Look (1.2.0)

Google Drive palette instead of the wallpaper's dynamic colours (`values/colors.xml`, `values-night`):
white pages, grey search pill (`searchBar`), pale-blue FAB / selection / tabs (`#C2E7FF`), blue accents
(`#0B57D0`). The viewer bar is solid over documents, PDFs and presentations; presentations show the first
slide, the slide count and Open. Selecting files shows a tint, a tick, "N selected" and select all.

## Bundled versions (1.2.0)

| Part | Version in 1.2.0 |
|---|---|
| teldrive server | **1.8.3** (tgdrive tag `1.8.3` = `d400a2d`) + `teldrive/patches/0000-teldrive-1.8.3-stash.patch` (tgdrive/teldrive#584 by Jaime Linares, plus Stash fixes for streaming, bots, probing, refused parts and per-user scoping), `0001` (Android host, no migrations) and `0002` (file session storage); the server reports `1.8.3-stash-apk` |
| teldrive-ui | tgdrive `latest` release zip, asset dated 2026-03-02 (pre-2.0) |
| rclone | **rclone-extra v1.75.2-extra** (gulp79 tag = `56bf1388`) + `rclone/patches/0001-chunkedreader-parallel-short-stream.patch` (a parallel stream that ends early fails the read instead of hanging); the app shows `v1.75.2` |
| PDF.js | 6.5.438 |
| ONLYOFFICE (office-web) | ONLYOFFICE 9.4, office-web `80b7e9eb` |

**teldrive 2.x is not supported yet.** Upstream teldrive was rebuilt for 2.0 on 2026-10-10 (new backend
and web UI). Stash's server is 1.8.x and shares your teldrive DB with `db.migrate = false`, so it only works
with a 1.8.x server and schema. Moving to 2.x means building a 2.x server (`-Ptd.teldriveRef=…`), redoing the
patches and the embedded UI (2.0 ships its own instead of teldrive-ui), and a new Stash version. That move
happens when the maintainer is ready (see CONTRIBUTING.md).

**rclone-extra:** gulp79/rclone-extra v1.76.0-extra (2026-11-06) and later target teldrive 2.x. Stash stays
on v1.75.2-extra until the move to 2.x.

Sources are pinned (teldrive and rclone-extra tags, PDF.js version + SHA-256, office-web commit), except
teldrive-ui, which is fetched from its `latest` release.

## Build-time settings

Telegram app id/hash, JWT secret, uploads encryption key and allowed users are **baked in at build time**
from your teldrive server's `config.toml` (and, optionally, `rclone.conf` with a `[teldrive]` remote and
`channel-folders.txt` with `Folder/Sub|channel_id` lines), all in `teldrive-config/` (git-ignored):

```bash
python3 -I scripts/gen-teldrive-secrets.py [CONFIG_DIR]   # writes teldrive.properties (git-ignored, 0600)
```

Your own Google OAuth client for Drive remotes is optional: `google.properties` (git-ignored) with
`gdrive.clientId=` and `gdrive.clientSecret=`. Without it, Drive remotes use rclone's default client.

Never commit these files, and don't share an APK built with them: the keys can be pulled out of it.
Without `teldrive.properties` the app still builds (Gradle warns), but its teldrive server has no Telegram
app id/hash, JWT secret or encryption key matching your teldrive server, so build with your own to use teldrive.
The DB user/password are never baked in; they are typed in the app and kept in its private storage.

## Build

Toolchain: JDK 21, Android SDK + NDK 27.3.13750724, Go ≥ 1.26 (rclone-extra needs it), git, python3.

```bash
export JAVA_HOME=/path/to/jdk-21 ANDROID_HOME=/path/to/android-sdk GOTOOLCHAIN=local
python3 -I scripts/gen-teldrive-secrets.py
export CMD_RELEASE_KEYSTORE=/path/to/release.jks CMD_RELEASE_KEYSTORE_PASSWORD=... \
       CMD_RELEASE_KEY_ALIAS=... CMD_RELEASE_KEY_PASSWORD=...   # your release signing key
./gradlew assembleOssRelease      # or assembleOssDebug
```

APK: `app/build/outputs/apk/oss/release/stash_v*-oss-arm64-v8a-release.apk`. Keep your signing key safe:
updates must be signed with the same key. Don't run `clean` together with a build in one Gradle call
(the rclone `clean` deletes the cache folder the build has just created); delete `app/build` instead.

Version: `de.felixnuesse.extract.versionName` / `versionCode` in `gradle.properties` (base code ends in 0;
the ABI digit is added). Gradle properties (`-P...`): `td.abis` (default `arm64-v8a`), `td.teldriveRepo`,
`td.teldriveRef`, `td.rcloneRepo`, `td.rcloneRef`, `td.uiZip`.

Build caches (`teldrive/cache`, `rclone/cache`, `rclone/gopath_env`, `onlyoffice/cache`, `pdfjs/cache`)
are git-ignored; each build resets teldrive and rclone to the pinned ref and applies the patches again.

## Updating from upstream

```bash
git fetch upstream && git merge upstream/master
```
