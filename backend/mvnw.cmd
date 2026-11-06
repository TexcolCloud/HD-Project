@echo off
setlocal
set "MAVEN_BASE=%~dp0.mvn\apache-maven-3.9.12"
if not exist "%MAVEN_BASE%\bin\mvn.cmd" powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0.mvn\bootstrap.ps1"
if not exist "%MAVEN_BASE%\bin\mvn.cmd" exit /b 1
call "%MAVEN_BASE%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
