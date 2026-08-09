[CmdletBinding()]
param(
    [string]$SourceContainer = 'drt-ops-login-dev-postgres',
    [string]$SourceDatabase = 'drt_ops_pilot_bootstrap',
    [string]$SourceUser = 'drt_ops',
    [string]$BackupDirectory = 'D:\codex-projects\.pilot-backups'
)

$ErrorActionPreference = 'Stop'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupFileName = "$SourceDatabase-$timestamp.dump"
$sourceTempPath = "/tmp/$backupFileName"
$restoreContainer = "drt-ops-p4-restore-$timestamp"
$restoreBootstrapDatabase = 'postgres'
$restoreDatabase = 'drt_ops_restore'
$restoreUser = 'drt_restore'
$restorePassword = 'p4-isolated-restore'
$restoreTempPath = '/tmp/restore.dump'
$restoreStarted = $false
$drillPassed = $false

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($Arguments[0]) failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Invoke-PsqlScalar {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$User,
        [Parameter(Mandatory = $true)][string]$Query
    )

    $value = & docker exec $Container psql --no-psqlrc --tuples-only --no-align `
        --username $User --dbname $Database --command $Query
    if ($LASTEXITCODE -ne 0) {
        throw "Read-only query failed in container $Container"
    }
    return ($value | Out-String).Trim()
}

function Get-DatabaseCounts {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$User
    )

    return [ordered]@{
        schemaMigrations = [int64](Invoke-PsqlScalar $Container $Database $User `
            'select count(*) from flyway_schema_history where success')
        vehicles = [int64](Invoke-PsqlScalar $Container $Database $User `
            'select count(*) from vehicles')
        drivers = [int64](Invoke-PsqlScalar $Container $Database $User `
            'select count(*) from drivers')
        virtualStops = [int64](Invoke-PsqlScalar $Container $Database $User `
            'select count(*) from virtual_stops')
    }
}

function Get-OrphanCounts {
    param(
        [Parameter(Mandatory = $true)][string]$Container,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$User
    )

    return [ordered]@{
        vehicleLocationVehicle = [int64](Invoke-PsqlScalar $Container $Database $User @'
select count(*)
from vehicle_location_events event
left join vehicles vehicle on vehicle.id = event.vehicle_id
where vehicle.id is null
'@)
        taskVehicle = [int64](Invoke-PsqlScalar $Container $Database $User @'
select count(*)
from vehicle_tasks task
left join vehicles vehicle on vehicle.id = task.vehicle_id
where vehicle.id is null
'@)
        taskDriver = [int64](Invoke-PsqlScalar $Container $Database $User @'
select count(*)
from vehicle_tasks task
left join drivers driver on driver.id = task.driver_id
where driver.id is null
'@)
    }
}

$backupRoot = [System.IO.Path]::GetFullPath($BackupDirectory)
$repositoryRoot = [System.IO.Path]::GetFullPath((Resolve-Path (Join-Path $PSScriptRoot '..\..')))
if ($backupRoot.StartsWith($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup directory must be outside the Git repository: $backupRoot"
}
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$backupPath = Join-Path $backupRoot $backupFileName
$summaryPath = Join-Path $backupRoot "p4-backup-restore-summary-$timestamp.json"

try {
    $sourceState = Invoke-Docker -Arguments @(
        'inspect',
        '--format', '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}',
        $SourceContainer
    )
    if (($sourceState | Out-String).Trim() -notmatch '^running\|(healthy)?$') {
        throw "Source database container is not running and healthy: $SourceContainer"
    }
    [void](Invoke-PsqlScalar $SourceContainer $SourceDatabase $SourceUser 'select 1')
    $sourceCounts = Get-DatabaseCounts $SourceContainer $SourceDatabase $SourceUser

    Invoke-Docker -Arguments @(
        'exec', $SourceContainer,
        'pg_dump',
        '--format=custom',
        '--no-owner',
        '--no-privileges',
        '--username', $SourceUser,
        '--dbname', $SourceDatabase,
        '--file', $sourceTempPath
    ) | Out-Null
    Invoke-Docker -Arguments @('cp', "${SourceContainer}:${sourceTempPath}", $backupPath) | Out-Null

    $backupFile = Get-Item -LiteralPath $backupPath
    if ($backupFile.Length -le 0) {
        throw "Backup file is empty: $backupPath"
    }
    $sha256 = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()

    Invoke-Docker -Arguments @(
        'run',
        '--detach',
        '--name', $restoreContainer,
        '--env', "POSTGRES_DB=$restoreBootstrapDatabase",
        '--env', "POSTGRES_USER=$restoreUser",
        '--env', "POSTGRES_PASSWORD=$restorePassword",
        'postgis/postgis:16-3.5'
    ) | Out-Null
    $restoreStarted = $true

    $ready = $false
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        & docker exec $restoreContainer pg_isready -h 127.0.0.1 -U $restoreUser -d $restoreBootstrapDatabase | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        throw "Restore database was not ready within 60 seconds: $restoreContainer"
    }

    Invoke-Docker -Arguments @(
        'exec', $restoreContainer,
        'createdb',
        '--username', $restoreUser,
        '--owner', $restoreUser,
        '--template', 'template0',
        $restoreDatabase
    ) | Out-Null
    Invoke-Docker -Arguments @('cp', $backupPath, "${restoreContainer}:${restoreTempPath}") | Out-Null
    Invoke-Docker -Arguments @(
        'exec', $restoreContainer,
        'pg_restore',
        '--exit-on-error',
        '--clean',
        '--if-exists',
        '--no-owner',
        '--no-privileges',
        '--username', $restoreUser,
        '--dbname', $restoreDatabase,
        $restoreTempPath
    ) | Out-Null

    $restoredCounts = Get-DatabaseCounts $restoreContainer $restoreDatabase $restoreUser
    $orphanCounts = Get-OrphanCounts $restoreContainer $restoreDatabase $restoreUser
    $postgisVersion = Invoke-PsqlScalar $restoreContainer $restoreDatabase $restoreUser `
        'select PostGIS_Version()'
    $locationQueryResult = Invoke-PsqlScalar $restoreContainer $restoreDatabase $restoreUser @'
select count(*)
from vehicles
where current_location is not null
  and ST_X(current_location::geometry) is not null
  and ST_Y(current_location::geometry) is not null
'@
    $queryChecksPassed = -not [string]::IsNullOrWhiteSpace($postgisVersion) `
        -and [int64]$locationQueryResult -ge 0

    $summary = [ordered]@{
        backupFileName = $backupFileName
        backupBytes = [int64]$backupFile.Length
        sha256 = $sha256
        restoreContainer = $restoreContainer
        source = $sourceCounts
        restored = $restoredCounts
        orphanCounts = $orphanCounts
        queryChecksPassed = $queryChecksPassed
        passed = $true
    }
    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding utf8

    & (Join-Path $PSScriptRoot 'Test-BackupRestoreResult.ps1') -SummaryPath $summaryPath
    if ($LASTEXITCODE -ne 0) {
        throw "Backup restore summary validation failed with exit code $LASTEXITCODE"
    }
    $drillPassed = $true

    Write-Output "P4_BACKUP_RESTORE_RESULT backupFileName=$backupFileName backupBytes=$($backupFile.Length) sha256=$sha256 summaryPath=$summaryPath"
}
finally {
    & docker exec $SourceContainer rm -f $sourceTempPath 2>$null
    if ($drillPassed -and $restoreStarted) {
        Invoke-Docker -Arguments @('rm', '--force', $restoreContainer) | Out-Null
        Write-Output "P4_BACKUP_RESTORE_CONTAINER_CLEANED name=$restoreContainer"
    }
    elseif ($restoreStarted) {
        Write-Warning "Backup restore drill failed; container retained for diagnosis: $restoreContainer"
    }
}
