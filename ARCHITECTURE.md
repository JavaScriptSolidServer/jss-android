# Architecture

## Goal

Ship a single-tap Android install that runs a personal Solid pod on the phone. The user opens the app and a JSS instance is running at `http://localhost:4443/` inside the app's process; closing the app stops the pod (or, with the foreground service, keeps it alive in the background).

## Components

### 1. Bundled JSS (the asset)

- Path inside APK: `assets/nodejs-project/`
- Contents: full JSS tree as published to npm — `bin/`, `src/`, `package.json`, `node_modules/`
- Why the full `node_modules/`: it's pure JS, so copying it once at build-time is cheaper than `npm install` on first launch (and avoids needing a network on first run)
- Size estimate: ~1.4 MB unpacked (per `npm publish` output for `javascript-solid-server@0.0.169`) + node_modules ≈ 30–50 MB

### 2. nodejs-mobile runtime

- Provides a patched Node binary cross-compiled for Android (arm64-v8a, armeabi-v7a, x86_64)
- Runs as a dedicated thread inside the Android process
- Restrictions vs upstream Node: no `child_process`, no `cluster`, no `worker_threads` until recent versions, no native addons unless explicitly built for Android

### 3. Flutter plugin (`nodejs-mobile-flutter`)

- Boots the Node runtime, hands it the entry script (`assets/nodejs-project/bin/jss.js`) plus startup args
- Provides a Dart channel for bidirectional message passing (used post-MVP for native UI ↔ JSS commands; v1 doesn't need it)

### 4. Flutter UI

- **v1**: a single `WebView` widget pointed at `http://localhost:4443/`
- Initial route depends on JSS config; default lands on the IDP landing or the `/{pod}/` root
- v2: native screens for sign-in, file browse, sharing — replacing the WebView screen by screen

### 5. Android foreground service

- Standard pattern: when JSS is "running" the app posts a persistent notification ("JSS is running on port 4443")
- Required to avoid the OS killing the Node thread when the app goes to background
- User can stop the pod by tapping the notification's Stop action

## Boot sequence

1. App `main()` → run Flutter app
2. Flutter `initState` → call `NodejsMobile.startNodeProject()` with:
   - script: `bin/jss.js`
   - args: `['start', '--single-user', '--port', '4443', '--data-root', <android-files-dir>/data]`
3. Node thread starts; JSS imports its modules; Fastify binds to `127.0.0.1:4443`
4. Flutter listens for the "ready" message on the channel (or polls `GET /` until 200) and shows the WebView

## Data layout on device

- `<android-files-dir>/data/` — JSS pod data (passed via `--data-root` and `DATA_ROOT` env)
- `<android-files-dir>/data/.idp/` — IDP credentials, keys
- `<android-files-dir>/.well-known/` — token store, etc.
- `<cache-dir>/` — JSS notification queues, ephemeral state

The pod's filesystem layout is unchanged from desktop JSS — we just point `--data-root` at app-private storage.

## Auth model on a single-user phone pod

- `--single-user` mode: one pod, one WebID, no registration UI
- Default: pod owner is the device user; the IDP login screen still works for cross-app sign-in flows (xlogin, did:nostr signers)
- E2EE composes cleanly: the device-local Nostr key is the pod's WebID and the encryption key (see [JSS#365](https://github.com/JavaScriptSolidServer/JavaScriptSolidServer/issues/365))

## Public exposure (optional)

- v1: pod is loopback-only, accessible only inside the app's WebView
- For sharing: `--tunnel` connects to a remote JSS that proxies a public hostname back to the local pod (decentralized ngrok)
- Long-term: a "share via tunnel" toggle in the app settings, with a paired remote JSS service

## What we're not doing

| Choice | Why |
|--------|-----|
| Run Node via Termux instead of nodejs-mobile | Termux requires a separate app + manual setup; defeats "one-click install" |
| Cross-compile native deps for Android | Not needed — JSS already uses pure-JS substitutes everywhere |
| Custom Node fork for additional capabilities | nodejs-mobile is maintained; forking is a long-term burden |
| iOS in v1 | App Store rule 2.5.2 (no executable code download) and embedded-interpreter policy is fuzzy; needs separate research |
| Native Flutter UI in v1 | WebView is faster to ship and reuses JSS's existing IDP / mashlib UIs |

## Open questions

- Does `nodejs-mobile`'s most recent release ship Node ≥ 18? (verify before scaffold, but `engines.node: ">=18.0.0"` is JSS's floor)
- Memory ceiling on cheap Android devices (1 GB RAM)? `oidc-provider` is the heaviest dep; might need `--idp` off in a "lite" mode
- How do we surface the WebID/pod URL outside the app? (Deep link? Share sheet? A "copy URL" button?)
- Does the foreground-service notification need a stop action wired to a graceful Fastify shutdown? (yes — need to expose this via the plugin channel)

## Build & release

- Debug builds: `flutter run` from a dev box with a connected device
- Release: `flutter build apk --release` produces a signed APK; `flutter build appbundle` for Play Store
- CI (later): GitHub Actions matrix with `subosito/flutter-action`, building both `--debug` and `--release` artifacts on each tag
