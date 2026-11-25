@echo off
setlocal
set "MAVEN_BASE=%~dp0.mvn\apache-maven-3.9.12"
if not exist "%MAVEN_BASE%\bin\mvn.cmd" (
    rem Let Windows PowerShell load its own modules when invoked from PowerShell 7.
    setlocal
    set "PSModulePath="
    "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0.mvn\bootstrap.ps1"
    if errorlevel 1 exit /b 1
    endlocal
)
if not exist "%MAVEN_BASE%\bin\mvn.cmd" exit /b 1
call "%MAVEN_BASE%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
