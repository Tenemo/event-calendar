@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "MAVEN_VERSION=3.9.11"
set "MAVEN_DISTRIBUTION_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"
set "MAVEN_BASE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN_HOME=%MAVEN_BASE%\apache-maven-%MAVEN_VERSION%"

where mise >nul 2>nul
if not errorlevel 1 (
    for /f "usebackq delims=" %%i in (`mise where java 2^>nul`) do set "MISE_JAVA_HOME=%%i"
    if defined MISE_JAVA_HOME (
        if exist "!MISE_JAVA_HOME!\bin\java.exe" (
            set "JAVA_HOME=!MISE_JAVA_HOME!"
            set "PATH=!JAVA_HOME!\bin;!PATH!"
        )
    )
)

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $mavenBase = '%MAVEN_BASE%'; $mavenHome = '%MAVEN_HOME%'; $downloadUrl = '%MAVEN_DISTRIBUTION_URL%'; New-Item -ItemType Directory -Force -Path $mavenBase | Out-Null; $archivePath = Join-Path $env:TEMP 'apache-maven-%MAVEN_VERSION%-bin.zip'; Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath; Expand-Archive -Path $archivePath -DestinationPath $mavenBase -Force; if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) { throw 'Maven distribution did not expand to the expected path.' }"
    if errorlevel 1 exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
