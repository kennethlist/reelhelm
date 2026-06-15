pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reelhelm-wear"

include(":app")     // the real text-first Wear OS app (org.reelhelm.wear)
include(":pjsua2")  // PJSIP/pjsua2 SWIG bindings + prebuilt .so (copied from ../android)
include(":probe")   // throwaway viability probe (org.reelhelm.weartest) — kept for hardware re-runs
