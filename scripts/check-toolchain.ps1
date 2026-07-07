$ErrorActionPreference = "Stop"

function Test-RequiredCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}

Test-RequiredCommand "java"
Test-RequiredCommand "docker"
Test-RequiredCommand "mise"

Write-Host "Java:"
java -version

Write-Host "Docker:"
docker --version
docker compose version

Write-Host "mise:"
mise --version
