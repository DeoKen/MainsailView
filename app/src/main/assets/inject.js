// Injected into every Mainsail page at document start (see WebViewHolder).
// 1. Tracks WebRTC peer connections so webcams can be paused while the app is in the
//    background. MJPEG streams are already stopped by Mainsail itself on visibilitychange.
// 2. Routes <a download href="blob:/data:"> saves (history CSV, settings backup, ...)
//    to the native "Save as" picker, since WebView can't save those on its own.
(function () {
    if (window.__mainsailView) return;
    var mv = (window.__mainsailView = {});
    var live = [];
    var suspended = [];
    var isSuspended = false;

    var NativePC = window.RTCPeerConnection;
    if (NativePC) {
        var Wrapped = function (config, constraints) {
            var pc = new NativePC(config, constraints);
            if (isSuspended) {
                // Created while hidden (e.g. a pending restart timer fired): let it start,
                // then shut it down and revive it with the others on resume.
                setTimeout(function () {
                    if (isSuspended) suspend(pc);
                    else live.push(pc);
                }, 1000);
            } else {
                live.push(pc);
            }
            return pc;
        };
        Wrapped.prototype = NativePC.prototype;
        Object.setPrototypeOf(Wrapped, NativePC);
        window.RTCPeerConnection = Wrapped;
    }

    function suspend(pc) {
        if (pc.signalingState === 'closed') return;
        try {
            pc.close();
        } catch (e) {}
        suspended.push(pc);
    }

    mv.suspendCams = function () {
        isSuspended = true;
        var list = live;
        live = [];
        list.forEach(suspend);
    };

    // Mainsail's WebRTC streamers (camera-streamer, go2rtc, MediaMTX) restart themselves
    // when their connection reports "disconnected", so fake exactly that.
    mv.resumeCams = function () {
        isSuspended = false;
        var list = suspended;
        suspended = [];
        list.forEach(function (pc) {
            try {
                Object.defineProperty(pc, 'connectionState', { configurable: true, get: function () { return 'disconnected'; } });
                Object.defineProperty(pc, 'iceConnectionState', { configurable: true, get: function () { return 'disconnected'; } });
                pc.dispatchEvent(new Event('iceconnectionstatechange'));
                pc.dispatchEvent(new Event('connectionstatechange'));
            } catch (e) {}
        });
    };

    // UI scale: Mainsail picks its layout (mobile/tablet/desktop/widescreen) from the page
    // width, so give it a page of (screen width / scale) and shrink that to fit the screen.
    mv.applyScale = function () {
        var bridge = window.MainsailViewBridge;
        if (!bridge) return;
        var scale = Math.round(bridge.uiScale() * 100) / 100;
        var width = bridge.viewWidthDp();
        var content = 'width=device-width,initial-scale=1.0';
        if (scale > 0 && width > 0 && scale !== 1) {
            content = 'width=' + Math.round(width / scale) + ',initial-scale=' + scale +
                ',minimum-scale=' + scale + ',maximum-scale=' + scale;
        }
        var metas = document.querySelectorAll('meta[name="viewport"]');
        var meta = metas[0];
        if (!meta) {
            if (!document.head) return;
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.head.appendChild(meta);
        }
        for (var i = 1; i < metas.length; i++) metas[i].remove();
        if (meta.content !== content) meta.content = content;
    };

    // Apply as soon as the page's own viewport tag is parsed, before Mainsail lays out.
    var viewportWatcher = new MutationObserver(function () {
        if (document.querySelector('meta[name="viewport"]')) {
            viewportWatcher.disconnect();
            mv.applyScale();
        }
    });
    viewportWatcher.observe(document, { childList: true, subtree: true });
    document.addEventListener('DOMContentLoaded', function () {
        viewportWatcher.disconnect();
        mv.applyScale();
    });

    mv.saveUrl = function (href, fileName) {
        fetch(href)
            .then(function (r) { return r.blob(); })
            .then(function (blob) {
                var reader = new FileReader();
                reader.onload = function () {
                    var s = reader.result;
                    window.MainsailViewBridge.saveBase64(s.substring(s.indexOf(',') + 1), blob.type || '', fileName || '');
                };
                reader.readAsDataURL(blob);
            })
            .catch(function (e) { console.error('MainsailView save failed', e); });
    };

    function intercept(a) {
        if (!window.MainsailViewBridge || !a || !a.hasAttribute || !a.hasAttribute('download')) return false;
        var href = a.href || '';
        if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return false;
        mv.saveUrl(href, a.getAttribute('download') || 'download');
        return true;
    }

    // Programmatic link.click(), including on links never added to the page.
    var nativeClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        if (intercept(this)) return;
        return nativeClick.apply(this, arguments);
    };

    // Real taps on download links.
    document.addEventListener('click', function (e) {
        var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
        if (a && intercept(a)) {
            e.preventDefault();
            e.stopPropagation();
        }
    }, true);
})();
