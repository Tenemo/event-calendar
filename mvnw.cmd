@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PROPERTIES_FILE=%~dp0.mvn\wrapper\maven-wrapper.properties"

if not exist "%PROPERTIES_FILE%" (
    echo Missing Maven wrapper properties: %PROPERTIES_FILE% 1>&2
    exit /b 1
)

set "MAVEN_DISTRIBUTION_URL="
set "MAVEN_DISTRIBUTION_SHA512="
for /f "usebackq tokens=1,* delims==" %%a in ("%PROPERTIES_FILE%") do (
    if /i "%%a"=="distributionUrl" set "MAVEN_DISTRIBUTION_URL=%%b"
    if /i "%%a"=="distributionSha512Sum" set "MAVEN_DISTRIBUTION_SHA512=%%b"
)

if not defined MAVEN_DISTRIBUTION_URL (
    echo Could not determine the Maven distribution from %PROPERTIES_FILE% 1>&2
    exit /b 1
)

set "MAVEN_ARCHIVE_NAME=%MAVEN_DISTRIBUTION_URL:*apache-maven-=%"
set "MAVEN_VERSION=%MAVEN_ARCHIVE_NAME:-bin.zip=%"

if "%MAVEN_ARCHIVE_NAME%"=="%MAVEN_DISTRIBUTION_URL%" (
    echo Could not determine the Maven version from %PROPERTIES_FILE% 1>&2
    exit /b 1
)

if not defined MAVEN_DISTRIBUTION_SHA512 (
    echo Missing Maven distribution SHA-512 checksum in %PROPERTIES_FILE% 1>&2
    exit /b 1
)
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
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $mavenBase = '%MAVEN_BASE%'; $mavenHome = '%MAVEN_HOME%'; $downloadUrl = '%MAVEN_DISTRIBUTION_URL%'; $expectedHash = '%MAVEN_DISTRIBUTION_SHA512%'.ToLowerInvariant(); New-Item -ItemType Directory -Force -Path $mavenBase | Out-Null; $archivePath = Join-Path $env:TEMP 'apache-maven-%MAVEN_VERSION%-bin.zip'; Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath; $actualHash = (Get-FileHash -Algorithm SHA512 -Path $archivePath).Hash.ToLowerInvariant(); if ($actualHash -ne $expectedHash) { throw ('Maven distribution checksum mismatch. Expected ' + $expectedHash + ' but got ' + $actualHash + '.') }; Expand-Archive -Path $archivePath -DestinationPath $mavenBase -Force; if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) { throw 'Maven distribution did not expand to the expected path.' }"
    if errorlevel 1 exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
