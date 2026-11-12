# Stash

[![license: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

**Stash**: all your clouds in one place. An Android file manager for [teldrive](https://github.com/tgdrive/teldrive)
(Telegram storage) plus Google Drive, TeraBox, SMB (NAS) and phone storage, powered by rclone.

> **Fork notice.** Stash is a modified version of [Round Sync](https://github.com/newhinton/Round-Sync)
> by Felix Nüsse, by way of [gulp79's rclone-extra fork](https://github.com/gulp79/Round-Sync). It has its own
> name, icon and package id (`io.github.azuizui.stash`), as Round Sync asks of forks. It is **not affiliated with or
> endorsed by** the Round Sync, rclone, teldrive, ONLYOFFICE or PDF.js projects. Full credits and licences:
> [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Features

- Bundled teldrive server and rclone; one `teldrive` remote, with folder-to-channel mappings for uploads.
- Remotes: teldrive, Google Drive, TeraBox, SMB, phone storage, with simplified sign-in forms.
- Send to… any other remote, cut / copy / paste across remotes, tabs, and pinning folders and files to the drawer.
- Built-in viewer: video and audio, images, PDF, text editor, Word and spreadsheet preview.
- Edit Word, Excel and PowerPoint files offline with the ONLYOFFICE mobile editors (modified, AGPL-3.0).
- Fill in, sign and mark up PDFs with PDF.js.
- Edits are uploaded back to the cloud, and Google Docs/Sheets/Slides are saved in place.

## Installation

Download the APK from [Releases](https://github.com/aZuiZui/stash/releases) (arm64-v8a, Android 15+).

The release APK has **no built-in teldrive settings**: the Telegram app id/hash, JWT secret, uploads encryption
key and Google OAuth client are not part of this repository. To use teldrive, build Stash yourself with your
own `teldrive.properties` (see [STASH.md](STASH.md)).

## Building

See [STASH.md](STASH.md). In short: JDK 21, Android SDK + NDK 27.3.13750724, Go ≥ 1.26, then
`./gradlew assembleOssRelease`. The teldrive server and rclone are built from source by the `:teldrive` and
`:rclone` modules from the public upstream repositories at pinned tags (teldrive `1.8.3`, rclone-extra
`v1.75.2-extra`), with Stash's changes applied from `teldrive/patches/` and `rclone/patches/`.

## Versions and teldrive 2.x

Stash 1.2.0 bundles **teldrive 1.8.3** (plus patches) and **rclone-extra v1.75.2-extra** (plus one fix);
exact versions and patches are in [STASH.md](STASH.md#bundled-versions-120). It works with teldrive servers and databases
on 1.8.x. **Teldrive 2.x is not supported yet.** Stash will move to it when the maintainer is ready, and using
2.x needs a recompiled Stash.

## Contributing

Forks and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). Until the move to teldrive 2.x,
pull requests that switch Stash to 2.x won't be merged. Forks that go to 2.x on their own are fine.

## Credits

- [rcloneExplorer](https://github.com/patrykcoding/rcloneExplorer) by **Patryk Kaczmarkiewicz**: the original app.
- [RCX](https://github.com/x0b/rcx) by **x0b**.
- [Round Sync](https://github.com/newhinton/Round-Sync) by **Felix Nüsse** and contributors.
- [Round-Sync (rclone-extra fork)](https://github.com/gulp79/Round-Sync) and [rclone-extra](https://github.com/gulp79/rclone-extra) by **gulp79**.
- [rclone](https://github.com/rclone/rclone) by **Nick Craig-Wood** and contributors. See https://rclone.org/donate/.
- [teldrive](https://github.com/tgdrive/teldrive) and [teldrive-ui](https://github.com/tgdrive/teldrive-ui) by **divyam234 / tgdrive**.
  Stash's teldrive patch includes [tgdrive/teldrive#584](https://github.com/tgdrive/teldrive/pull/584) by **Jaime Linares**.
- ONLYOFFICE editors by **Ascensio System SIA**, packaged for the browser by [**wasmtools/office-web**](https://github.com/wasmtools/office-web).
- [PDF.js](https://github.com/mozilla/pdf.js) by **Mozilla**; [docx-preview](https://github.com/VolodymyrBaydalka/docxjs), [SheetJS](https://sheetjs.com), [JSZip](https://github.com/Stuk/jszip).

Every upstream commit keeps its original author in this repository's history.

## Libraries (inherited from Round Sync)
- [rclone](https://github.com/rclone/rclone) - Calling this a library is an understatement. Without rclone, there would not be Round Sync. See https://rclone.org/donate/ to support rclone.
- [Jetpack AndroidX](https://developer.android.com/license)
- [Floating Action Button SpeedDial](https://github.com/leinardi/FloatingActionButtonSpeedDial) - A Floating Action Button Speed Dial implementation for Android that follows the Material Design specification.
- [Glide](https://github.com/bumptech/glide) - An image loading and caching library for Android focused on smooth scrolling.
- [MarkdownJ](https://github.com/myabc/markdownj) - converts markdown into HTML.
- [Material Design Icons](https://github.com/Templarian/MaterialDesign) - 2200+ Material Design Icons from the Community.
- [Recyclerview Animators](https://github.com/wasabeef/recyclerview-animators) - An Android Animation library which easily add itemanimator to RecyclerView items.
- [Toasty](https://github.com/GrenderG/Toasty) - The usual Toast, but with steroids.
- Icons from [Flaticon](https://www.flaticon.com) courtesy of [Smashicons](https://www.flaticon.com/authors/smashicons) and [Freepik](https://www.flaticon.com/authors/freepik)


## License
Stash is released under the terms of the [GPLv3 license](LICENSE), like Round Sync. Bundled components keep
their own licences (MIT, Apache-2.0, AGPL-3.0); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Contributions to Round Sync were licensed under the MIT license via a
[CLA](https://gist.githubusercontent.com/x0b/889f037d76706fc9e3ab8ee1c047841b/raw/67c028b19e33111428904558cfda0c01039d1574/rcloneExplorer-cla-202001).

## About this app
Round Sync is a fork of [**RCX**](https://github.com/x0b/rcx) by **x0b**<sup>[x0b](https://github.com/x0b)</sup> which is itself a fork of [**rcloneExplorer**](https://github.com/patrykcoding/rcloneExplorer) by **Patryk Kaczmarkiewicz**<sup>[patrykcoding](https://github.com/patrykcoding)</sup> .

If you want to convey a modified version (fork), we ask you to use a different name, app icon and package id as well as proper attribution to avoid user confusion.
