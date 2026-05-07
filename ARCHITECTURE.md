# Architecture

## Goal

Ship a single-tap Android install that runs a personal Solid pod on the phone. The user opens the app and a JSS instance is running at `http://127.0.0.1:4443/` inside the app's process; closing the app stops the pod (or, with the foreground service, keeps it alive in the background).

## Why native Kotlin (not Flutter, not React Native, not a plugin)

> The original draft specified Flutter. Research showed there's no maintained Flutter↔nodejs-mobile plugin, and on closer inspection **the plugin layer is dead weight for our shape**: every plugin (RN, Flutter, Capacitor) exists to marshal messages between an outer JS runtime and the embedded Node. Our UI is a `WebView`, the WebView talks to JSS over HTTP, and the bridge is HTTP. Pivot rationale: [JSS#366 comment](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/366#issuecomment-4401796552). Same APK story (JS bundle + WebView), one fewer abstraction.

## Components

### 1. Bundled JSS (the asset)

- Path inside APK: `app/src/main/assets/jss/`
- Contents: full JSS tree as published to npm — `bin/`, `src/`, `package.json`, `node_modules/`
- Why the full `node_modules/`: it's pure JS, so copying it once at build-time is cheaper than `npm install` on first launch (and avoids needing a network on first run)
- Verified bundle size for `javascript-solid-server@0.0.169`: **~62 MB / 5444 files** (per `scripts/bundle-jss.sh` smoke run)
- Not tracked in git; produced fresh from `scripts/bundle-jss.sh` for each build

### 2. nodejs-mobile runtime (`libnode.so`)

- Patched Node binary cross-compiled for Android (arm64-v8a, armeabi-v7a, x86_64)
- Vendored from [`nodejs-mobile/nodejs-mobile` releases](https://github.com/nodejs-mobile/nodejs-mobile/releases) by `scripts/fetch-libnode.sh`
- Currently ships **Node 18.20.4** (last release Oct 2024; Node 18 EOL April 2025 — see Risks)
- Restrictions vs upstream Node: no `child_process`, no `cluster`, no native addons unless explicitly built for Android
- Linked via JNI/CMake; loaded into a dedicated thread inside the Android process

### 3. JNI shim (`native-lib.cpp`)

- ~80 lines of C++, lifted near-verbatim from [JaneaSystems/nodejs-mobile-samples](https://github.com/JaneaSystems/nodejs-mobile-samples/tree/master/android/native-gradle/app/src/main/cpp)
- Two responsibilities:
  1. `extern "C" int callintoNode(int argc, char *argv[]) { return node::Start(argc, argv); }` — exposes Node's main loop
  2. Pipe stdout/stderr to logcat (otherwise Fastify's startup logs vanish)
- Optional reuse from `nodejs-mobile-react-native`'s C++ layer (MIT licensed): the assetCopy routine and JNI thread setup. We're a server, not a client of `rn-bridge.cpp`'s message channel — we don't need that file.

### 4. MainActivity (Kotlin)

- Single-screen `WebView` pointed at `http://127.0.0.1:4443/`
- WebView config: `domStorageEnabled = true`, `javaScriptEnabled = true`, allow mixed content for loopback, override `shouldOverrideUrlLoading` so off-pod links open in the system browser (avoids in-WebView OAuth capture)
- Boot waits for the foreground service to report Node ready before loading the URL — show a splash in the meantime

### 5. JssService (Kotlin foreground service)

- Wraps the Node thread in an Android `Service` with `START_STICKY` and a persistent notification ("JSS pod running on :4443")
- Required: without a foreground service, Android kills the Node thread when the app backgrounds
- Notification has a "Stop" action wired to a graceful Fastify shutdown via a process-internal HTTP call (or sigterm via the JNI side)
- Reference: `node_flutter`'s `NodeService.kt` (~133 lines, MIT) is the cleanest example to lift the lifecycle pattern from

## Boot sequence

1. App `onCreate` → start `JssService` as a foreground service
2. `JssService` spawns a worker thread → JNI `startNode(args)`:
   - script: `<filesDir>/jss/bin/jss.js`
   - args: `['start', '--single-user', '--port', '4443', '--host', '127.0.0.1', '--root', '<filesDir>/data', '--idp']`
   - env: `JSS_SINGLE_USER_PASSWORD=<derived-or-stored>` (only on first launch, to seed the IDP account)
3. Node imports JSS modules; Fastify binds to `127.0.0.1:4443`
4. Service polls `GET http://127.0.0.1:4443/` (100 ms backoff, max ~5 s) → broadcasts ready
5. `MainActivity` receives ready → loads the WebView URL

## Asset layout on device

| Path | Purpose |
|---|---|
| `<filesDir>/jss/` | JSS tree, copied from APK assets on first launch (or when build version changes) |
| `<filesDir>/data/` | Pod data: `profile/`, `public/`, `private/`, `inbox/`, `settings/`, `.idp/` |
| `<filesDir>/data/.idp/` | IDP credentials + signing keys (verified by spike: created on first boot) |
| `<cacheDir>/` | Ephemeral state (notification queues, etc.) |

The pod's filesystem layout is unchanged from desktop JSS — we just point `--root` at app-private storage. Verified by booting `jss start --single-user --port 4444 --root /tmp/foo --idp`: full pod tree is created at the supplied path with nothing leaking to `~/.jss/` or the cwd.

> **CLI flag note (verified 2026-05-08):** the flag is `-r, --root <path>`, not `--data-root`.

## Auth model on a single-user phone pod

- `--single-user` mode: one pod, one WebID, no registration UI
- Default pod owner is the device user
- The IDP login screen still works for cross-app sign-in flows (xlogin, did:nostr signers via NIP-07 in the WebView)
- E2EE composes cleanly: device-local Nostr key is the pod's WebID and the encryption key (see [JSS#365](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/365))

## Public exposure (optional)

- v1: pod is loopback-only, accessible only inside the app's WebView
- For sharing: `--tunnel` connects to a remote JSS that proxies a public hostname back to the local pod (decentralized ngrok)
- Long-term: a "share via tunnel" toggle in the app settings, with a paired remote JSS service

## What we're not doing

| Choice | Why |
|---|---|
| Run Node via Termux | Termux requires a separate app + manual setup; defeats "one-click install" |
| Cross-compile native deps | Not needed — JSS uses pure-JS substitutes throughout |
| Custom Node fork | `nodejs-mobile` is maintained; forking is a long-term burden |
| Flutter / React Native shell | Both pay for an outer JS runtime to talk to Node — but our UI is a WebView, talking to Node over HTTP. The plugin layer is dead weight |
| Bare/Pear runtime | No `fs` / `http` builtins — would require rewriting JSS's transitive deps against `bare-*` modules |
| iOS in v1 | App Store rule 2.5.2 grey zone on dynamic JS eval; OIDC client registration may trip it |
| Native UI (Compose/Views) in v1 | WebView reuses JSS's existing IDP + mashlib UIs; faster to ship |

## Risks / open questions

- **Node 18 EOL.** Upstream `nodejs-mobile` last released Oct 2024 with Node 18.20.4. Node 18 stopped getting security patches April 2025. For a server running OIDC + TLS, this is real. Mitigations: 127.0.0.1-only bind cuts attack surface; track community efforts toward a Node 20/22 mobile build (watch upstream issues).
- **APK size.** `libnode.so` is ~30 MB per ABI. Ship as AAB with ABI splits (arm64-v8a + armeabi-v7a; drop x86_64 unless emulator support is needed) so Play Store delivers per-device.
- **Port collisions.** 4443 may be taken on the device. Plan: try 4443, then 4444, 4445, … and write the chosen port to a `SharedPreferences` the WebView reads. Or bind to port 0 and read back via a JNI return.
- **Memory on low-end devices.** `oidc-provider` is the heaviest dep; `--no-idp` "lite" mode is the fallback for 1 GB RAM phones.
- **First-run IDP password UX.** Options: (a) auto-generate, store in Android Keystore, never show; (b) prompt user; (c) skip IDP entirely on phone (sign in via did:nostr only). Pick before v1.
- **Foreground service stop action.** Notification "Stop" needs to wire to a graceful Fastify shutdown — sigterm via JNI or an in-process HTTP call to a `/.shutdown` admin endpoint we'd need to add to JSS.
- **WebID URL discovery from outside the app.** How does the user get their pod URL into another app on the phone? Options: deep link, share sheet, a "copy URL" button. Pick before v1.

## Build & release

- Debug: `./gradlew installDebug` from a dev box with a connected device (ADB)
- Release: `./gradlew bundleRelease` produces a signed AAB for Play Store; `./gradlew assembleRelease` for sideload-friendly APKs
- CI (later): GitHub Actions, JDK 17, Android SDK action, NDK action, build both `--debug` and `--release` AABs on each tag
- Pre-build hooks (orchestrated outside gradle for clarity):
  - `scripts/bundle-jss.sh` — populates `app/src/main/assets/jss/`
  - `scripts/fetch-libnode.sh` — populates `app/libnode/{abi}/libnode.so`
- Both are idempotent and check version pins; CI invokes them before `./gradlew`
