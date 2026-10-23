# MainsailView

A minimal Android app that wraps [Mainsail](https://docs.mainsail.xyz/) for two printers
in a WebView that stays connected while you're in other apps.

- Two printers, each with a user-set name and address (Settings ⚙, or long-press a printer tab).
  One printer is loaded at a time; switching loads the other.
- Back / forward / reload buttons. The toolbar can sit at the top or bottom.
- A foreground service (with its notification) keeps the websocket alive in the background.
- Webcams pause in the background: Mainsail stops MJPEG itself, and the app closes WebRTC
  streams and makes Mainsail reconnect them when you come back.
- After a configurable time in the background (default 10 min, 0 = never) the app closes
  itself. The notification's **Close** button, or swiping it out of recents, closes it right away.
- Uploads use the system file picker (any file type, so .gcode / .cfg / .conf all show).
- Downloads ask where to save each file.
- Optional keep-screen-on while visible. Follows the system light/dark theme.

## Getting the APK

Every push builds a debug APK in GitHub Actions: open the **Actions** tab, pick the latest
**Build APK** run and download the `MainsailView-debug-apk` artifact (a zip containing the APK).

The APK is signed with the committed `app/debug.keystore`, so new builds install over
old ones without losing settings. That key is public, so don't reuse it for anything else.

## Requirements

Android 8.0 (API 26) or newer. Plain `http://` addresses are allowed.
