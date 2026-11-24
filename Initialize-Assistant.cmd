@echo off
setlocal
title MES Assistant - First-time Setup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend\assistant\deploy\Initialize-Assistant.ps1"
set "setup_result=%ERRORLEVEL%"
echo.
if not "%setup_result%"=="0" echo Setup did not finish. Resolve the reported step and rerun this launcher.
pause
exit /b %setup_result%
