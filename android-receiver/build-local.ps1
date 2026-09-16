param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]] $GradleArgs = @("assembleDebug")
)

$root = Split-Path -Parent $PSScriptRoot
if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
  throw "WSL is required. This entry point intentionally avoids C: drive build caches."
}
$wslRoot = (& wsl.exe wslpath -a -- $root).Trim()
$argsText = ($GradleArgs | ForEach-Object { "'" + ($_ -replace "'", "'\\''") + "'" }) -join " "
$command = "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export ANDROID_HOME=/mnt/f/optiferry-storage/android-sdk; export ANDROID_SDK_ROOT=/mnt/f/optiferry-storage/android-sdk; export OPTIFERRY_ANDROID_BUILD_ROOT=`$HOME/.cache/optiferry-android-build; export GRADLE_USER_HOME=`$HOME/.gradle; export ANDROID_USER_HOME=`$HOME/.android; export OPTIFERRY_GRADLE=/mnt/f/optiferry-storage/android-sdk/gradle-8.9/bin/gradle; cd '$wslRoot/android-receiver'; bash '$wslRoot/scripts/build-android.sh' $argsText"
& wsl.exe bash -lc $command
exit $LASTEXITCODE
