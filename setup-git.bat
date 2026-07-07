@echo off
rem ── one-time setup (safe to re-run): connects this folder to your github repo ──
cd /d "%~dp0"

git init >nul 2>&1

rem identity for commits (stored only for this repo)
git config user.name "Can"
git config user.email "gunaycan073@gmail.com"

rem point origin at the repo (replaces any old value)
git remote remove origin >nul 2>&1
git remote add origin https://github.com/violec/mood.git

rem make sure we're on a branch called main
git checkout -B main

git add -A
git commit -m "mood: local baseline"

rem local folder is the source of truth:
git push -u origin main --force

echo.
echo done — from now on just double-click push.bat after edits.
pause
