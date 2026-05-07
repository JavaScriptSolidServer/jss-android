# jss-android

Native Android app (Kotlin) that bundles **[JavaScript Solid Server (JSS)](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer)** for one-click mobile install. Realizes part of [JSS#46](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/46) ("Solid Pod on Every Phone") for non-technical users.

> **Status:** scaffold / pre-MVP. Tracking issue: [JSS#366](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366). Pivot decision: [comment](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401796552).

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Android APK (Kotlin)                           │
│                                                 │
│   ┌──────────────────┐    ┌──────────────────┐  │
│   │  WebView         │    │  libnode.so      │  │
│   │  (MainActivity)  │    │  (JNI thread)    │  │
│   │                  │◄──►│                  │  │
│   │  http://         │HTTP│  bin/jss.js      │  │
│   │  127.0.0.1:4443  │    │  (single-user)   │  │
│   │                  │    │                  │  │
│   └──────────────────┘    └──────────────────┘  │
│                                                 │
│           Foreground service + notification     │
└─────────────────────────────────────────────────┘
```

`libnode.so` from [`nodejs-mobile`](https://github.com/nodejs-mobile/nodejs-mobile) is linked via JNI and runs JSS on a dedicated thread. The WebView talks to JSS over HTTP — no platform-channel bridge needed (JSS is already a server, the WebView is already a client).

## Why this is feasible

- **JSS has zero native modules** — pure-JS only, by design (`bcryptjs`, `sql.js`, `@noble/curves`). Verified by static audit. Bundling is a pure asset-copy.
- **Already runs on Android via Termux** — same Node 18+ runtime constraint, same arm64 target.
- **Prior art**: [Manyverse](https://www.manyverse.io/) (Scuttlebutt) and [Mapeo](https://www.mapeo.app/) ship exactly this pattern (`nodejs-mobile` + WebView), both highlighted on [nodejs-mobile.github.io](https://nodejs-mobile.github.io/).

See the [feasibility spike](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401585143) for the full dep audit, and the [pivot rationale](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401796552) for why we landed on Kotlin instead of Flutter or React Native.

## v1 scope

- [x] `scripts/bundle-jss.sh` — npm pack → install prod deps → refuse to bundle native modules
- [x] Launch args verified end-to-end: `bin/jss.js start --single-user --port 4443 --host 127.0.0.1 --root <files-dir>/data --idp`
- [ ] Android Studio Kotlin scaffold (`build.gradle.kts`, `MainActivity.kt`, `AndroidManifest.xml`, theme)
- [ ] `scripts/fetch-libnode.sh` — pulls `libnode.so` (arm64-v8a + armeabi-v7a) from `nodejs-mobile` releases
- [ ] JNI shim — ~80 lines C++, lifted from [JaneaSystems samples](https://github.com/JaneaSystems/nodejs-mobile-samples/tree/master/android/native-gradle)
- [ ] Foreground service + persistent notification ("JSS pod running")
- [ ] WebView wired to `http://127.0.0.1:4443/`, with port-bind retry (4443 → 4444 → ...)
- [ ] First-launch asset copy from APK assets to `Context.filesDir`
- [ ] AAB build with ABI splits

## v1 caveats

- **No `--git`, no `--terminal`** — `nodejs-mobile`'s Node disallows `child_process`. Both are optional flags; v1 just doesn't enable them.
- **Loopback only** by default (HTTP, not HTTPS). `--tunnel` is the path to public exposure via a remote JSS (decentralized ngrok).
- **Node 18.20.4** is the upstream mobile build; Node 18 went EOL April 2025. Loopback bind cuts the attack surface materially. Long-term, we watch upstream for a community Node 20/22 mobile build.
- **APK size**: libnode is ~30 MB/ABI. Ship as AAB so Play Store delivers per-device.

## Out of scope (v1)

- **iOS** — App Store rule 2.5.2 has a grey zone on dynamic JS evaluation; OIDC client registration may trip it. Audit before any iOS push.
- **postmarketOS / Linux ARM64** — later.
- **Native UI** (Compose/Views) replacing the WebView — v2.

## Quick start (development)

```bash
# Prereqs: Android Studio (or Android SDK + JDK 17), an Android device or emulator
adb devices                              # confirm device is visible

# Vendor libnode.so for the target ABIs (TBD)
./scripts/fetch-libnode.sh

# Bundle JSS into the APK assets
./scripts/bundle-jss.sh

# Build & install on a connected device (TBD: gradle scaffold)
./gradlew installDebug
```

## Repo layout (target)

```
jss-android/
├── README.md                 # this file
├── ARCHITECTURE.md           # design details
├── LICENSE                   # AGPL-3.0-only (matches JSS)
├── build.gradle.kts          # root gradle
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   ├── libnode/              # vendored libnode.so + headers (gitignored)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/live/jss/jss_android/
│       │   ├── MainActivity.kt
│       │   └── JssService.kt
│       ├── cpp/
│       │   ├── native-lib.cpp     # JNI shim
│       │   └── CMakeLists.txt
│       ├── assets/
│       │   └── jss/               # JSS bundled here at build time
│       └── res/
└── scripts/
    ├── bundle-jss.sh         # → app/src/main/assets/jss/
    └── fetch-libnode.sh      # → app/libnode/{abi}/libnode.so
```

## Refs

- [JSS#46](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/46) — umbrella vision ("Solid Pod on Every Phone")
- [JSS#366](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366) — this app's tracking issue
- [JSS#45](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/45), [JSS#266](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/266) — Termux precedent
- [JSS#365](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/365) — E2EE composes well with a mobile pod
- [`nodejs-mobile`](https://github.com/nodejs-mobile/nodejs-mobile) — patched Node runtime for Android/iOS
- [`nodejs-mobile-samples`](https://github.com/JaneaSystems/nodejs-mobile-samples) — reference Kotlin Android project
- [Manyverse](https://www.manyverse.io/), [Mapeo](https://www.mapeo.app/) — prior art for this pattern

## License

[AGPL-3.0-only](./LICENSE) — matches JSS.
