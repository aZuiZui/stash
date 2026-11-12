// stash: offline glue for the ONLYOFFICE *mobile* editors, loaded after office-web's offline-shim.js
// (bundle.py adds both to apps/*/mobile/index.html).
//
// Offline nothing connects to a collaboration server. The desktop editors still reach
// CoAuthoringApi.auth(), whose offline "dummy calls" end in asyncServerIdEndLoaded(); the mobile app
// never calls it, so ServerIdWaitComplete stays false, _openDocumentEndCallback() waits forever and
// the page stays blank. Signal "connected" once the document file has been handed to the engine.
(function () {
    var timer = setInterval(function () {
        var AC = window.AscCommon;
        if (!AC || !AC.baseEditorsApi) return;
        clearInterval(timer);
        var proto = AC.baseEditorsApi.prototype;
        // The offline licence (office-web's offline-shim) carries no build version; the mobile app
        // compares it with its own (9.4.0, the pinned ONLYOFFICE build) and otherwise shows
        // "Editor updated, the page will be reloaded" on every open. Report the bundled version.
        var perms = AC.asc_CAscEditorPermissions && AC.asc_CAscEditorPermissions.prototype;
        if (perms && perms.asc_getBuildVersion) {
            var getBuild = perms.asc_getBuildVersion;
            perms.asc_getBuildVersion = function () { return getBuild.call(this) || '9.4.0'; };
        }
        // there is no document server to upgrade: a reconnect without a server version would show
        // "Editor updated, the page will be reloaded" and throw away the session; never send it
        var sendEvent = proto.sendEvent;
        proto.sendEvent = function (name) {
            if (name === 'asc_onServerVersion') return false;
            return sendEvent.apply(this, arguments);
        };
        // editor.js starts the editor while the converter still runs (both take seconds): hold the open
        // until the converted document is there, instead of opening an empty one
        var openEmpty = proto._openEmptyDocument;
        proto._openEmptyDocument = function () {
            var api = this, args = arguments;
            (function wait() {
                var p = window.parent;
                if (p && p !== window && p.__ooDocPending) return setTimeout(wait, 20);
                openEmpty.apply(api, args);
            })();
        };
        var onEndLoadFile = proto.onEndLoadFile;
        proto.onEndLoadFile = function () {
            var result = onEndLoadFile.apply(this, arguments);
            var api = this;
            setTimeout(function () {
                if (!api.ServerIdWaitComplete && typeof api.asyncServerIdEndLoaded === 'function') {
                    api.asyncServerIdEndLoaded();
                }
            }, 0);
            return result;
        };
    }, 5);
})();
