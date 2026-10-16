@echo off
rem ── builds the apk on this pc (no github needed) — tools live in %USERPROFILE%\mood-build ──
cd /d "%~dp0"
set "JAVA_HOME=%USERPROFILE%\mood-build\jdk"
set "ANDROID_HOME=%USERPROFILE%\mood-build\sdk"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo the build tools are missing — ask claude to set them up again ^(jdk 17, android sdk 34, gradle 8.7^).
  pause & exit /b 1
)
call "%USERPROFILE%\mood-build\gradle\bin\gradle.bat" -p android assembleDebug --console=plain
if errorlevel 1 ( echo. & echo build failed — see the messages above. & pause & exit /b 1 )
copy /y "android\app\build\outputs\apk\debug\app-debug.apk" "mood.apk" >nul
echo.
echo done — mood.apk is in this folder. copy it to your phone and open it to install.
pause
