// Thin library module that packages the PJSIP pjsua2 SWIG bindings (Java) and
// the prebuilt native libraries. Both are produced by scripts/build-pjsip.sh
// and committed into src/main/java/ and src/main/jniLibs/ respectively.
//
// This module has NO Kotlin/Compose — it is plain Java + .so payload, so it
// compiles fast and you only rebuild it when you rebuild PJSIP itself.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.pjsip.pjsua2"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        // Keep in sync with the ABIs you actually built in build-pjsip.sh.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The SWIG-generated Java lives under src/main/java/org/pjsip/pjsua2/ and the
    // prebuilt libpjsua2.so under src/main/jniLibs/<abi>/ — both default paths,
    // so no extra sourceSets config is required.
}
