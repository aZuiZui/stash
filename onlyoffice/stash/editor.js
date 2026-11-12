// stash: offline ONLYOFFICE mobile editor host for OfficeEditorActivity.
//
// open: Stash serves the file at ./file -> x2t.wasm converts it to ONLYOFFICE's bin -> the mobile editor
//       loads it through offline-shim.js (AscCommon.getEmpty returns window.__ooPendingDocBin).
// save: Stash calls stashSave() -> editor bin (__ooGetFileData) -> x2t back to the original format ->
//       bytes go to StashBridge in base64 chunks.
// x2tConvert/extractOoxmlMedia are adapted from github.com/wasmtools/office-web (web/app.js, AGPL-3.0).
(function () {
    'use strict';
    var B = window.StashBridge;
    var info = JSON.parse(B.info());          // { name, ext }
    var ext = info.ext.toLowerCase();
    var X2T_DIR = 'vendor/sdkjs/common/wasm/x2t/';
    var dirty = false;
    var t0 = performance.now();
    function mark(what) { B.log('open ' + what + ' +' + Math.round(performance.now() - t0) + 'ms'); }

    function fail(msg) { B.onError(String(msg && (msg.message || msg) || 'error')); }
    window.addEventListener('error', function (e) { B.log('error ' + e.message + ' @' + e.filename + ':' + e.lineno); });

    function documentType(e) {
        if (['xlsx', 'xlsm', 'xls', 'ods', 'csv'].indexOf(e) >= 0) return 'cell';
        if (['pptx', 'ppt', 'odp'].indexOf(e) >= 0) return 'slide';
        return 'word';
    }

    // ---- x2t.wasm ----
    function loadX2t() {
        return fetch(X2T_DIR + 'x2t.wasm').then(function (r) {
            if (!r.ok) throw new Error('converter missing (' + r.status + ')');
            return r.arrayBuffer();
        }).then(function (bytes) {
            mark('converter read');
            return new Promise(function (resolve, reject) {
                window.Module = {
                    wasmBinary: bytes,
                    locateFile: function (p) { return X2T_DIR + p; },
                    onRuntimeInitialized: function () { mark('converter ready'); resolve(window.Module); }
                };
                var s = document.createElement('script');
                s.src = X2T_DIR + 'x2t.js';
                s.onerror = function () { reject(new Error('converter failed to load')); };
                document.head.appendChild(s);
            });
        });
    }

    function resetWorking(mod) {
        var rmrf = function (p) {
            var st;
            try { st = mod.FS.stat(p); } catch (_) { return; }
            if (mod.FS.isDir(st.mode)) {
                mod.FS.readdir(p).forEach(function (e) { if (e !== '.' && e !== '..') rmrf(p + '/' + e); });
                mod.FS.rmdir(p);
            } else {
                mod.FS.unlink(p);
            }
        };
        try { rmrf('/working'); } catch (_) {}
        mod.FS.mkdir('/working');
        mod.FS.mkdir('/working/fonts');
        mod.FS.mkdir('/working/themes');
    }

    // Converting to bin resets /working; converting back must not, x2t reads the extracted media from it.
    function x2tConvert(mod, input, fromExt, toExt) {
        if (toExt === 'bin') resetWorking(mod);
        var inName = 'doc.' + fromExt, outName = 'out.' + toExt;
        var stream = mod.FS.open('/working/' + inName, 'w');
        mod.FS.write(stream, input, 0, input.length, 0);
        mod.FS.close(stream);
        mod.FS.writeFile('/working/params.xml',
            '<?xml version="1.0" encoding="utf-8"?>' +
            '<TaskQueueDataConvert xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">' +
            '<m_sFontDir>/working/fonts/</m_sFontDir><m_sThemeDir>/working/themes</m_sThemeDir>' +
            '<m_sFileFrom>/working/' + inName + '</m_sFileFrom><m_sFileTo>/working/' + outName + '</m_sFileTo>' +
            '<m_bIsNoBase64>false</m_bIsNoBase64><m_nCsvTxtEncoding>46</m_nCsvTxtEncoding><m_nCsvDelimiter>4</m_nCsvDelimiter>' +
            '</TaskQueueDataConvert>');
        var ret = mod.ccall('main1', 'number', ['string'], ['/working/params.xml']);
        if (ret !== 0) throw new Error('conversion failed (' + ret + ')');
        return mod.FS.readFile('/working/' + outName);
    }

    // Images under */media/* as data: URLs; offline-shim maps the editor's _offline_media/<name> to them.
    function extractOoxmlMedia(bytes) {
        var exact = {}, base = {};
        try {
            var dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), eocd = -1;
            for (var i = bytes.length - 22; i >= 0 && i >= bytes.length - 22 - 65536; i--) {
                if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
            }
            if (eocd < 0) return Promise.resolve({ exact: exact, base: base });
            var count = dv.getUint16(eocd + 10, true), off = dv.getUint32(eocd + 16, true);
            var decoder = new TextDecoder(), jobs = [];
            var MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', svg: 'image/svg+xml',
                bmp: 'image/bmp', webp: 'image/webp', emf: 'image/x-emf', wmf: 'image/x-wmf', tiff: 'image/tiff', tif: 'image/tiff' };
            for (var e = 0; e < count; e++) {
                if (dv.getUint32(off, true) !== 0x02014b50) break;
                var method = dv.getUint16(off + 10, true), cSize = dv.getUint32(off + 20, true);
                var nameLen = dv.getUint16(off + 28, true), extraLen = dv.getUint16(off + 30, true);
                var cmtLen = dv.getUint16(off + 32, true), lh = dv.getUint32(off + 42, true);
                var name = decoder.decode(bytes.subarray(off + 46, off + 46 + nameLen));
                off += 46 + nameLen + extraLen + cmtLen;
                var m = /(?:^|\/)media\/([^/]+)$/.exec(name);
                if (!m || dv.getUint32(lh, true) !== 0x04034b50) continue;
                var fname = m[1], fext = (fname.split('.').pop() || '').toLowerCase();
                if (['mp4', 'webm', 'mp3', 'wav'].indexOf(fext) >= 0) continue;
                var start = lh + 30 + dv.getUint16(lh + 26, true) + dv.getUint16(lh + 28, true);
                jobs.push((function (fname, fext, method, raw) {
                    var data = method === 8
                        ? new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw')))
                            .arrayBuffer().then(function (b) { return new Uint8Array(b); })
                        : Promise.resolve(method === 0 ? raw : null);
                    return data.catch(function () { return null; }).then(function (d) {
                        if (!d) return;
                        var bin = '';
                        for (var j = 0; j < d.length; j += 0x8000) bin += String.fromCharCode.apply(null, d.subarray(j, j + 0x8000));
                        var url = 'data:' + (MIME[fext] || 'application/octet-stream') + ';base64,' + btoa(bin);
                        exact[fname] = url;
                        var b = fname.lastIndexOf('.') > 0 ? fname.slice(0, fname.lastIndexOf('.')) : fname;
                        (base[b] = base[b] || {})[fext] = base[b][fext] || url;
                    });
                })(fname, fext, method, bytes.subarray(start, start + cSize)));
            }
            return Promise.all(jobs).then(function () { return { exact: exact, base: base }; });
        } catch (err) {
            return Promise.resolve({ exact: exact, base: base });
        }
    }

    // ---- open ----
    var x2t = loadX2t();
    var file = fetch('file').then(function (r) {
        if (!r.ok) throw new Error('file missing (' + r.status + ')');
        return r.arrayBuffer();
    }).then(function (b) { return new Uint8Array(b); });

    // the editor app loads at the same time as the converter; mobile-shim holds its document open
    // until __ooDocPending is cleared below
    window.__ooDocPending = true;
    Promise.all([x2t, file]).then(function (res) {
        var mod = res[0], bytes = res[1];
        window.__ooPendingDocBin = x2tConvert(mod, bytes, ext, 'bin');
        mark('converted');
        window.__ooDocExt = ext;
        window.__ooMediaMap = {};
        window.__ooMediaMapByBase = {};
        return extractOoxmlMedia(bytes).then(function (media) {
            window.__ooMediaMap = media.exact;
            window.__ooMediaMapByBase = media.base;
            window.__ooDocPending = false;
        });
    }).catch(fail);

    try {
        new DocsAPI.DocEditor('editor', {
            type: 'mobile',
            width: '100%',
            height: '100%',
            documentType: documentType(ext),
            document: {
                fileType: ext,
                key: 'stash-' + Date.now(),
                title: info.name,
                url: '_offline_',
                permissions: { edit: true, download: false, print: false, copy: true, review: false, comment: false }
            },
            editorConfig: {
                mode: 'edit',
                lang: 'en',
                canCoAuthoring: false,
                user: { id: 'stash', name: 'Me' },
                customization: { about: false, feedback: false, help: false, goback: false, close: false,
                                 autosave: false, compactHeader: true, uiTheme: 'theme-light', zoom: -2,
                                 // real page layout (not ONLYOFFICE's reflowed "Mobile View"), fitted to the width
                                 mobile: { forceView: false, standardView: true } }
            },
            events: {
                onDocumentReady: function () { mark('editor ready'); watchState(); B.onReady(); },
                onAppReady: function () { mark('editor app loaded'); },
                onDocumentStateChange: function (e) { dirty = !!(e && e.data); B.onDirty(dirty); },
                onError: function (e) { B.log('editor error ' + JSON.stringify(e && e.data)); },
                onRequestSave: function () { B.onSaveRequested(); }
            }
        });
    } catch (e) { fail(e); }

    // ---- save (called by OfficeEditorActivity) ----
    // kind: 'auto' (draft on the phone, keep editing) or 'final' (Done: hand over and close)
    window.stashSave = function (kind) {
        kind = (kind === 'auto' || kind === 'upload') ? kind : 'final';
        x2t.then(function (mod) {
            var ed = api();
            if (isCell && ed && ed.asc_getCellEditMode()) ed.asc_closeCellEditor(false);
            var frame = document.querySelector('iframe[name="frameEditor"]');
            var bin = frame && frame.contentWindow.__ooGetFileData && frame.contentWindow.__ooGetFileData();
            if (!bin || !bin.length) throw new Error('no document data');
            var out = x2tConvert(mod, bin, 'bin', ext);
            B.saveStart(kind);
            var CHUNK = 3 * 256 * 1024;   // multiple of 3: chunks base64-encode independently
            for (var i = 0; i < out.length; i += CHUNK) {
                var part = out.subarray(i, i + CHUNK), s = '';
                for (var j = 0; j < part.length; j += 0x8000) s += String.fromCharCode.apply(null, part.subarray(j, j + 0x8000));
                B.saveChunk(btoa(s));
            }
            B.saveEnd(kind);
        }).catch(function (e) { B.onSaveFailed(kind, String(e && e.message || e)); });
    };
    // ---- formatting commands from Stash's own toolbar, and state back to it ----
    var isCell = documentType(ext) === 'cell';
    var state = { bold: false, italic: false, underline: false, align: 'left', undo: false, redo: false, cellEdit: false, modified: false };
    var ALIGN_NUM = { left: 1, center: 2, right: 0, justify: 3 };
    var ALIGN_NAME = { 1: 'left', 2: 'center', 0: 'right', 3: 'justify' };

    function api() {
        var f = document.querySelector('iframe[name="frameEditor"]');
        return f && f.contentWindow && f.contentWindow.Asc && f.contentWindow.Asc.editor;
    }
    var pushTimer = 0;
    function push() {
        clearTimeout(pushTimer);
        pushTimer = setTimeout(function () { B.onState(JSON.stringify(state)); }, 30);
    }
    function on(a, ev, fn) {
        a.asc_registerCallback(ev, function () {
            try { fn.apply(null, arguments); push(); } catch (e) { B.log(ev + ': ' + e.message); }
        });
    }
    var watchTries = 0;
    function watchState() {
        var a = api();
        // the engine object may appear a little after onDocumentReady (seen with the spreadsheet editor)
        if (!a || !a.asc_registerCallback) {
            if (++watchTries < 100) setTimeout(watchState, 100); else B.log('state: no editor api');
            return;
        }
        B.log('state: watching (' + (isCell ? 'cell' : 'word') + ')');
        // Word uses asc_onCanUndo/Redo, the spreadsheet engine asc_onCanUndoChanged/RedoChanged
        on(a, 'asc_onCanUndo', function (v) { state.undo = !!v; });
        on(a, 'asc_onCanRedo', function (v) { state.redo = !!v; });
        on(a, 'asc_onCanUndoChanged', function (v) { state.undo = !!v; });
        on(a, 'asc_onCanRedoChanged', function (v) { state.redo = !!v; });
        on(a, 'asc_onDocumentModifiedChanged', function (v) { if (v) state.modified = true; });
        if (isCell) {
            // typing into a cell happens in the cell editor; it reaches the sheet (and undo) on commit
            on(a, 'asc_onEditCell', function () {
                setTimeout(function () { state.cellEdit = !!a.asc_getCellEditMode(); push(); }, 0);
            });
            on(a, 'asc_onSelectionChanged', function (info) {
                var x = info && info.asc_getXfs && info.asc_getXfs();
                if (!x) return;
                state.bold = !!x.asc_getFontBold();
                state.italic = !!x.asc_getFontItalic();
                state.underline = !!x.asc_getFontUnderline();
                var h = x.asc_getHorAlign && x.asc_getHorAlign();
                state.align = ALIGN_NAME[h] || 'left';
            });
        } else {
            on(a, 'asc_onBold', function (v) { state.bold = !!v; });
            on(a, 'asc_onItalic', function (v) { state.italic = !!v; });
            on(a, 'asc_onUnderline', function (v) { state.underline = !!v; });
            on(a, 'asc_onPrAlign', function (v) { state.align = ALIGN_NAME[v] || 'left'; });
        }
        push();
    }

    // Asked by Stash before closing: are there changes, or is a cell being typed into?
    window.stashCheck = function () {
        var a = api(), r = { modified: false, cellEdit: false, undo: false };
        try {
            if (a) {
                if (isCell) {
                    r.cellEdit = !!a.asc_getCellEditMode();
                    r.modified = !!(a.asc_isDocumentModified && a.asc_isDocumentModified());
                } else {
                    r.modified = !!(a.isDocumentModified && a.isDocumentModified());
                }
                r.undo = !!(a.asc_getCanUndo ? a.asc_getCanUndo() : (a.can_Undo ? a.can_Undo() : false));
            }
        } catch (e) { B.log('check: ' + e.message); }
        return JSON.stringify(r);
    };

    // name: undo | redo | bold | italic | underline | align (arg left|center|right|justify)
    //       | color / highlight (arg #rrggbb, highlight also "none")
    window.stashCmd = function (name, arg) {
        var a = api();
        if (!a) return;
        try {
            switch (name) {
                case 'commitCell': if (isCell && a.asc_getCellEditMode()) a.asc_closeCellEditor(false); state.cellEdit = false; break;
                case 'undo': isCell ? a.asc_Undo() : a.Undo(); break;
                case 'redo': isCell ? a.asc_Redo() : a.Redo(); break;
                case 'bold': state.bold = !state.bold; isCell ? a.asc_setCellBold(state.bold) : a.put_TextPrBold(state.bold); break;
                case 'italic': state.italic = !state.italic; isCell ? a.asc_setCellItalic(state.italic) : a.put_TextPrItalic(state.italic); break;
                case 'underline': state.underline = !state.underline; isCell ? a.asc_setCellUnderline(state.underline) : a.put_TextPrUnderline(state.underline); break;
                case 'align': state.align = arg; isCell ? a.asc_setCellAlign(ALIGN_NUM[arg]) : a.put_PrAlign(ALIGN_NUM[arg]); break;
                case 'color': {
                    var c = rgb(arg);
                    isCell ? a.asc_setCellTextColor(c) : a.put_TextColor(c);
                    break;
                }
                case 'highlight': {
                    var w = document.querySelector('iframe[name="frameEditor"]').contentWindow;
                    if (isCell) {
                        a.asc_setCellBackgroundColor(arg === 'none' ? null : rgb(arg));
                    } else if (arg === 'none') {
                        a.SetMarkerFormat(true, false);
                    } else {
                        var h = parseInt(arg.slice(1), 16);
                        a.SetMarkerFormat(true, true, (h >> 16) & 255, (h >> 8) & 255, h & 255);
                    }
                    break;
                }
            }
        } catch (e) {
            B.log('cmd ' + name + ': ' + e.message);
        }
        push();
        function rgb(hex) {
            var h = parseInt(hex.slice(1), 16);
            var Asc = document.querySelector('iframe[name="frameEditor"]').contentWindow.Asc;
            return new Asc.asc_CColor((h >> 16) & 255, (h >> 8) & 255, h & 255);
        }
    };
})();
