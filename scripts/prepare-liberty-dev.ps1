$ErrorActionPreference = "Stop"

$postgresqlVersion = if ($env:POSTGRESQL_VERSION) { $env:POSTGRESQL_VERSION } else { "42.7.13" }
$resourcesDirectory = Join-Path $PSScriptRoot "..\src\main\liberty\config\resources"
New-Item -ItemType Directory -Force -Path $resourcesDirectory | Out-Null

& "$PSScriptRoot\..\mvnw.cmd" -q dependency:copy "-Dartifact=org.postgresql:postgresql:$postgresqlVersion" "-DoutputDirectory=$resourcesDirectory"
