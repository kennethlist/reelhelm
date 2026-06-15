# reelhelm Wear OS

Standalone (LTE/WiFi, no phone tether) Wear OS companion to the phone SIP client
in `../android/`. **Text-first**: built on the conclusion that inbound *calls*
can't ring reliably on a dozing watch without FCM, but *texts* and *missed-call
notices* can arrive reliably "eventually" via a Doze-friendly poll/sync model.
See `../docs/wear-os-feasibility.md` for the why.

Three Gradle modules:

| Module | Package | What |
|---|---|---|
| `:app` | `org.reelhelm.wear` | **The real app** — text-first Wear UI + data + sync + real SIP |
| `:pjsua2` | `org.pjsip.pjsua2` | PJSIP/pjsua2 SWIG bindings + prebuilt `.so` (arm64-v8a, x86_64), copied from `../android` |
| `:probe` | `org.reelhelm.weartest` | Throwaway viability probe (Telecom + Doze), kept for hardware re-runs |

## SIP integration — REAL (verified 2026-06-15)

`PjsipTransport` (the production `SipTransport`) ports the phone app's
`SipManager`/`MyAccount`/`MyCall`/`SipThread` over the same prebuilt pjsua2 libs.
**Verified on the Wear OS 5 emulator against the dockerized Asterisk:**

**Messaging**
- `reg state=REGISTERED code=200 reason=OK` — real SIP REGISTER (UDP) as ext 1001
- outbound SIP `MESSAGE` → `MESSAGE status peer=1001 ok=true` (200 OK)
- inbound SIP `MESSAGE` via `onInstantMessage` → persisted + notified + shown
  (self-loopback through Asterisk's `sms-out` dialplan)

**Voice calls** (outbound + inbound-when-awake; managed directly, no Telecom)
- inbound INVITE → incoming-call screen (Answer/Decline) → answer → call goes
  ACTIVE (Asterisk shows `PJSIP/1001 … Up Echo()`) → hang up → logged as *answered*
- outbound: place call → Asterisk authenticates 1001, runs the dialplan, dials the
  callee → ends if the callee is unreachable
- never-answered inbound is logged as a **missed call**; in-call UI = mute / speaker /
  hang up; audio bridged via pjsua2 (`MyCall.onCallMediaState`)

> Call *signaling/answer/media-negotiation* is verified; actual RTP audio doesn't
> flow on the emulator (`-no-audio`, and emulators don't pass RTP) — that needs real
> hardware. Calls aren't guaranteed to ring a deeply-dozing watch (the no-push
> limitation); they surface when the watch is awake/reachable.

To run the UI without a server, swap `PjsipTransport` → `MockTransport` in `WearApp`.

## :app — architecture

```
Wear Compose UI (round-screen)
  Home · Messages · Conversation · Missed calls · Account · Sync status
        |
   WearViewModel  ─ StateFlows ─►  Compose
        |
   MessageRepository ──► Room (reelhelm-sip.db, schema mirrors the phone app)
        |
   SipTransport (interface)          SyncWorker (WorkManager, 15-min periodic)
     ├─ PjsipTransport ◄── DEFAULT       └─ Doze-friendly pull on maintenance windows
     └─ MockTransport  (UI-only demo)        + on app resume
```

- **Real SIP** via `PjsipTransport` (see the verified section above). Live inbound
  arrives on the transport's `incoming`/`missed` flows (collected by the repository
  for the app's lifetime); the `pollInbound`/`pollMissedCalls` path stays for future
  **server-side store-and-forward** (the one piece still to add for true offline
  catch-up). `MockTransport` remains as a no-server UI demo.
- **Doze-friendly by design.** Inbound texts/missed-calls come from `sync()`, driven
  by a 15-min `WorkManager` job (runs in Doze maintenance windows) + a sync on every
  app resume. No persistent socket, no foreground service, no FCM.
- **Room schema is identical** to the phone app (`accounts`/`messages`/`call_logs`,
  same db name) so a shared DB / real server sync drops in without a migration.
- **Text entry** uses Wear `RemoteInput` (voice + on-watch keyboard) plus canned
  quick-replies — idiomatic for a round screen.

### Build & run

```bash
cd android-watch
./gradlew :app:assembleDebug
adb -s <wear-emulator> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <wear-emulator> shell pm grant org.reelhelm.wear android.permission.POST_NOTIFICATIONS
adb -s <wear-emulator> shell am start -n org.reelhelm.wear/.MainActivity
adb -s <wear-emulator> logcat -s WearSip      # transport + sync trace
```

Verified on the `wearProbe` AVD (Wear OS 5 / API 34): home menu, conversation list,
thread (in/out bubbles + delivery status), quick-reply + RemoteInput send, the
store-and-forward sync surfacing queued items, missed-call list, account editor, and
sync-status screen — plus inbound notifications on the watch face.

> Emulator networking gotcha (only matters once you wire real network): the Wear
> image boots with no working network; `adb reboot` brings `eth0` up. The mock
> transport needs no network, so the demo runs without it.

### Install on a real watch

Needs an **arm64 watch on Wear OS 4+ (API 33+)** — Pixel Watch, Galaxy Watch 4/5/6/7,
etc. (minSdk 31, so Wear OS 3 / API 30 won't take it).

**Build an arm64-only APK** (the default build also bundles x86_64 for the emulator —
~83 MB; arm64-only is leaner for the watch):

```bash
cd android-watch
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=arm64-v8a
# If that still bundles x86_64, temporarily set abiFilters to listOf("arm64-v8a")
# in app/build.gradle.kts, build, then revert. APK: app/build/outputs/apk/debug/app-debug.apk
```

Watches have no USB data port, so install over **Wi-Fi ADB**:

1. On the watch: Settings → System → About → tap **Build number** 7× to unlock
   **Developer options**, then enable **ADB debugging** and **Wireless debugging**.
   Put the watch on the **same Wi-Fi** as your computer.
2. Pair + connect (Wear OS 4/5 wireless debugging shows an IP:PORT + 6-digit code):
   ```bash
   adb=~/Android/Sdk/platform-tools/adb
   $adb pair <watch-ip>:<pair-port>      # enter the 6-digit code
   $adb connect <watch-ip>:<debug-port>  # main port on the Wireless debugging screen
   $adb devices                          # confirm the watch is listed
   ```
   (Older "Debug over Wi-Fi" just prints `adb connect <ip>:5555` — skip the pair step.)
3. Install + launch:
   ```bash
   $adb -s <watch> install -r app/build/outputs/apk/debug/app-debug.apk
   $adb -s <watch> shell pm grant org.reelhelm.wear android.permission.RECORD_AUDIO
   $adb -s <watch> shell pm grant org.reelhelm.wear android.permission.POST_NOTIFICATIONS
   $adb -s <watch> shell am start -n org.reelhelm.wear/.MainActivity
   ```
   (Or tap **reelhelm** in the watch app list and accept the permission prompts.)

**To actually register:** the seed account is empty (no credentials in source), so on
the watch open the **Account** screen and set **Server** to your Asterisk's real
reachable IP (NOT `10.0.2.2` — that's emulator-only), plus **Extension** and
**Password**. The watch's network must be able to reach that Asterisk; the Status
screen then shows **Registered**. (Bluetooth-tethered watches can ADB over Bluetooth,
but standalone Wi-Fi is far simpler.)

### Remaining work for production
1. **Server-side store-and-forward** — Asterisk page-mode `MESSAGE` is fire-and-forget,
   so texts/missed-calls that arrive while the watch is asleep are lost. Add a queue
   (Asterisk dialplan or a small sidecar) that `pollInbound`/`pollMissedCalls` drain on
   sync. This is what makes the text-first "arrives eventually" promise hold on real
   hardware. (The seam is already there — only the server + the two poll methods change.)
2. **Account UX** — transport (UDP/TCP/TLS) is hardcoded to the seeded account; surface
   a selector on the Account screen if needed.
3. **Hardware validation** — confirm registration survives real-watch Doze (the one
   unknown from `../docs/wear-os-feasibility.md`).

### Live SIP test (what was verified)
```bash
# Asterisk up (asterisk/docker-compose.yml), watch reaches host at 10.0.2.2:5060
adb -s <wear> shell am broadcast -n org.reelhelm.wear/.DebugReceiver \
  -a org.reelhelm.wear.DEBUG --es action send --es peer 1001 --es body howdy
adb -s <wear> logcat -s WearSip   # REGISTERED, MESSAGE status ok, RX MESSAGE
```

## :probe — viability probe (throwaway, kept for hardware)

The bare-minimum Telecom + Doze probe used to prove feasibility. Build/run:

```bash
./gradlew :probe:assembleDebug
adb install -r probe/build/outputs/apk/debug/app-debug.apk
adb shell am broadcast -n org.reelhelm.weartest/.ProbeReceiver -a org.reelhelm.weartest.PROBE --es action register|incoming|net_start|net_stop
adb logcat -s WEARPROBE
```

Findings (Telecom feasible via self-managed + wakelock/direct-launch; inbound-Doze
unproven on emulator) are written up in `../docs/wear-os-feasibility.md`. The
host-side Doze push server is `tools/doze_push_server.py`.
