@echo off
setlocal
if defined PYSONAR_BIN (
  "%PYSONAR_BIN%" %*
  exit /b %ERRORLEVEL%
)
if exist "%~dp0..\lib\pysonar.exe" (
  "%~dp0..\lib\pysonar.exe" %*
  exit /b %ERRORLEVEL%
)
if exist "%~dp0..\target\release\pysonar.exe" (
  "%~dp0..\target\release\pysonar.exe" %*
  exit /b %ERRORLEVEL%
)
echo PySonar2 native binary not found. Build with cargo build --release or set PYSONAR_BIN. 1>&2
exit /b 127
