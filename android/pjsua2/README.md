# :pjsua2 module

This module packages **PJSIP's pjsua2 API** for the app. It is intentionally
empty in git except for this README and the Gradle file — the actual payload is
**generated**, not hand-written:

- `src/main/java/org/pjsip/pjsua2/**` — SWIG-generated Java bindings
- `src/main/jniLibs/<abi>/libpjsua2.so` — prebuilt native library

## Bootstrap (run once)

The app will **not compile** until you generate these. From `android/`:

```bash
ANDROID_NDK_ROOT=/path/to/ndk ./scripts/build-pjsip.sh
```

This clones `pjproject`, builds it audio-only for the configured ABIs
(`arm64-v8a x86_64` by default), runs SWIG, and copies the outputs here.

Requirements: Android **NDK r27+**, **SWIG 4.x**, `make`, `git`, `python3`.

## ABIs

Keep the `abiFilters` in this module's `build.gradle.kts` and in `:app`'s in
sync with the `APP_ABIS` you actually built. `x86_64` is only needed for the
emulator; drop it for release builds to shrink the APK.
