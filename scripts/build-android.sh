#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export ANDROID_HOME="${ANDROID_HOME:-/mnt/f/optiferry-storage/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35 and build-tools 34}"
if [[ ! -d "$ANDROID_HOME" ]]; then
    echo "ERROR: Android SDK directory does not exist: $ANDROID_HOME" >&2
    exit 1
fi
cd "$root/android-receiver"
export OPTIFERRY_ANDROID_BUILD_ROOT="${OPTIFERRY_ANDROID_BUILD_ROOT:-$HOME/.cache/optiferry-android-build}"
# Gradle and Android tooling change cache/output modes (including chmod 700),
# which is not supported reliably by the Windows-mounted F drive. Keep all
# disposable build state on WSL's native filesystem; only the final APK is
# copied back into the repository's dist/ directory.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
if [[ -z ${JAVA_HOME:-} ]]; then
    if [[ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]]; then
        export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    else
        echo 'ERROR: JDK 17 is required to build the Android receiver.' >&2
        exit 1
    fi
fi
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "ERROR: JAVA_HOME does not point to a usable JDK: $JAVA_HOME" >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
initArgs=()
if [[ ${OPTIFERRY_BUILD_MIRROR:-0} == 1 ]]; then initArgs=(-I "$root/scripts/build-mirror.init.gradle"); fi
gradleArgs=(assembleDebug testDebugUnitTest)
if (($# > 0)); then
    hasTask=0
    for arg in "$@"; do
        if [[ "$arg" != -* ]]; then
            hasTask=1
            break
        fi
    done
    if ((hasTask)); then
        gradleArgs=("$@")
    else
        gradleArgs=("$@" "${gradleArgs[@]}")
    fi
fi
if [[ -n ${OPTIFERRY_GRADLE:-} ]]; then
    if [[ ! -x "$OPTIFERRY_GRADLE" ]]; then
        echo "ERROR: Gradle executable does not exist: $OPTIFERRY_GRADLE" >&2
        exit 1
    fi
    "$OPTIFERRY_GRADLE" "${initArgs[@]}" "${gradleArgs[@]}"
elif [[ -x "$ANDROID_HOME/gradle-8.9/bin/gradle" ]]; then
    "$ANDROID_HOME/gradle-8.9/bin/gradle" "${initArgs[@]}" "${gradleArgs[@]}"
else
    java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "${initArgs[@]}" "${gradleArgs[@]}"
fi
mkdir -p "$root/dist"
cp "$OPTIFERRY_ANDROID_BUILD_ROOT/app/outputs/apk/debug/app-debug.apk" "$root/dist/OptiFerry-debug.apk"
