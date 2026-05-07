# jss-android

Flutter Android app that bundles **[JavaScript Solid Server (JSS)](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer)** for one-click mobile install. Realizes part of [JSS#46](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/46) ("Solid Pod on Every Phone") for non-technical users.

> **Status:** scaffold / pre-MVP. Tracking issue: [JSS#366](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366). Feasibility spike: [comment](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401585143).

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Android APK                                    │
│                                                 │
│   ┌──────────────────┐    ┌──────────────────┐  │
│   │  Flutter UI      │    │  nodejs-mobile   │  │
│   │  (WebView v1)    │◄──►│  thread          │  │
│   │                  │    │                  │  │
│   │  http://         │    │  bin/jss.js      │  │
│   │  localhost:4443  │    │  start --single- │  │
│   │                  │    │  user            │  │
│   └──────────────────┘    └──────────────────┘  │
│                                                 │
│           Foreground service notification       │
└─────────────────────────────────────────────────┘
```

JSS source is bundled as APK assets; `nodejs-mobile`'s patched Node runs it on a dedicated thread; a Flutter WebView points at `localhost:4443/`.

## Why this is feasible

The mobile-friendly choices were already in the JSS dep tree:

- **Zero native modules** in `node_modules/` — no `.node` binaries, no `binding.gyp`, no `node-gyp` install hooks. Bundling is a pure asset-copy.
- **Pure-JS substitutes** wired in: `bcryptjs` (vs `bcrypt`), `sql.js` (vs `better-sqlite3`), `@noble/curves` / `@noble/hashes` (vs native `secp256k1`).
- **Already runs on Android via Termux** — same Node 18+ runtime constraint, same arm64 target.

See the [feasibility spike](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401585143) for the full audit.

## v1 scope

- [ ] Flutter project scaffold (`flutter create .`)
- [ ] `nodejs-mobile-flutter` plugin wired in
- [ ] JSS source bundled as `assets/nodejs-project/`
- [ ] Boot `bin/jss.js start --single-user --port 4443 --host 127.0.0.1 --root <files-dir>/data --idp` on app launch (CLI surface verified by [spike](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401585143))
- [ ] WebView at `http://localhost:4443/`
- [ ] Foreground service + persistent notification
- [ ] APK build target (debug + release)
- [ ] First-run UX: pod-name picker, written to a launch-args file the Node side reads

### v1 caveats

- **No `--git`** (handler shells out to `git http-backend` via `child_process` — not available under `nodejs-mobile`).
- **No `--terminal`** (PTY spawn — same restriction).
- **Loopback only** by default (HTTP, not HTTPS); `--tunnel` is the path to public exposure via a remote JSS.

## Out of scope (v1)

- iOS — App Store policy on embedded JS interpreters is unclear.
- postmarketOS / Linux ARM64 (Flutter Linux target) — later.
- Native Flutter UI (replacing the WebView) — v2.

## Quick start (development)

```bash
# Prereqs: Flutter SDK, Android SDK, JDK 17, an Android device or emulator
flutter --version

# One-time project scaffold (see "v1 scope" — done at first commit after this)
flutter create --platforms android --org io.jss .

# Bundle JSS into the APK assets
./scripts/bundle-jss.sh   # (TBD) — copies bin/, src/, package.json, node_modules/

# Run on a connected device
flutter run
```

## Repo layout (target)

```
jss-android/
├── README.md             # this file
├── ARCHITECTURE.md       # design details
├── LICENSE               # AGPL-3.0-only (matches JSS)
├── pubspec.yaml          # Flutter project config
├── android/              # Android-specific build config
├── lib/
│   ├── main.dart         # entry point
│   ├── nodejs_bridge.dart  # plugin wiring
│   └── webview_page.dart # WebView shell
├── assets/
│   └── nodejs-project/   # JSS bundled here (gitignored except a .gitkeep)
│       ├── bin/
│       ├── src/
│       ├── package.json
│       └── node_modules/
└── scripts/
    └── bundle-jss.sh     # asset-bundling helper
```

## Refs

- [JSS#46](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/46) — umbrella vision ("Solid Pod on Every Phone")
- [JSS#366](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366) — this app's tracking issue
- [JSS#45](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/45), [JSS#266](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/266) — Termux precedent
- [JSS#365](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/365) — E2EE composes well with a personal mobile pod
- [`nodejs-mobile`](https://github.com/nodejs-mobile/nodejs-mobile) — patched Node runtime for iOS/Android
- [`nodejs-mobile-flutter`](https://github.com/JaneaSystems/nodejs-mobile-flutter) — Flutter plugin

## License

[AGPL-3.0-only](./LICENSE) — matches JSS.
