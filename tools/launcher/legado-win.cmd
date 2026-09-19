@echo off
rem legado Reader for Windows - double-click launcher.
rem Real logic lives in launcher.ps1 (waits for the server, opens the app window,
rem and reclaims the process when the window closes).
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%launcher.ps1" %*
if errorlevel 1 (
  echo.
  echo Startup failed. Press any key to close.
  pause >nul
)
endlocal
