[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SummaryPath
)

$ErrorActionPreference = 'Stop'

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

$resolvedPath = Resolve-Path -LiteralPath $SummaryPath -ErrorAction Stop
$rawSummary = Get-Content -LiteralPath $resolvedPath -Raw
Assert-Condition ($rawSummary -notmatch '(?i)"[^"]*(password|token|phone|contact)[^"]*"\s*:') `
    'Summary contains a forbidden sensitive field name'
Assert-Condition ($rawSummary -notmatch '(?i)jdbc:') `
    'Summary contains a forbidden JDBC connection string'

$summary = $rawSummary | ConvertFrom-Json
Assert-Condition ($null -ne $summary.backupFileName -and $summary.backupFileName -match '\.dump$') `
    'backupFileName must identify a dump file'
Assert-Condition ($null -ne $summary.backupBytes -and [int64]$summary.backupBytes -gt 0) `
    'backupBytes must be greater than zero'
Assert-Condition ($null -ne $summary.sha256 -and $summary.sha256 -match '^[0-9a-fA-F]{64}$') `
    'sha256 must contain exactly 64 hexadecimal characters'
Assert-Condition ($null -ne $summary.restoreContainer -and -not [string]::IsNullOrWhiteSpace($summary.restoreContainer)) `
    'restoreContainer is required'

foreach ($field in @('schemaMigrations', 'vehicles', 'drivers', 'virtualStops')) {
    Assert-Condition ($null -ne $summary.source.$field) "source.$field is required"
    Assert-Condition ($null -ne $summary.restored.$field) "restored.$field is required"
    Assert-Condition ([int64]$summary.source.$field -eq [int64]$summary.restored.$field) `
        "source.$field must equal restored.$field"
}

foreach ($field in @('vehicleLocationVehicle', 'taskVehicle', 'taskDriver')) {
    Assert-Condition ($null -ne $summary.orphanCounts.$field) "orphanCounts.$field is required"
    Assert-Condition ([int64]$summary.orphanCounts.$field -eq 0) "orphanCounts.$field must be zero"
}

Assert-Condition ($summary.queryChecksPassed -eq $true) 'queryChecksPassed must be true'
Assert-Condition ($summary.passed -eq $true) 'passed must be true'

Write-Output "P4_BACKUP_RESTORE_SUMMARY_ACCEPTED path=$resolvedPath"
