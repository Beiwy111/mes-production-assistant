@echo off
setlocal
title Production Assistant - Local Startup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend\assistant\deploy\Start-AssistantLocal.ps1"
set "assistant_exit=%ERRORLEVEL%"
echo.
if not "%assistant_exit%"=="0" echo Startup did not complete. Read the message above and run again after resolving it.
pause
exit /b %assistant_exit%
