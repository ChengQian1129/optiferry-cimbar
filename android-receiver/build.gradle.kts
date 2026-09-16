plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

// Keep Gradle's large, disposable outputs on the WSL filesystem. The release
// APK is copied to ../dist by scripts/build-android.sh.
val externalBuildRoot = System.getenv("OPTIFERRY_ANDROID_BUILD_ROOT")
if (!externalBuildRoot.isNullOrBlank()) {
    layout.buildDirectory.set(file("$externalBuildRoot/root"))
    subprojects {
        layout.buildDirectory.set(file("$externalBuildRoot/$name"))
    }
}
