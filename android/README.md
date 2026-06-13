# reelhelm-sip (Android client)

A deliberately simple Android SIP client for the [reelhelm](../README.md)
Asterisk + SIP trunk server. Does exactly what's needed and nothing else:

- **Calls** — outbound + inbound, integrated with the OS via a self-managed
  Telecom `ConnectionService` (native incoming UI, Bluetooth/headset, audio focus).
- **SMS** — text messaging over **SIP `MESSAGE`** (RFC 3428 page-mode). The
  reelhelm server bridges these to real PSTN SMS via the SIP trunk.
- **Contacts** — read-only from the Android address book (caller-ID lookup +
  picker). No LDAP / no separate directory.
- **Keep-alive** — a foreground service keeps every account registered; restarts
  on boot and re-registers on network changes.
- **Multiple SIP accounts** — register many at once; pick which one dials.

No help center, call recording, analytics, or subscriptions.

## Tech

- Kotlin · Jetpack Compose · Room · Coroutines
- **PJSIP / pjsua2** as the SIP stack (GPLv3 — fine for personal/open-source use)
- minSdk 31 (Android 12), targetSdk 35

## Build

### 1. Build PJSIP once

The `:pjsua2` module is empty until you generate the bindings + native libs:

```bash
ANDROID_NDK_ROOT=/path/to/ndk ./scripts/build-pjsip.sh
```

Needs **NDK r27+**, **SWIG 4.x**, `make`, `git`, `python3`. See
[`pjsua2/README.md`](pjsua2/README.md). This is the slow, one-time step.

### 2. Build the app

Open `android/` in Android Studio (it will generate the Gradle wrapper), or:

```bash
gradle wrapper            # first time, if you have a system Gradle
./gradlew :app:assembleDebug
```

## Configure

1. Run the reelhelm server (`docker compose up -d --build` in the repo root) with
   at least one extension and `sms.enabled` in `data/config.json`.
2. Launch the app → **Settings** → **+** → add an account:
   - Username = extension id (e.g. `1001`), password = its password
   - Host = the Asterisk LAN IP/hostname, port `5060`
   - Transport: **TCP** recommended (more reliable through mobile NAT/Doze)
3. The account chip should turn **REGISTERED**. Verify server-side:
   `docker exec reelhelm asterisk -rx "pjsip show endpoints"`.
4. For reliable background calls, exempt the app from battery optimization when
   prompted.

## Known limitations (inherited / by design)

- **SMS**: 160 chars/segment, **no MMS**, inbound to a single DID — these are
  SIP-trunk-over-SIP-MESSAGE limits (see the server README), surfaced in the UI.
- **No push**: with self-hosted Asterisk there's no FCM, so deep Doze can delay
  inbound calls. Mitigated by the foreground service + TCP registration +
  keep-alive + battery-optimization exemption.
- Outgoing-message delivery state is best-effort (the pjsua2 Java binding doesn't
  expose a per-MESSAGE userData token).

## Build notes (verified working)

This project has been built end-to-end on Linux/x86_64 with:

- **JDK 17** (Temurin, `~/jdk17`) — a full JDK with `javac` is required both by
  the SWIG `java` step and by Gradle.
- **NDK r27c** (`27.2.12479018`), **SWIG 4.1**, **Gradle 8.11.1**, SDK platform
  35 / build-tools 35.0.0.

Decisions baked into `build-pjsip.sh` that made the native build succeed:

- **Audio backend = OpenSL ES**, not Oboe. Oboe lives in PJSIP's
  `third_party/oboe` *submodule*, which a shallow clone doesn't fetch; OpenSL ES
  is built into the NDK and is fine for VoIP.
- **TLS transport disabled.** No OpenSSL backend is compiled in, so enabling TLS
  left `pj_ssl_sock_*` undefined at link time. The reelhelm server is UDP/TCP.
  To enable TLS later, cross-compile OpenSSL for Android first.
- **`libc++_shared.so` is bundled** next to `libpjsua2.so` — it's a dynamic
  dependency and the app crashes at load without it.

The pjsua2 Java API specifics that the Kotlin already matches: `OnRegStateParam`/
`OnInstantMessageStatusParam` `.code` is an `int` (no `.swigValue()`);
`CallInfo.media` is a `java.util.AbstractList` (use `.size` / `[i]`);
`Call.getMedia(long)` takes a `Long`.

ABIs: only **arm64-v8a** is built by default here. For an x86_64 emulator, run
the script with `APP_ABIS="arm64-v8a x86_64"`.

## Layout

```
android/
├── scripts/build-pjsip.sh         # one-time PJSIP build
├── pjsua2/                        # generated SWIG bindings + .so (vendored)
└── app/src/main/java/org/reelhelm/sip/
    ├── sip/        SipManager, SipThread, MyAccount, MyCall, SipService, …
    ├── telecom/    SipConnectionService, SipConnection, SipPhoneAccounts
    ├── data/       Room (accounts + messages), ContactsRepository
    └── ui/         Compose screens + SipViewModel
```
