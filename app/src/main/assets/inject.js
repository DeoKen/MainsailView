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

    // MJPEG streams: Mainsail starts/stops them on visibilitychange. Report the page as hidden
    // ourselves while in the background, so they stop even if WebView doesn't say so, and
    // report a fresh hidden -> visible on return so they always restart instead of freezing.
    var forcedHidden = false;
    function setForcedHidden(on) {
        if (on) {
            Object.defineProperty(document, 'visibilityState', { configurable: true, get: function () { return 'hidden'; } });
            Object.defineProperty(document, 'hidden', { configurable: true, get: function () { return true; } });
        } else {
            delete document.visibilityState;
            delete document.hidden;
        }
        forcedHidden = on;
    }
    function fireVisibilityChange() {
        document.dispatchEvent(new Event('visibilitychange'));
    }

    mv.suspendCams = function () {
        isSuspended = true;
        var list = live;
        live = [];
        list.forEach(suspend);
        if (!forcedHidden) {
            setForcedHidden(true);
            fireVisibilityChange();
        }
    };

    // Mainsail's WebRTC streamers (camera-streamer, go2rtc, MediaMTX) restart themselves
    // when their connection reports "disconnected", so fake exactly that.
    mv.resumeCams = function () {
        if (!forcedHidden) {
            setForcedHidden(true);
            fireVisibilityChange();
        }
        // Back to the real state: "visible" restarts the streams now; if WebView still says
        // "hidden", its own visibilitychange will start them a moment later.
        setForcedHidden(false);
        fireVisibilityChange();

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

    // Picture-in-picture: the app offers PiP while Mainsail's Webcam page (/cam) is open,
    // and in PiP shows just the first camera, filling the window.
    function firstCam() {
        var panel = document.querySelector('.webcam-panel');
        if (!panel) return null;
        var media = panel.querySelector('canvas, video, img, iframe');
        if (!media) return null;
        return { media: media, box: media.closest('.webcamBackground') || media };
    }

    var lastCamState = '';
    function reportCamState() {
        var bridge = window.MainsailViewBridge;
        if (!bridge) return;
        var onCamPage = /\/cam\/?$/.test(location.pathname);
        var w = 16, h = 9;
        var cam = onCamPage ? firstCam() : null;
        if (cam) {
            var r = cam.media.getBoundingClientRect();
            if (r.width > 0 && r.height > 0) {
                w = Math.round(r.width);
                h = Math.round(r.height);
            }
        }
        var state = onCamPage + ':' + w + 'x' + h;
        if (state === lastCamState) return;
        lastCamState = state;
        bridge.camState(onCamPage, w, h);
    }
    setInterval(reportCamState, 1000);

    var pipStyle = null;
    mv.setPip = function (on) {
        document.documentElement.classList.toggle('mv-pip', on);
        document.querySelectorAll('.mv-pip-target').forEach(function (el) { el.classList.remove('mv-pip-target'); });
        if (!on) return;
        if (!pipStyle) {
            pipStyle = document.createElement('style');
            pipStyle.textContent =
                'html.mv-pip, html.mv-pip body { overflow: hidden !important; background: #000 !important; }' +
                'html.mv-pip .mv-pip-target { position: fixed !important; top: 0 !important; left: 0 !important;' +
                ' width: 100vw !important; height: 100vh !important; max-width: none !important; margin: 0 !important;' +
                ' padding: 0 !important; z-index: 2147483647 !important; background: #000 !important;' +
                ' display: flex !important; align-items: center !important; justify-content: center !important; }' +
                'html.mv-pip .mv-pip-target canvas, html.mv-pip .mv-pip-target video, html.mv-pip .mv-pip-target img,' +
                ' html.mv-pip .mv-pip-target iframe, html.mv-pip canvas.mv-pip-target, html.mv-pip video.mv-pip-target,' +
                ' html.mv-pip img.mv-pip-target { width: 100% !important; height: 100% !important; object-fit: contain !important; }';
            document.head.appendChild(pipStyle);
        }
        var cam = firstCam();
        if (cam) cam.box.classList.add('mv-pip-target');
    };

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

    // WebView keeps the last text box (e.g. the console) focused when you tap elsewhere, so
    // every tap re-opens the keyboard and scrolls back to it. Drop that focus on taps that
    // land outside it.
    function isEditable(el) {
        if (!el || el.nodeType !== 1) return false;
        if (el.isContentEditable || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') return true;
        if (el.tagName !== 'INPUT') return false;
        var type = (el.type || '').toLowerCase();
        return ['button', 'checkbox', 'radio', 'range', 'color', 'file', 'submit', 'reset', 'image'].indexOf(type) < 0;
    }
    document.addEventListener('pointerdown', function (e) {
        var focused = document.activeElement;
        var target = e.target;
        if (!isEditable(focused) || isEditable(target)) return;
        // Taps on the field's own icons (e.g. Send) or its dropdown keep the focus.
        var field = focused.closest ? focused.closest('.v-input') : null;
        if (field && field.contains(target)) return;
        if (target && target.closest && target.closest('.v-menu__content')) return;
        focused.blur();
    }, true);

    // Real taps on download links.
    document.addEventListener('click', function (e) {
        var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
        if (a && intercept(a)) {
            e.preventDefault();
            e.stopPropagation();
        }
    }, true);
})();
