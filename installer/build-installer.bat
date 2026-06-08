@echo off
setlocal EnableExtensions

set "ISCC_EXE=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
set "ISS_FILE=%~dp0DocumentConverter.iss"

if not exist "%ISCC_EXE%" (
  echo [ERROR] ISCC.exe not found: %ISCC_EXE%
  echo Install Inno Setup 6 first: https://jrsoftware.org/isdl.php
  pause
  exit /b 1
)

if not exist "%ISS_FILE%" (
  echo [ERROR] Installer script not found: %ISS_FILE%
  pause
  exit /b 1
)

echo [INFO] Building installer...
"%ISCC_EXE%" "%ISS_FILE%"
if errorlevel 1 (
  echo [ERROR] Build failed.
  pause
  exit /b 1
)

echo [INFO] Build completed.
pause
endlocal
