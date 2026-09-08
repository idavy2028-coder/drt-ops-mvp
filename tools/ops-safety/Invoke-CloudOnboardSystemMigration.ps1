[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('DryRun', 'ApplyV19', 'ContractCheck')]
    [string] $Mode,

    [Parameter(Mandatory)]
    [string] $ApiBaseUri,

    [string] $ManifestPath = (Join-Path $PSScriptRoot 'onboard-system-migration-manifest.json'),

    [string] $PrivateSourceRoot,

    [Parameter(Mandatory)]
    [switch] $GatewayStopped,

    [string] $ApiTokenEnvironmentVariable = 'P6_2_MIGRATION_API_TOKEN'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$libraryPath = Join-Path $PSScriptRoot 'cloud-onboard-system-migration-lib.ps1'
try {
    . $libraryPath
} catch {
    [Console]::Out.WriteLine(
        ('alias=batch phase={0} step=load-library httpStatus=0 version=0 warnings=LIBRARY_LOAD_FAILED fileSha256=NONE' -f $Mode))
    exit 1
}

$plan = $null
$planLoaded = $false
$manifestHash = 'NONE'

try {
    $uri = New-Object System.Uri($ApiBaseUri, [System.UriKind]::Absolute)
    Assert-LoopbackApiTarget -ApiBaseUri $uri | Out-Null
    Assert-MigrationEnvironment -GatewayStopped ([bool] $GatewayStopped) | Out-Null

    if ([string]::IsNullOrWhiteSpace($PrivateSourceRoot)) {
        $PrivateSourceRoot = [Environment]::GetEnvironmentVariable('P6_2_PRIVATE_SOURCE_ROOT', 'Process')
    }
    if ([string]::IsNullOrWhiteSpace($PrivateSourceRoot)) { throw 'PRIVATE_SOURCE_ROOT_REQUIRED' }

    $plan = Read-StrictUtf8Json -Path $ManifestPath
    $planLoaded = $true
    Test-OnboardMigrationPlan -Plan $plan -PrivateSourceRoot $PrivateSourceRoot | Out-Null

    $headers = @{}
    $token = [Environment]::GetEnvironmentVariable($ApiTokenEnvironmentVariable, 'Process')
    if (-not [string]::IsNullOrWhiteSpace($token)) {
        $headers['Authorization'] = 'Bearer ' + $token
    }

    $statePersister = {
        param($updatedPlan)
        Write-StrictUtf8Json -Path $ManifestPath -Value $updatedPlan
    }.GetNewClosure()
    $results = @(Invoke-OnboardMigration -Mode $Mode -Plan $plan -PrivateSourceRoot $PrivateSourceRoot `
        -ApiBaseUri $uri -Headers $headers -StatePersister $statePersister)
    Write-StrictUtf8Json -Path $ManifestPath -Value $plan
    $manifestHash = Get-FileSha256 -Path $ManifestPath
    foreach ($result in $results) {
        $result.fileSha256 = $manifestHash
        Write-Output (Format-OnboardMigrationResult -Result $result)
    }
    Write-Output ('SAFE_RECORDS=' + @($plan.records).Count)
    Write-Output ('MANIFEST_SHA256=' + $manifestHash)
    exit 0
} catch {
    if ($planLoaded -and $null -ne $plan) {
        try {
            Write-StrictUtf8Json -Path $ManifestPath -Value $plan
            $manifestHash = Get-FileSha256 -Path $ManifestPath
        } catch {
            $manifestHash = 'NONE'
        }
    } elseif (Test-Path -LiteralPath $ManifestPath -PathType Leaf) {
        try { $manifestHash = Get-FileSha256 -Path $ManifestPath } catch { $manifestHash = 'NONE' }
    }
    $match = [regex]::Match([string] $_.Exception.Message, '[A-Z][A-Z0-9_]{3,}')
    $code = if ($match.Success) { $match.Value } else { 'UNSAFE_INTERNAL_ERROR' }
    $failure = [pscustomobject]@{
        safeAlias = 'batch'
        phase = $Mode
        step = 'failed'
        httpStatus = 0
        version = 0
        warnings = @($code)
        fileSha256 = $manifestHash
    }
    Write-Output (Format-OnboardMigrationResult -Result $failure)
    exit 1
}
