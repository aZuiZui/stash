// stash: glue between the PDF.js viewer and io.github.azuizui.stash.OfficeEditorActivity (window.StashBridge).
// Same contract as the office editor's editor.js: B.onReady / B.onState / B.onError, and
// window.stashCmd, window.stashCheck, window.stashSave(kind) for the app to call.
(function () {
    'use strict';
    var B = window.StashBridge;
    if (!B) return;
    var t0 = performance.now();
    function log(m) { try { B.log(m); } catch (e) {} }
    window.addEventListener('error', function (e) { log('error ' + e.message + ' @' + e.filename + ':' + e.lineno); });

    // PDF.js editor types (AnnotationEditorType in pdf.mjs)
    var TOOLS = { none: 0, text: 3, highlight: 9, image: 13, draw: 15, sign: 101 };
    var NAMES = {}; Object.keys(TOOLS).forEach(function (k) { NAMES[TOOLS[k]] = k; });
    var state = { undo: false, redo: false, modified: false, tool: 'none' };
    var pushTimer = 0;
    function push() {
        clearTimeout(pushTimer);
        pushTimer = setTimeout(function () { B.onState(JSON.stringify(state)); }, 30);
    }
    function app() { return window.PDFViewerApplication; }

    document.addEventListener('webviewerloaded', function () {
        var O = window.PDFViewerApplicationOptions;
        O.set('disablePreferences', true);          // nothing from a previous session overrides these
        O.set('localeProperties', { lang: 'en-US' });
        O.set('enableScripting', false);            // no JavaScript from inside PDFs
        O.set('enableSignatureEditor', true);
        O.set('enableComment', false);
        O.set('enableHighlightFloatingButton', true);
        O.set('enableGuessAltText', false);
        O.set('enableNewAltTextWhenAddingImage', false);
        O.set('enableAltText', false);
        O.set('disableHistory', true);
        O.set('sidebarViewOnLoad', 0);
        O.set('viewOnLoad', 1);                     // always start at the top
        O.set('defaultZoomValue', 'page-width');

        var A = app();
        A.initializedPromise.then(function () {
            var bus = A.eventBus;
            bus.on('documentloaded', function () {
                log('open ready +' + Math.round(performance.now() - t0) + 'ms');
                var st = A.pdfDocument.annotationStorage;
                var set = st.onSetModified;
                st.onSetModified = function () {
                    if (set) set.apply(this, arguments);
                    state.modified = true; push();
                };
                B.onReady();
            });
            bus.on('editingstateschanged', function (e) {
                var d = e.details || {};
                if ('hasSomethingToUndo' in d) state.undo = !!d.hasSomethingToUndo;
                if ('hasSomethingToRedo' in d) state.redo = !!d.hasSomethingToRedo;
                if (state.undo) state.modified = true;
                push();
            });
            bus.on('annotationeditormodechanged', function (e) {
                state.tool = NAMES[e.mode] || 'none'; push();
            });
        });
        // a broken or password-protected file: PDF.js shows its own message; tell the app too
        var docErr = A._documentError;
        A._documentError = function (key, more) {
            B.onError(String((more && more.message) || key));
            return docErr.apply(this, arguments);
        };
    });

    function setTool(name) {
        var A = app(), mode = TOOLS[name];
        if (mode === undefined || !A || !A.pdfDocument) return;
        if (state.tool === name) mode = 0;           // tapping the active tool again turns it off
        A.eventBus.dispatch('switchannotationeditormode', { source: null, mode: mode });
        // signature and image need a picture first: open their dialogs straight away
        if (mode === TOOLS.sign) setTimeout(function () { document.getElementById('editorSignatureAddSignature').click(); }, 50);
        if (mode === TOOLS.image) setTimeout(function () { document.getElementById('editorStampAddImage').click(); }, 50);
    }

    window.stashCmd = function (name, arg) {
        var A = app();
        if (!A || !A.pdfDocument) return;
        if (name === 'undo' || name === 'redo') A.eventBus.dispatch('editingaction', { source: null, name: name });
        else if (name === 'tool') setTool(arg);
    };

    window.stashCheck = function () {
        var A = app();
        return JSON.stringify({ modified: !!(A && (A._annotationStorageModified || state.modified)), undo: state.undo });
    };

    function commitTyping() {
        // a form field or text box still being typed into writes its value when it loses focus
        var el = document.activeElement;
        if (el && el !== document.body && el.blur) el.blur();
        return new Promise(function (r) { setTimeout(r, 60); });
    }

    // kind: 'auto' (draft on the phone), 'upload' (leaving the app) or 'final' (Done)
    window.stashSave = function (kind) {
        kind = (kind === 'auto' || kind === 'upload') ? kind : 'final';
        var A = app();
        commitTyping().then(function () {
            return A.pdfDocument.saveDocument().catch(function (e) {
                // an unfinished tool (e.g. a signature not yet added) can't be saved: leave the tool and retry
                if (kind === 'auto') throw e;   // don't pull the user out of a tool for a background save
                log('save retry after: ' + (e && e.message));
                A.eventBus.dispatch('switchannotationeditormode', { source: null, mode: 0 });
                return new Promise(function (r) { setTimeout(r, 150); }).then(function () { return A.pdfDocument.saveDocument(); });
            });
        }).then(function (data) {
            B.saveStart(kind);
            var CH = 786432;
            for (var i = 0; i < data.length; i += CH) {
                var part = data.subarray(i, Math.min(i + CH, data.length)), s = '';
                for (var j = 0; j < part.length; j += 32768) s += String.fromCharCode.apply(null, part.subarray(j, j + 32768));
                B.saveChunk(btoa(s));
            }
            B.saveEnd(kind);
        }).catch(function (e) { B.onSaveFailed(kind, String(e && e.message || e)); });
    };
})();
