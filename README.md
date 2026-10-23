# MainsailView

A minimal Android app that wraps [Mainsail](https://docs.mainsail.xyz/) for two printers
in a WebView that stays connected while you're in other apps.

- Any number of printers, each with a name and address. Add, edit, reorder and delete them in
  Settings ⚙ (long-press a printer tab to get there). They show as tabs; one is loaded at a time.
- **UI scale** (separate for a foldable's main and cover screens, told apart by whether the window spans the fold) changes the page width Mainsail sees, so you can choose its mobile, tablet, desktop
  or widescreen layout (and the dashboard you set up for it). It is a percentage of whichever screen
  is in use, so a foldable's cover and main screens each adapt; Settings shows the resulting layout.
- Back / forward / reload buttons, and pinch-to-zoom. The toolbar can sit at the top or bottom.
- A foreground service (with its notification) keeps the app alive in the background, and
  Mainsail's printer connection is held by the app itself (not the page), so it keeps
  answering the printer even while the phone has the page frozen.
- Webcams pause in the background: Mainsail stops MJPEG itself, and the app closes WebRTC
  streams and makes Mainsail reconnect them when you come back.
- After a configurable time in the background (default 10 min, 0 = never) the app closes
  itself. The notification's **Close** button, or swiping it out of recents, closes it right away.
- Uploads use the system file picker (any file type, so .gcode / .cfg / .conf all show).
- Downloads ask where to save each file.
- If Mainsail lost its printer connection (e.g. phone folded and locked), the app does what
  Mainsail's "Try again" button does, on return and every 10 s after, until it reconnects;
  no page reload. Failed page loads are retried.
- Fullscreen works like Chrome: the webcam video's fullscreen button fills the screen (Back
  exits), and going to the home screen from there shrinks it into a picture-in-picture
  window. Closing that window keeps the app running in the background.
- Optional keep-screen-on while visible. Follows the system light/dark theme.

## Getting the APK

Builds run only when started by hand: open the **Actions** tab, choose **Build APK**, press
**Run workflow**, then open the finished run and download the `MainsailView-debug-apk` artifact (a zip containing the APK).

The APK is signed with the committed `app/debug.keystore`, so new builds install over
old ones without losing settings. That key is public, so don't reuse it for anything else.

## Requirements

Android 8.0 (API 26) or newer. Plain `http://` addresses are allowed.
