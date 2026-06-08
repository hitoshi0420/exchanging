@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
set "JAVA_HOME=%SCRIPT_DIR%jdk"
if not exist "%JAVA_HOME%\bin\java.exe" (
  for /d %%D in ("%SCRIPT_DIR%jdk*") do (
    if exist "%%~fD\bin\java.exe" (
      set "JAVA_HOME=%%~fD"
      goto :jdk_found
    )
  )
)
:jdk_found
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "JAR_PATH=%SCRIPT_DIR%document-converter-1.0.0.jar"
set "APP_URL=http://localhost:3000/"
set "LOG_DIR=%SCRIPT_DIR%logs"

if not exist "%JAVA_EXE%" (
  echo [ERROR] 未找到 Java: %JAVA_EXE%
  pause
  exit /b 1
)

if not exist "%JAR_PATH%" (
  echo [ERROR] 未找到 JAR: %JAR_PATH%
  echo 请将 document-converter-1.0.0.jar 放在 start.bat 同目录后再运行
  pause
  exit /b 1
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo [INFO] 使用 Java: %JAVA_EXE%
"%JAVA_EXE%" -version 2>&1
echo.

echo [INFO] 正在启动应用（日志输出到 %LOG_DIR%\app.log）...
start "DocumentConverter" /MIN "%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR_PATH%" >> "%LOG_DIR%\app.log" 2>&1

echo [INFO] 等待应用就绪...
set RETRIES=0
:wait_loop
timeout /t 2 /nobreak >nul
set /a RETRIES+=1
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -Uri '%APP_URL%api/health' -UseBasicParsing -TimeoutSec 2; exit 0 } catch { exit 1 }" >nul 2>&1
if %ERRORLEVEL% EQU 0 goto :ready
if %RETRIES% LSS 15 goto :wait_loop

echo [WARN] 应用启动超时，仍将打开页面。
echo [WARN] 请查看日志: %LOG_DIR%\app.log

:ready
start "" "%APP_URL%"
echo [INFO] 已打开页面: %APP_URL%
echo [INFO] 应用在后台运行，关闭此窗口不影响应用。

endlocal
