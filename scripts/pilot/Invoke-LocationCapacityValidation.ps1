[CmdletBinding()]
param(
    [string]$ContainerName = 'drt-ops-p4-location-capacity',
    [int]$HostPort = 15434,
    [string]$DatabaseName = 'drt_ops_capacity',
    [string]$DatabaseUser = 'drt_ops',
    [string]$DatabasePassword = 'drt_ops',
    [string]$MavenCommand = 'D:\codex-projects\.worktrees\drt-ops-mvp\.tools\apache-maven-3.9.11\bin\mvn.cmd'
)

$ErrorActionPreference = 'Stop'
$validationPassed = $false

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments[0]) failed with exit code $LASTEXITCODE"
    }
}

if (-not (Test-Path -LiteralPath $MavenCommand -PathType Leaf)) {
    throw "Maven command was not found: $MavenCommand"
}

$existingContainer = docker ps -a --filter "name=^/$ContainerName$" --format '{{.Names}}'
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to query Docker container state'
}
if ($existingContainer -eq $ContainerName) {
    throw "Refusing to overwrite existing container: $ContainerName"
}

try {
    Invoke-Docker -Arguments @(
        'run',
        '--detach',
        '--name', $ContainerName,
        '--publish', "127.0.0.1:${HostPort}:5432",
        '--env', "POSTGRES_DB=$DatabaseName",
        '--env', "POSTGRES_USER=$DatabaseUser",
        '--env', "POSTGRES_PASSWORD=$DatabasePassword",
        'postgis/postgis:16-3.5'
    )

    $ready = $false
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        & docker exec $ContainerName pg_isready -h 127.0.0.1 -U $DatabaseUser -d $DatabaseName | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        throw "Capacity database was not ready within 60 seconds: $ContainerName"
    }

    $jdbcUrl = "jdbc:postgresql://127.0.0.1:${HostPort}/$DatabaseName"
    & $MavenCommand `
        -q `
        -pl apps/api `
        '-Dtest=VehicleLocationCapacityIntegrationTest' `
        '-Ddrt.integration.capacity=true' `
        "-Ddrt.integration.postgis-url=$jdbcUrl" `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "Capacity validation failed with Maven exit code $LASTEXITCODE"
    }

    $validationPassed = $true
}
finally {
    if ($validationPassed) {
        Invoke-Docker -Arguments @('rm', '--force', $ContainerName)
        Write-Output "P4_CAPACITY_CONTAINER_CLEANED name=$ContainerName"
    }
    else {
        Write-Warning "Capacity validation failed; container retained for diagnosis: $ContainerName"
    }
}
