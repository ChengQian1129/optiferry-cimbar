#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK with platform 35 and build-tools 34}"
cd "$root/android-receiver"
args=()
if [[ ${OPTIFERRY_BUILD_MIRROR:-0} == 1 ]]; then args=(-I "$root/scripts/build-mirror.init.gradle"); fi
if [[ -n ${OPTIFERRY_GRADLE:-} ]]; then "$OPTIFERRY_GRADLE" "${args[@]}" assembleDebug testDebugUnitTest; else java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "${args[@]}" assembleDebug testDebugUnitTest; fi
mkdir -p "$root/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$root/dist/OptiFerry-debug.apk"
