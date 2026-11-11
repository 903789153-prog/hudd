@echo off
REM ============================================================
REM  KingsHUD 本地一键构建（Windows）
REM  前置：JDK 17 + Android SDK（Android Studio 装好即可）
REM ============================================================
setlocal

where java >nul 2>nul
if errorlevel 1 (
    echo [!] 没找到 java，先装 JDK 17
    exit /b 1
)

if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
        set ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk%
    ) else (
        echo [!] 没找到 Android SDK，设 ANDROID_HOME 后重试
        exit /b 1
    )
)

echo [*] ANDROID_HOME=%ANDROID_HOME%

if not exist local.properties (
    echo sdk.dir=%ANDROID_HOME:\=/%> local.properties
)

echo [*] 构建 release...
set GRADLE_CMD=gradle
if exist gradlew.bat set GRADLE_CMD=gradlew.bat
call %GRADLE_CMD% assembleRelease
if errorlevel 1 (
    echo [*] release 失败，改出 debug 包
    call %GRADLE_CMD% assembleDebug
)

echo.
echo [*] 产物：
dir /s /b app\build\outputs\apk\*.apk

echo.
echo [*] 安装到手机（先开 USB 调试）：
for /r app\build\outputs\apk %%f in (*.apk) do echo     adb install -r "%%f"

endlocal
