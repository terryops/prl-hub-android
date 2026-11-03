# Pearl Hub for Android

A self-custody **Pearl ($PRL)** wallet and mining monitor for Android: a native Kotlin shell around the Pearl Hub web interface.

[Google Play](https://play.google.com/store/apps/details?id=com.prl.wizard) · [Telegram channel @prl_hub](https://t.me/prl_hub)

## How it is built

The interface is the [Pearl Hub web frontend](https://github.com/terryops/prl-hub-web), bundled into the APK and shown in a WebView at `https://pearlhub.net/` (served from the APK's assets, not downloaded). The wallet inside it is the code of the [Pearl Hub browser wallet](https://github.com/terryops/prl-hub-wallet). This repository is the shell: it adds what a browser cannot do, following the [iPhone / iPad / Mac app](https://github.com/terryops/prl-hub) feature by feature.

- **Wallet secret behind the screen lock.** The wallet keeps the browser wallet's encrypted vault. On Android its password is a random 32-byte secret, encrypted under an Android Keystore key (StrongBox when present) and released only after BiometricPrompt or the screen lock confirms (`Wallet.kt`). Recovery phrases and private keys never leave the device; transactions are signed on it.
- **Notifications**: price, offline and payment alerts as FCM data messages from the [Pearl Hub server](https://github.com/terryops/prl-hub-server), and 锁屏盯盘, an ongoing notification with the live price, promoted to a Live Update on Android 16 (`Push.kt`, `Notifications.kt`, `LiveTicker.kt`).
- **Widgets**: eight home-screen and cover-screen widgets drawn with `RemoteViews` (`Widget*.kt`).
- **Native requests** where the WebView cannot make them: Pearl's public Blockbook indexer, which sends no CORS headers, the user's own SafeTrade account (signed with keys kept in the Keystore, `SafeTrade.kt`), and a plain-HTTP Nockchain node (`NockRpc.kt`). Each is limited to a fixed list of hosts and paths.
- Alternate app icons, the portrait lock, screenshot blocking on sensitive screens, clipboard, sharing and haptics; on Google Play also the in-app review sheet, in-app updates and Play Billing for Pearl Hub Pro (`src/play`).

[BRIDGE.md](BRIDGE.md) is the contract between the page and the shell: every method, the push payload, the live ticker and the widget snapshot.

## Privacy and security

- App data is excluded from backups and device-to-device transfer, so a new phone needs the recovery phrase.
- Cleartext HTTP is off except for the one Nockchain node, by IP address (`res/xml/network_security_config.xml`). The WebView has no file or content access, never loads mixed content and keeps Safe Browsing on; each HTML file is served with a Content-Security-Policy.
- What each request carries, and what is stored where, is listed in the app under **Settings → Security & Privacy → Security Overview**.

## Building

Requirements: JDK 17 or newer, the Android SDK with platform 37 and build-tools (`local.properties` with `sdk.dir=…`, or `ANDROID_HOME`), and Node.js 22.6 or newer for the web bundle.

1. Build the web bundle from a [prl-hub-web](https://github.com/terryops/prl-hub-web) checkout. Its Android build also builds the wallet from a [prl-hub-wallet](https://github.com/terryops/prl-hub-wallet) checkout next to it.
2. Point this build at the bundle with `-PwebDir=/absolute/path/to/dist-android` (or `PRL_HUB_WEB=<prl-hub-web checkout>`). Every build first copies it into `app/src/main/assets/www/`; without an `index.html` there it stops and says so.
3. Build:

```sh
./gradlew assemblePlayDebug assembleDirectDebug   # debug APKs
./gradlew bundlePlayRelease                       # Google Play (.aab)
./gradlew assembleDirectRelease                   # direct download (.apk)
./gradlew test lint
```

The two flavors share the application id `com.prl.wizard`. `play` adds Google Play's review, update and billing libraries; `direct` has none of them.

**Push (optional).** Without a Firebase configuration the app builds and runs normally and push is simply off. To enable it, create a Firebase project with an Android app for your package name and put its `google-services.json` in `app/`, or write `firebase.properties` in the repository root with `projectId`, `applicationId`, `apiKey` and `gcmSenderId`. Notifications from the official server only reach the official app.

**Release signing.** Without `keystore.properties` in the repository root, release builds are unsigned. With it: `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, plus optional `upload.*` entries for a separate Play upload key.

## Checks

`./gradlew test` runs the unit tests. The scripts in `qa/` drive a debug build on an emulator over adb and the WebView's DevTools socket; several wipe the app's data first. Each says at the top how to run it and what it needs; use an emulator or a test device.

## License

Copyright (C) 2026 Cyber Corner

Pearl Hub for Android is free software, released under the [GNU General Public License v3.0](LICENSE). The bundled web interface and wallet are MIT-licensed in their own repositories. Third-party notices are in the app under **Settings → About → Open Source Licenses**.
