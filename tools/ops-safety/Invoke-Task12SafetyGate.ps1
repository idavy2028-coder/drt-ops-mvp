[CmdletBinding()]
param(
    [string] $Mode = 'DryRunCleanup',
    [string] $ExpectedPath = '',
    [string] $ResultsPath = '',
    [string] $ReceiptPath = '',
    [string] $TimeoutMilliseconds = '10000'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Get-Task12DisplayMode {
    param([AllowEmptyString()] [string] $Value)
    if ($Value -cin @('VerifyAcceptance','DryRunCleanup','ApplyCleanup')) { return $Value }
    return 'Invalid'
}

function Write-Task12Failure {
    param([Parameter(Mandatory)] [string] $DisplayMode, [Parameter(Mandatory)] [string] $Code, [string] $Completed = 'NONE')
    if ($Completed -cnotin @('NONE','CONTAINER_REMOVED')) { $Completed = 'NONE' }
    [Console]::Out.WriteLine(('TASK12_SAFETY_STATUS=FAIL MODE={0} CODE={1} COMPLETED={2}' -f $DisplayMode, $Code, $Completed))
}

function Get-Task12CliFailureCode {
    param([AllowEmptyString()] [string] $Message)
    if ($Message -match '^TASK12_ACCEPTANCE_|^TASK12_ALIAS_|^TASK12_RESULT_|^TASK12_TERMINAL_|^TASK12_UUID_|^TASK12_VEHICLE_|^TASK12_RECORD_|^TASK12_FIELD_') {
        return 'ACCEPTANCE_REJECTED'
    }
    if ($Message -match '^TASK12_RECEIPT_|^TASK12_RUN_ID_|^TASK12_OWNER_|^TASK12_CONTAINER_ID_INVALID|^TASK12_VOLUME_CREATED_AT_INVALID|^TASK12_DOCKER_ENDPOINT_INVALID') {
        return 'RECEIPT_REJECTED'
    }
    if ($Message -ceq 'TASK12_JSON_INPUT_INVALID') { return 'JSON_INPUT_INVALID' }
    if ($Message -match '^TASK12_DOCKER_') { return 'DOCKER_OPERATION_FAILED' }
    if ($Message -match '^TASK12_CONTAINER_|^TASK12_VOLUME_|^TASK12_INVENTORY_') { return 'CLEANUP_REJECTED' }
    return 'INTERNAL_ERROR'
}

function Read-Task12ControlledJson {
    param([Parameter(Mandatory)] [AllowEmptyString()] [string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'TASK12_JSON_INPUT_INVALID' }
    try {
        $file = Get-Item -LiteralPath $Path -ErrorAction Stop
        if ($file.PSIsContainer -or $file.Length -lt 1 -or $file.Length -gt 1048576) { throw 'TASK12_JSON_INPUT_INVALID' }
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $encoding = New-Object System.Text.UTF8Encoding($false, $true)
        $json = $encoding.GetString($bytes)
        if ($json.Length -gt 0 -and $json[0] -eq [char] 0xFEFF) { $json = $json.Substring(1) }
        return ($json | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        throw 'TASK12_JSON_INPUT_INVALID'
    }
}

$displayMode = Get-Task12DisplayMode -Value $Mode
if ($displayMode -ceq 'Invalid') {
    Write-Task12Failure -DisplayMode $displayMode -Code 'MODE_INVALID'
    exit 1
}

$timeout = 0
if (-not [int]::TryParse($TimeoutMilliseconds, [ref] $timeout) -or $timeout -lt 100 -or $timeout -gt 60000) {
    Write-Task12Failure -DisplayMode $displayMode -Code 'TIMEOUT_INVALID'
    exit 1
}

$libraryPath = Join-Path $PSScriptRoot 'task12-safety-lib.ps1'
$adapterPath = Join-Path $PSScriptRoot 'task12-docker-adapter.ps1'
try {
    . $libraryPath
    . $adapterPath
} catch {
    Write-Task12Failure -DisplayMode $displayMode -Code 'LIBRARY_LOAD_FAILED'
    exit 1
}

try {
    if ($Mode -ceq 'VerifyAcceptance') {
        $expected = @(Read-Task12ControlledJson -Path $ExpectedPath)
        $results = @(Read-Task12ControlledJson -Path $ResultsPath)
        $acceptance = Assert-Task12Acceptance -Expected $expected -Results $results
        [Console]::Out.WriteLine(('TASK12_SAFETY_STATUS=PASS MODE=VerifyAcceptance ACCEPTED={0}' -f $acceptance.Count))
        exit 0
    }

    $receipt = Read-Task12ControlledJson -Path $ReceiptPath
    $inventoryProvider = {
        param([string] $Stage)
        Get-Task12DockerInventory -DockerExecutablePath 'docker.exe' -Endpoint ([string] $receipt.DockerEndpoint) `
            -TimeoutMilliseconds $timeout
    }.GetNewClosure()
    $operationExecutor = {
        param([string] $Operation, [string] $Target, [string] $Endpoint)
        # 固定动作映射，JSON不能选命令。
        if ($Operation -ceq 'RemoveContainer') {
            Remove-Task12DockerContainer -DockerExecutablePath 'docker.exe' -Endpoint $Endpoint `
                -ContainerId $Target -TimeoutMilliseconds $timeout
        } elseif ($Operation -ceq 'RemoveVolume') {
            Remove-Task12DockerVolume -DockerExecutablePath 'docker.exe' -Endpoint $Endpoint `
                -VolumeName $Target -TimeoutMilliseconds $timeout
        } else {
            throw 'TASK12_OPERATION_INVALID'
        }
    }.GetNewClosure()
    $cleanup = Invoke-Task12Cleanup -Mode $Mode -Receipt $receipt -InventoryProvider $inventoryProvider `
        -OperationExecutor $operationExecutor
    if ($Mode -ceq 'DryRunCleanup') {
        [Console]::Out.WriteLine(('TASK12_SAFETY_STATUS=PASS MODE=DryRunCleanup CONTAINERS={0} VOLUMES={1} ACTIONS=0' -f
                $cleanup.Containers, $cleanup.Volumes))
    } else {
        [Console]::Out.WriteLine(('TASK12_SAFETY_STATUS=PASS MODE=ApplyCleanup CONTAINERS={0} VOLUMES={1} ACTIONS={2} COMPLETED={3}' -f
                $cleanup.Containers, $cleanup.Volumes, $cleanup.Actions, $cleanup.Completed))
    }
    exit 0
} catch {
    $completed = 'NONE'
    try {
        if ($null -ne $_.Exception.Data['Task12Completed']) { $completed = [string] $_.Exception.Data['Task12Completed'] }
    } catch { $completed = 'NONE' }
    Write-Task12Failure -DisplayMode $displayMode -Code (Get-Task12CliFailureCode -Message ([string] $_.Exception.Message)) -Completed $completed
    exit 1
}
