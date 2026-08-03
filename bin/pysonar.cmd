@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
if defined PYSONAR_JAR (
  set "JAR_PATH=%PYSONAR_JAR%"
) else if exist "%SCRIPT_DIR%..\lib\pysonar.jar" (
  set "JAR_PATH=%SCRIPT_DIR%..\lib\pysonar.jar"
) else (
  set "JAR_PATH=%SCRIPT_DIR%..\target\pysonar-3.3.0.jar"
)
if not exist "%JAR_PATH%" (
  echo PySonar2 JAR not found. Build with "mvn package" or set PYSONAR_JAR. 1>&2
  exit /b 127
)
java -jar "%JAR_PATH%" %*
