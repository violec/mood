@echo off
rem ── double-click after claude edits: commits + pushes + triggers apk build ──
cd /d "%~dp0"
git add -A
git commit -m "mood update %date% %time%"
git push
echo.
echo pushed — github actions is building the apk (repo - Actions tab).
pause
