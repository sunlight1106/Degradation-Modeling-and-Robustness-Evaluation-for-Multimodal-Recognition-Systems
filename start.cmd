@echo off
cd /d "%~dp0"
where pythonw.exe >nul 2>nul
if not errorlevel 1 (
  start "" /b pythonw.exe "%~dp0scripts\startup.py"
  exit /b
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1"
if errorlevel 1 pause
