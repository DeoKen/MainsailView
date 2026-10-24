// Injected into every Mainsail page at document start (see WebViewHolder). It:
// - pauses webcams while the app is in the background and restarts them on return,
// - lets the app see whether Mainsail lost its Moonraker connection and reconnect it,
// - reports fullscreen elements (for picture-in-picture),
// - drops a text box's focus on taps elsewhere (stops the keyboard popping back up),
// - routes <a download href="blob:/data:"> saves to the native "Save as" picker.
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

    // Watch the page's own Moonraker websocket (without changing it) so the app can tell,
    // when it comes back to the foreground, whether Mainsail lost its connection. Only the
    // first Moonraker URL the page opens counts, and its reconnects to that same URL:
    // Mainsail's printer farm opens /websocket connections to other printers too, and one
    // of those being offline must not count as this printer's connection being lost.
    var NativeWS = window.WebSocket;
    var mainSocketUrl = null;
    var mainSocket = null;
    var lastMessageAt = 0;
    // This script also runs in embedded frames (e.g. a webcam player); only the page
    // itself logs, relays and watches the printer connection.
    var isTop = window.top === window;

    function log(message) {
        if (!isTop) return;
        try {
            if (window.MainsailViewBridge) window.MainsailViewBridge.log(message);
        } catch (e) {}
    }

    // RelaySocket: a WebSocket look-alike whose connection is held by the app's own process
    // (RelaySockets.kt), which keeps answering the printer while the phone has this page
    // frozen and hands over everything that arrived once the page runs again.
    var relayBridge = isTop ? window.MainsailViewSocket : null;
    var relays = {};
    var relayCounter = 0;
    function RelaySocket(url) {
        this.url = new URL(url, location.href).href;
        this.readyState = 0;
        this.protocol = '';
        this.extensions = '';
        this.binaryType = 'blob';
        this.bufferedAmount = 0;
        this.onopen = this.onmessage = this.onclose = this.onerror = null;
        this._listeners = {};
        this._id = ++relayCounter;
        relays[this._id] = this;
        relayBridge.postMessage(JSON.stringify({ t: 'open', id: this._id, url: this.url }));
    }
    ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (name, i) {
        RelaySocket[name] = i;
        RelaySocket.prototype[name] = i;
    });
    RelaySocket.prototype.send = function (data) {
        if (this.readyState === 0) throw new DOMException('Still in CONNECTING state.', 'InvalidStateError');
        if (this.readyState !== 1) return;
        relayBridge.postMessage(JSON.stringify({ t: 'send', id: this._id, data: String(data) }));
    };
    RelaySocket.prototype.close = function (code, reason) {
        if (this.readyState >= 2) return;
        this.readyState = 2;
        relayBridge.postMessage(JSON.stringify({ t: 'close', id: this._id, code: code || 1000, reason: reason || '' }));
    };
    RelaySocket.prototype.addEventListener = function (type, fn) {
        (this._listeners[type] = this._listeners[type] || []).push(fn);
    };
    RelaySocket.prototype.removeEventListener = function (type, fn) {
        var list = this._listeners[type] || [];
        var i = list.indexOf(fn);
        if (i >= 0) list.splice(i, 1);
    };
    RelaySocket.prototype.dispatchEvent = function (event) {
        var self = this;
        var handler = self['on' + event.type];
        if (typeof handler === 'function') handler.call(self, event);
        (self._listeners[event.type] || []).slice().forEach(function (fn) {
            if (typeof fn === 'function') fn.call(self, event);
            else if (fn && typeof fn.handleEvent === 'function') fn.handleEvent(event);
        });
        return true;
    };
    if (relayBridge) {
        relayBridge.onmessage = function (e) {
            var m;
            try {
                m = JSON.parse(e.data);
            } catch (err) {
                return;
            }
            var ws = relays[m.id];
            if (!ws) return;
            if (m.t === 'open') {
                ws.readyState = 1;
                ws.dispatchEvent(new Event('open'));
            } else if (m.t === 'msg') {
                if (ws.readyState === 1) ws.dispatchEvent(new MessageEvent('message', { data: m.data }));
            } else if (m.t === 'close') {
                delete relays[m.id];
                var wasOpen = ws.readyState === 1 || ws.readyState === 2;
                ws.readyState = 3;
                if (!m.clean && !wasOpen) ws.dispatchEvent(new Event('error'));
                ws.dispatchEvent(new CloseEvent('close', { code: m.code, reason: m.reason, wasClean: !!m.clean }));
            }
        };
    }
    function ago(t) {
        return t ? Math.round((Date.now() - t) / 1000) + ' s ago' : 'never';
    }

    // Listens to (never alters) Mainsail's printer connection, for the log and recovery.
    function watchMainSocket(ws) {
        var openedAt = 0;
        ws.addEventListener('open', function () {
            openedAt = lastMessageAt = Date.now();
            log('Connected to the printer');
        });
        ws.addEventListener('message', function () {
            lastMessageAt = Date.now();
        });
        ws.addEventListener('close', function (e) {
            log('Printer connection closed (code ' + e.code + (e.reason ? ', ' + e.reason : '') +
                (e.wasClean ? ', clean' : '') + '), open for ' +
                (openedAt ? Math.round((Date.now() - openedAt) / 1000) + ' s' : '0 s') +
                ', last message ' + ago(lastMessageAt));
            if (ws === mainSocket) scheduleRecovery();
        });
        var nativeClose = ws.close;
        ws.close = function () {
            if (ws.readyState === NativeWS.OPEN) log('Mainsail closed its connection itself; last message ' + ago(lastMessageAt));
            return nativeClose.apply(ws, arguments);
        };
        patchHeartbeat();
    }

    if (NativeWS) {
        var WrappedWS = function (url, protocols) {
            var isMoonraker = /\/websocket(\?|$)/.test(new URL(url, location.href).pathname + '?');
            var ws;
            if (isMoonraker && relayBridge && protocols === undefined) ws = new RelaySocket(url);
            else ws = protocols === undefined ? new NativeWS(url) : new NativeWS(url, protocols);
            if (isTop && isMoonraker) {
                if (mainSocketUrl === null) {
                    mainSocketUrl = ws.url;
                    log(ws instanceof RelaySocket ? 'Printer connection is relayed through the app'
                        : 'Printer connection is NOT relayed (WebView too old?)');
                }
                if (ws.url === mainSocketUrl) {
                    mainSocket = ws;
                    watchMainSocket(ws);
                }
            }
            return ws;
        };
        WrappedWS.prototype = NativeWS.prototype;
        Object.setPrototypeOf(WrappedWS, NativeWS); // keeps WebSocket.OPEN etc.
        window.WebSocket = WrappedWS;
    }

    // True when Mainsail's latest connection to its printer is closed (it gave up).
    mv.connectionLost = function () {
        if (!mainSocket) return false;
        return mainSocket.readyState === NativeWS.CLOSING || mainSocket.readyState === NativeWS.CLOSED;
    };

    function mainsailVm() {
        var root = document.querySelector('[data-app]') || document.getElementById('app');
        var vm = root && root.__vue__;
        return vm && vm.$socket ? vm : null;
    }

    // Does what Mainsail's own "Try again" button does (TheConnectingDialog.reconnect):
    // reconnects and reloads the printer data without reloading the page. Returns false
    // if this Mainsail doesn't look as expected, so the app can reload the page instead.
    mv.reconnect = function () {
        var vm = mainsailVm();
        if (!vm || typeof vm.$socket.connect !== 'function') return false;
        try {
            if (vm.$store) vm.$store.dispatch('socket/setData', { connectingFailed: false });
            vm.$socket.connect();
            return true;
        } catch (e) {
            return false;
        }
    };

    // After the connection closes, give Mainsail's own quick retries (about 5 s) a chance,
    // then press its Try again if it's still down. Keeps going while the page is alive.
    var recoveryTimer = null;
    function scheduleRecovery() {
        if (recoveryTimer) return;
        log('Will press Try again in 6 s if still disconnected');
        recoveryTimer = setTimeout(function () {
            recoveryTimer = null;
            if (!mv.connectionLost()) {
                log('Reconnected by itself');
                return;
            }
            log('Still disconnected: pressing Try again');
            if (!mv.reconnect()) log('Try again is not available in this Mainsail');
        }, 6000);
    }

    // Mainsail drops the connection when no message arrives for 10 s (its heartbeat). If the
    // page was paused in the background, that timer can fire on resume before the messages
    // that arrived meanwhile are handled, wrongly killing a healthy connection. Same check,
    // but it looks again 3 s later before giving up. Only patched if Mainsail looks as expected.
    var heartbeatPatched = false;
    var heartbeatTries = 0;
    function patchHeartbeat() {
        if (heartbeatPatched) return;
        var vm = mainsailVm();
        if (!vm) {
            if (heartbeatTries++ < 60) setTimeout(patchHeartbeat, 1000);
            return;
        }
        var client = vm.$socket;
        if (typeof client.heartbeat !== 'function' || !('heartbeatTimer' in client)) {
            log('Mainsail heartbeat not found; left as is');
            return;
        }
        heartbeatPatched = true;
        var confirming = false;
        client.heartbeat = function () {
            var self = this;
            if (confirming) log('Kept the connection: messages were still arriving after a pause');
            confirming = false;
            if (self.heartbeatTimer) clearTimeout(self.heartbeatTimer);
            self.heartbeatTimer = window.setTimeout(function () {
                confirming = true;
                self.heartbeatTimer = window.setTimeout(function () {
                    confirming = false;
                    if (!self.instance || self.instance.readyState !== NativeWS.OPEN || !self.store) return;
                    log('No message from the printer for 13 s: disconnecting (Mainsail heartbeat)');
                    self.close();
                    self.store.dispatch('socket/onClose');
                }, 3000);
            }, 10000);
        };
    }

    // Notes when the page itself was paused (frozen or heavily throttled by the phone).
    var lastTick = Date.now();
    if (isTop) setInterval(function () {
        var now = Date.now();
        if (now - lastTick > 15000) {
            log('Page was paused for ' + Math.round((now - lastTick) / 1000) + ' s' + (forcedHidden ? ' (app in background)' : ''));
        }
        lastTick = now;
    }, 2000);

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
    // width, so the app asks for a page of (screen width / scale) CSS px and zooms out to
    // fit it. Pinch-zoom then works on top, as on any page. width 0 = Mainsail's own.
    mv.applyScale = function (width) {
        if (!isTop) return;
        var content = width > 0 ? 'width=' + width : 'width=device-width,initial-scale=1.0';
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

    // Apply as soon as the page's own viewport tag is parsed, before Mainsail lays out; the
    // app applies it again once the page has loaded and whenever the width or scale changes.
    if (isTop) {
        var viewportWatcher = new MutationObserver(function () {
            if (!document.querySelector('meta[name="viewport"]')) return;
            viewportWatcher.disconnect();
            try {
                mv.applyScale(window.MainsailViewBridge ? window.MainsailViewBridge.pageWidth() : 0);
            } catch (e) {}
        });
        viewportWatcher.observe(document, { childList: true, subtree: true });
    }

    // Mainsail's temperature table: keep the heater power ("48 %") on one line. On narrow
    // layouts it wraps to "48 / %" whenever the value changes width, making the rows jump.
    if (isTop) {
        var layoutFix = document.createElement('style');
        layoutFix.textContent = '.temperature-panel-table td.state { white-space: nowrap; }';
        (document.head || document.documentElement).appendChild(layoutFix);
    }

    // Fullscreen (e.g. the webcam video's own fullscreen button): tell the app the shape of
    // what went fullscreen, so its picture-in-picture window matches.
    function reportFullscreen() {
        var bridge = window.MainsailViewBridge;
        var el = document.fullscreenElement || document.webkitFullscreenElement;
        if (!bridge || !el) return;
        var m = /^(VIDEO|CANVAS|IMG|IFRAME)$/.test(el.tagName) ? el : el.querySelector('video, canvas, img, iframe');
        var w = 0, h = 0;
        if (m) {
            w = m.videoWidth || m.naturalWidth || 0;
            h = m.videoHeight || m.naturalHeight || 0;
        }
        if (!(w > 0 && h > 0)) {
            w = 16;
            h = 9;
        }
        bridge.fullscreenState(true, w, h);
    }
    document.addEventListener('fullscreenchange', reportFullscreen);
    document.addEventListener('webkitfullscreenchange', reportFullscreen);

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
    function blurOnOutsideTap(e) {
        var focused = document.activeElement;
        var target = e.target;
        if (!isEditable(focused) || isEditable(target)) return;
        // Taps on the field's own icons (e.g. Send) or its dropdown keep the focus.
        var field = focused.closest ? focused.closest('.v-input') : null;
        if (field && field.contains(target)) return;
        if (target && target.closest && target.closest('.v-menu__content')) return;
        focused.blur();
    }
    // All three: taps on e.g. a video's built-in controls don't always produce each one.
    ['touchstart', 'pointerdown', 'mousedown'].forEach(function (type) {
        document.addEventListener(type, blurOnOutsideTap, { capture: true, passive: true });
    });

    // Called by the app when the keyboard closes, so the next tap can't reopen it.
    mv.blurTextField = function () {
        if (isEditable(document.activeElement)) document.activeElement.blur();
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
