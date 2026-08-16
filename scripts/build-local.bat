@echo off
rem Local one-click build for OpenCode Android APK
rem Usage: scripts\build-local.bat [gradle args...]
setlocal

set "ROOT=%~dp0.."
set "LOCAL=%ROOT%\.local"
set "JAVA_HOME=%LOCAL%\jdk17\jdk-17.0.20+8"
set "ANDROID_HOME=%LOCAL%\android-sdk"
set "GRADLE=%LOCAL%\gradle-8.7\gradle-8.7\bin\gradle.bat"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK not found at %JAVA_HOME%
    echo Please place JDK 17 under .local\jdk17\
    exit /b 1
)
if not exist "%ANDROID_HOME%\platforms\android-34" (
    echo ERROR: Android SDK platform-34 not found at %ANDROID_HOME%
    exit /b 1
)

rem System proxy (Clash etc) for downloading opencode binary. Comment out if direct connection works.
set "GRADLE_OPTS=-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"

echo === Building APK (downloads opencode binary on first run) ===
"%GRADLE%" -p "%ROOT%\android-app" assembleDebug --no-daemon %*
if errorlevel 1 exit /b 1

set "APK=%ROOT%\android-app\app\build\outputs\apk\debug\app-debug.apk"
echo.
echo === DONE ===
echo APK: %APK%
echo Install: adb install -r "%APK%"
