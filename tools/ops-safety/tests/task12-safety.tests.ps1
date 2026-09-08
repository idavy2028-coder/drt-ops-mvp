[CmdletBinding()]
param([string] $NameFilter = '')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$script:Total = 0
$script:Passed = 0
$script:Failures = New-Object 'System.Collections.Generic.List[string]'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$opsRoot = Split-Path -Parent $here
$libraryPath = Join-Path $opsRoot 'task12-safety-lib.ps1'

function Invoke-TestCase {
    param([Parameter(Mandatory)] [string] $Name, [Parameter(Mandatory)] [scriptblock] $Body)
    if (-not [string]::IsNullOrWhiteSpace($NameFilter) -and $Name -notlike $NameFilter) { return }
    $script:Total++
    try {
        & $Body
        $script:Passed++
    } catch {
        $match = [regex]::Match([string] $_.Exception.Message, '[A-Z][A-Z0-9_]{3,}')
        $code = if ($match.Success) { $match.Value } else { 'UNSAFE_TEST_ERROR' }
        $script:Failures.Add($Name + ':' + $code)
    }
}

function Assert-True {
    param([Parameter(Mandatory)] [bool] $Condition, [Parameter(Mandatory)] [string] $Code)
    if (-not $Condition) { throw $Code }
}

function Assert-ThrowsTask12 {
    param([Parameter(Mandatory)] [scriptblock] $Body)
    try { & $Body | Out-Null } catch {
        if ([string] $_.Exception.Message -match '^TASK12_[A-Z0-9_]+$') { return }
        throw 'UNSAFE_ERROR_CODE'
    }
    throw 'EXPECTED_REJECTION_MISSING'
}

function New-AcceptanceFixture {
    $expected = @(
        [pscustomobject]@{ SafeAlias='terminal-01'; TerminalId='10000000-0000-0000-0000-000000000001'; VehicleId='20000000-0000-0000-0000-000000000001'; OnboardSystemId='30000000-0000-0000-0000-000000000001' },
        [pscustomobject]@{ SafeAlias='terminal-02'; TerminalId='10000000-0000-0000-0000-000000000002'; VehicleId='20000000-0000-0000-0000-000000000002'; OnboardSystemId='30000000-0000-0000-0000-000000000002' },
        [pscustomobject]@{ SafeAlias='terminal-03'; TerminalId='10000000-0000-0000-0000-000000000003'; VehicleId='20000000-0000-0000-0000-000000000003'; OnboardSystemId='30000000-0000-0000-0000-000000000003' },
        [pscustomobject]@{ SafeAlias='terminal-04'; TerminalId='10000000-0000-0000-0000-000000000004'; VehicleId='20000000-0000-0000-0000-000000000004'; OnboardSystemId='30000000-0000-0000-0000-000000000004' }
    )
    $results = @($expected | ForEach-Object {
        [pscustomobject]@{
            SafeAlias = $_.SafeAlias
            TerminalId = $_.TerminalId
            VehicleId = $_.VehicleId
            OnboardSystemId = $_.OnboardSystemId
            Status = 'PASS'
        }
    })
    return [pscustomobject]@{ Expected=$expected; Results=$results }
}

function Copy-Records {
    param([Parameter(Mandatory)] [object[]] $Records)
    return @($Records | ForEach-Object {
        $copy = [ordered]@{}
        foreach ($property in $_.PSObject.Properties) { $copy[$property.Name] = $property.Value }
        [pscustomobject] $copy
    })
}

function Copy-SyntheticObject {
    param([Parameter(Mandatory)] $Value)
    return ($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json)
}

function New-CleanupFixture {
    $runId = '20260905-a1b2c3'
    $owner = '0123456789abcdef0123456789abcdef'
    $containerId = ('a' * 64)
    $containerName = 'drt-p6-2-task12-pg-' + $runId
    $volumeName = 'drt-p6-2-task12-pgdata-' + $runId
    $createdAt = '2026-09-05T01:02:03.0000000Z'
    $labels = [pscustomobject]@{
        'com.drt.task' = 'p6-2-task12'
        'com.drt.run-id' = $runId
        'com.drt.owner' = $owner
    }
    $receipt = [pscustomobject]@{
        SchemaVersion = 1
        RunId = $runId
        OwnerNonce = $owner
        DockerEndpoint = 'npipe:////./pipe/dockerDesktopLinuxEngine'
        ContainerId = $containerId
        VolumeCreatedAt = $createdAt
    }
    $container = [pscustomobject]@{
        Id = $containerId
        Name = $containerName
        Labels = $labels
        State = 'exited'
        Mounts = @([pscustomobject]@{
            Type='volume'; Name=$volumeName;
            Source=('/var/lib/docker/volumes/' + $volumeName + '/_data'); Destination='/var/lib/postgresql/data'
        })
    }
    $volume = [pscustomobject]@{
        Name = $volumeName
        Driver = 'local'
        Scope = 'local'
        Labels = $labels
        CreatedAt = $createdAt
        Options = [pscustomobject]@{}
        Mountpoint = '/var/lib/docker/volumes/' + $volumeName + '/_data'
    }
    $initial = [pscustomobject]@{ Containers=@($container); Volumes=@($volume) }
    $postContainer = [pscustomobject]@{ Containers=@(); Volumes=@($volume) }
    return [pscustomobject]@{
        Receipt = $receipt
        Initial = $initial
        PostContainer = $postContainer
        ContainerName = $containerName
        VolumeName = $volumeName
    }
}

function New-VariableDockerAdapter {
    param(
        [Parameter(Mandatory)] [object[]] $Snapshots,
        [string] $FailOperation = ''
    )
    $state = [pscustomobject]@{
        ReadIndex = 0
        Reads = New-Object 'System.Collections.Generic.List[string]'
        Operations = New-Object 'System.Collections.Generic.List[string]'
        Snapshots = $Snapshots
        FailOperation = $FailOperation
    }
    $inventoryProvider = {
        param([string] $Stage)
        $state.Reads.Add($Stage)
        $index = [Math]::Min($state.ReadIndex, $state.Snapshots.Count - 1)
        $state.ReadIndex++
        return $state.Snapshots[$index]
    }.GetNewClosure()
    $operationExecutor = {
        param([string] $Operation, [string] $Target, [string] $Endpoint)
        $state.Operations.Add($Operation + '|' + $Target + '|' + $Endpoint)
        if ($Operation -ceq $state.FailOperation) { throw ('TASK12_TEST_' + $Operation + '_FAILURE') }
    }.GetNewClosure()
    return [pscustomobject]@{ State=$state; InventoryProvider=$inventoryProvider; OperationExecutor=$operationExecutor }
}

function Write-TestUtf8 {
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Text)
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Quote-NativeArgument {
    param([Parameter(Mandatory)] [string] $Value)
    return '"' + $Value.Replace('\', '\').Replace('"', '\"') + '"'
}

function Invoke-TestPowerShellProcess {
    param([Parameter(Mandatory)] [string[]] $Arguments, [hashtable] $Environment = @{}, [int] $TimeoutMilliseconds = 15000)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = 'powershell.exe'
    $start.Arguments = (($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($key in $Environment.Keys) { $start.EnvironmentVariables[[string] $key] = [string] $Environment[$key] }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    if (-not $process.Start()) { throw 'TEST_PROCESS_START_FAILED' }
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            try { $process.Kill() } catch {}
            [void] $process.WaitForExit(5000)
            throw 'TEST_PROCESS_TIMEOUT'
        }
        if (-not [System.Threading.Tasks.Task]::WaitAll(@($stdoutTask, $stderrTask), 5000)) { throw 'TEST_CAPTURE_TIMEOUT' }
        return [pscustomobject]@{ ExitCode=$process.ExitCode; Stdout=$stdoutTask.Result; Stderr=$stderrTask.Result }
    } finally {
        if (-not $process.HasExited) { try { $process.Kill() } catch {}; [void] $process.WaitForExit(5000) }
        $process.Dispose()
    }
}

if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
    $script:Failures.Add('bootstrap:TASK12_LIBRARY_MISSING')
} else {
    . $libraryPath

    Invoke-TestCase 'acceptance_accepts_exact_four' {
        $fixture = New-AcceptanceFixture
        $result = Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results
        Assert-True ($result.Count -eq 4 -and $result.Status -ceq 'PASS') 'ACCEPTANCE_RESULT_INVALID'
    }

    Invoke-TestCase 'acceptance_allows_two_terminals_on_same_vehicle_and_system' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[1].VehicleId = $fixture.Expected[0].VehicleId
        $fixture.Expected[1].OnboardSystemId = $fixture.Expected[0].OnboardSystemId
        $fixture.Results[1].VehicleId = $fixture.Expected[0].VehicleId
        $fixture.Results[1].OnboardSystemId = $fixture.Expected[0].OnboardSystemId
        $result = Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results
        Assert-True ($result.Count -eq 4) 'SHARED_PAIR_REJECTED'
    }

    foreach ($count in @(0, 3, 5)) {
        Invoke-TestCase ('acceptance_rejects_expected_count_' + $count) {
            $fixture = New-AcceptanceFixture
            [object[]] $records = @()
            if ($count -eq 3) { $records = @($fixture.Expected[0..2]) }
            elseif ($count -eq 5) { $records = @($fixture.Expected + [pscustomobject]@{ SafeAlias='terminal-05'; TerminalId='10000000-0000-0000-0000-000000000005'; VehicleId='20000000-0000-0000-0000-000000000005'; OnboardSystemId='30000000-0000-0000-0000-000000000005' }) }
            Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $records -Results $fixture.Results }
        }.GetNewClosure()
        Invoke-TestCase ('acceptance_rejects_result_count_' + $count) {
            $fixture = New-AcceptanceFixture
            [object[]] $records = @()
            if ($count -eq 3) { $records = @($fixture.Results[0..2]) }
            elseif ($count -eq 5) { $records = @($fixture.Results + [pscustomobject]@{ SafeAlias='terminal-05'; TerminalId='10000000-0000-0000-0000-000000000005'; VehicleId='20000000-0000-0000-0000-000000000005'; OnboardSystemId='30000000-0000-0000-0000-000000000005'; Status='PASS' }) }
            Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $records }
        }.GetNewClosure()
    }

    Invoke-TestCase 'acceptance_rejects_duplicate_alias' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[1].SafeAlias = 'terminal-01'
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_duplicate_physical_terminal_id' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[1].TerminalId = $fixture.Expected[0].TerminalId
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_compares_uuid_by_guid_value' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[0].TerminalId = '{10000000-0000-0000-0000-000000000001}'
        $result = Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results
        Assert-True ($result.Count -eq 4) 'GUID_EQUIVALENCE_REJECTED'
    }

    Invoke-TestCase 'acceptance_rejects_equivalent_duplicate_terminal_id_formats' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[1].TerminalId = '{10000000-0000-0000-0000-000000000001}'
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_empty_uuid' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[0].VehicleId = [guid]::Empty.ToString()
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_wrong_result_mapping' {
        $fixture = New-AcceptanceFixture
        $fixture.Results[0].TerminalId = $fixture.Results[1].TerminalId
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_vehicle_to_multiple_systems' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[1].VehicleId = $fixture.Expected[0].VehicleId
        $fixture.Results[1].VehicleId = $fixture.Expected[0].VehicleId
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_system_to_multiple_vehicles' {
        $fixture = New-AcceptanceFixture
        $fixture.Expected[1].OnboardSystemId = $fixture.Expected[0].OnboardSystemId
        $fixture.Results[1].OnboardSystemId = $fixture.Expected[0].OnboardSystemId
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_non_pass_status' {
        $fixture = New-AcceptanceFixture
        $fixture.Results[2].Status = 'FAIL'
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_non_exact_result_alias_case' {
        $fixture = New-AcceptanceFixture
        $fixture.Results[0].SafeAlias = 'TERMINAL-01'
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_missing_field' {
        $fixture = New-AcceptanceFixture
        $malformed = Copy-Records -Records $fixture.Results
        $malformed[0].PSObject.Properties.Remove('OnboardSystemId')
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $malformed }
    }

    Invoke-TestCase 'acceptance_rejects_malformed_field_type' {
        $fixture = New-AcceptanceFixture
        $fixture.Results[0].SafeAlias = @('terminal-01')
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'acceptance_rejects_null_record' {
        $fixture = New-AcceptanceFixture
        $fixture.Results[0] = $null
        Assert-ThrowsTask12 { Assert-Task12Acceptance -Expected $fixture.Expected -Results $fixture.Results }
    }

    Invoke-TestCase 'cleanup_dry_run_is_read_only' {
        $fixture = New-CleanupFixture
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        $result = Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt `
            -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor
        Assert-True ($result.Actions -eq 0 -and $adapter.State.Operations.Count -eq 0) 'DRY_RUN_MUTATED'
        Assert-True ($adapter.State.Reads.Count -eq 1 -and $adapter.State.Reads[0] -ceq 'Initial') 'DRY_RUN_READ_SEQUENCE'
    }

    Invoke-TestCase 'cleanup_library_rejects_non_exact_mode_before_inventory' {
        $fixture = New-CleanupFixture
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode 'dryruncleanup' -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Reads.Count -eq 0 -and $adapter.State.Operations.Count -eq 0) 'NON_EXACT_MODE_TOUCHED_DOCKER'
    }

    Invoke-TestCase 'fix_round1_i1_rejects_wrong_case_container_label_keys_psobject' {
        $fixture = New-CleanupFixture
        $fixture.Initial.Containers[0].Labels = [pscustomobject]@{
            'COM.DRT.TASK'='p6-2-task12'; 'COM.DRT.RUN-ID'=$fixture.Receipt.RunId; 'COM.DRT.OWNER'=$fixture.Receipt.OwnerNonce
        }
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 0) 'WRONG_CASE_CONTAINER_LABEL_DELETED'
    }

    Invoke-TestCase 'fix_round1_i1_rejects_wrong_case_volume_label_keys_dictionary' {
        $fixture = New-CleanupFixture
        $wrong = @{
            'COM.DRT.TASK'='p6-2-task12'; 'COM.DRT.RUN-ID'=$fixture.Receipt.RunId; 'COM.DRT.OWNER'=$fixture.Receipt.OwnerNonce
        }
        $fixture.Initial.Volumes[0].Labels = $wrong
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 0) 'WRONG_CASE_VOLUME_LABEL_DELETED'
    }

    Invoke-TestCase 'fix_round1_i1_accepts_exact_label_keys_dictionary' {
        $fixture = New-CleanupFixture
        foreach ($resource in @($fixture.Initial.Containers[0], $fixture.Initial.Volumes[0])) {
            $exact = @{
                'com.drt.task'='p6-2-task12'; 'com.drt.run-id'=$fixture.Receipt.RunId; 'com.drt.owner'=$fixture.Receipt.OwnerNonce
            }
            $resource.Labels = $exact
        }
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        $result = Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor
        Assert-True ($result.Actions -eq 0) 'EXACT_DICTIONARY_LABELS_REJECTED'
    }

    Invoke-TestCase 'fix_round1_i1_rejects_predelete_label_key_case_drift_before_delete' {
        $fixture = New-CleanupFixture
        $preDelete = Copy-SyntheticObject $fixture.Initial
        $preDelete.Containers[0].Labels = [pscustomobject]@{
            'COM.DRT.TASK'='p6-2-task12'; 'COM.DRT.RUN-ID'=$fixture.Receipt.RunId; 'COM.DRT.OWNER'=$fixture.Receipt.OwnerNonce
        }
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $preDelete)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 0) 'PREDELETE_LABEL_DRIFT_DELETED'
    }

    Invoke-TestCase 'fix_round1_i1_rejects_postdelete_volume_label_key_case_drift' {
        $fixture = New-CleanupFixture
        $postDelete = Copy-SyntheticObject $fixture.PostContainer
        $postDelete.Volumes[0].Labels = [pscustomobject]@{
            'COM.DRT.TASK'='p6-2-task12'; 'COM.DRT.RUN-ID'=$fixture.Receipt.RunId; 'COM.DRT.OWNER'=$fixture.Receipt.OwnerNonce
        }
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial, $postDelete)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 1 -and $adapter.State.Operations[0] -match '^RemoveContainer\|') 'POSTDELETE_LABEL_DRIFT_VOLUME_DELETED'
    }

    foreach ($bindCase in @('same','parent','child','different','alias','missing-source')) {
        Invoke-TestCase ('fix_round1_i2_rejects_any_bind_' + $bindCase) {
            $fixture = New-CleanupFixture
            $mountpoint = [string] $fixture.Initial.Volumes[0].Mountpoint
            $bind = [ordered]@{ Type='bind'; Name=''; Destination='/synthetic' }
            switch ($bindCase) {
                'same' { $bind.Source = $mountpoint }
                'parent' { $bind.Source = '/var/lib/docker/volumes/' + $fixture.VolumeName }
                'child' { $bind.Source = $mountpoint + '/child' }
                'different' { $bind.Source = '/definitely/different/synthetic' }
                'alias' { $bind.Source = '/var/lib/docker/volumes/../volumes/' + $fixture.VolumeName + '/_data' }
                'missing-source' { }
            }
            $consumer = [pscustomobject]@{
                Id=('c' * 64); Name=('bind-' + $bindCase); Labels=[pscustomobject]@{}; State='running'; Mounts=@([pscustomobject] $bind)
            }
            $fixture.Initial.Containers = @($fixture.Initial.Containers[0], $consumer)
            $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
            Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
            Assert-True ($adapter.State.Operations.Count -eq 0) 'BIND_DID_NOT_FAIL_CLOSED'
        }.GetNewClosure()
    }

    Invoke-TestCase 'fix_round1_i2_postdelete_new_bind_blocks_volume_remove' {
        $fixture = New-CleanupFixture
        $postDelete = Copy-SyntheticObject $fixture.PostContainer
        $postDelete.Containers = @([pscustomobject]@{
            Id=('c' * 64); Name='new-bind'; Labels=[pscustomobject]@{}; State='running';
            Mounts=@([pscustomobject]@{ Type='bind'; Name=''; Source='/different'; Destination='/synthetic' })
        })
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial, $postDelete)
        $caught = $null
        try { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor | Out-Null } catch { $caught = $_.Exception }
        Assert-True ($null -ne $caught -and [string] $caught.Data['Task12Completed'] -ceq 'CONTAINER_REMOVED') 'POST_BIND_PARTIAL_STATE_MISSING'
        Assert-True ($adapter.State.Operations.Count -eq 1 -and $adapter.State.Operations[0] -match '^RemoveContainer\|') 'POST_BIND_VOLUME_DELETED'
    }

    foreach ($malformedMounts in @('missing-mounts','null-mounts','unknown-type','volume-without-name')) {
        Invoke-TestCase ('fix_round1_i2_rejects_unproven_mount_inventory_' + $malformedMounts) {
            $fixture = New-CleanupFixture
            $other = [pscustomobject]@{ Id=('c' * 64); Name=('other-' + $malformedMounts); Labels=[pscustomobject]@{}; State='running'; Mounts=@() }
            switch ($malformedMounts) {
                'missing-mounts' { $other.PSObject.Properties.Remove('Mounts') }
                'null-mounts' { $other.Mounts = $null }
                'unknown-type' { $other.Mounts = @([pscustomobject]@{ Type='mystery'; Name='' }) }
                'volume-without-name' { $other.Mounts = @([pscustomobject]@{ Type='volume'; Name='' }) }
            }
            $fixture.Initial.Containers = @($fixture.Initial.Containers[0], $other)
            $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
            Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
            Assert-True ($adapter.State.Operations.Count -eq 0) 'UNPROVEN_MOUNT_INVENTORY_DELETED'
        }.GetNewClosure()
    }

    Invoke-TestCase 'cleanup_apply_uses_exact_order_and_targets' {
        $fixture = New-CleanupFixture
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial, $fixture.PostContainer)
        $result = Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt `
            -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor
        $expectedReads = 'Initial,PreDelete,PostContainerDelete'
        Assert-True (($adapter.State.Reads -join ',') -ceq $expectedReads) 'APPLY_READ_SEQUENCE'
        Assert-True ($adapter.State.Operations.Count -eq 2) 'APPLY_ACTION_COUNT'
        Assert-True ($adapter.State.Operations[0] -ceq ('RemoveContainer|' + ('a' * 64) + '|' + $fixture.Receipt.DockerEndpoint)) 'CONTAINER_REMOVE_ARGUMENTS'
        Assert-True ($adapter.State.Operations[1] -ceq ('RemoveVolume|' + $fixture.VolumeName + '|' + $fixture.Receipt.DockerEndpoint)) 'VOLUME_REMOVE_ARGUMENTS'
        Assert-True ($result.Actions -eq 2) 'APPLY_RESULT_ACTIONS'
    }

    foreach ($endpoint in @('tcp://127.0.0.1:2375', 'ssh://host', 'npipe:////./pipe/not-docker')) {
        Invoke-TestCase ('cleanup_rejects_endpoint_' + ($endpoint -replace '[^a-zA-Z0-9]', '_')) {
            $fixture = New-CleanupFixture
            $fixture.Receipt.DockerEndpoint = $endpoint
            $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
            Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
            Assert-True ($adapter.State.Reads.Count -eq 0) 'UNSAFE_ENDPOINT_ENUMERATED'
        }.GetNewClosure()
    }

    foreach ($mutation in @('RunId', 'OwnerNonce', 'ContainerId', 'VolumeCreatedAt', 'SchemaVersion')) {
        Invoke-TestCase ('cleanup_rejects_bad_receipt_' + $mutation) {
            $fixture = New-CleanupFixture
            switch ($mutation) {
                'RunId' { $fixture.Receipt.RunId = '../other' }
                'OwnerNonce' { $fixture.Receipt.OwnerNonce = 'short' }
                'ContainerId' { $fixture.Receipt.ContainerId = 'not-an-id' }
                'VolumeCreatedAt' { $fixture.Receipt.VolumeCreatedAt = 'not-a-time' }
                'SchemaVersion' { $fixture.Receipt.SchemaVersion = 2 }
            }
            $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
            Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
            Assert-True ($adapter.State.Reads.Count -eq 0) 'BAD_RECEIPT_ENUMERATED'
        }.GetNewClosure()
    }

    Invoke-TestCase 'cleanup_receipt_accepts_iso_time_without_fraction' {
        $fixture = New-CleanupFixture
        $fixture.Receipt.VolumeCreatedAt = '2026-09-05T01:02:03Z'
        $fixture.Initial.Volumes[0].CreatedAt = '2026-09-05T01:02:03.0000000Z'
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        $result = Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor
        Assert-True ($result.Actions -eq 0) 'ISO_TIME_REJECTED'
    }

    Invoke-TestCase 'cleanup_rejects_string_schema_version' {
        $fixture = New-CleanupFixture
        $fixture.Receipt.SchemaVersion = '1'
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Reads.Count -eq 0) 'STRING_SCHEMA_ENUMERATED'
    }

    Invoke-TestCase 'cleanup_rejects_zero_matches' {
        $fixture = New-CleanupFixture
        $empty = [pscustomobject]@{ Containers=@(); Volumes=@() }
        $adapter = New-VariableDockerAdapter -Snapshots @($empty)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
    }

    Invoke-TestCase 'cleanup_rejects_multiple_related_containers' {
        $fixture = New-CleanupFixture
        $inventory = Copy-SyntheticObject $fixture.Initial
        $second = Copy-SyntheticObject $inventory.Containers[0]
        $second.Id = ('b' * 64)
        $second.Name = 'other-name'
        $inventory.Containers = @($inventory.Containers[0], $second)
        $adapter = New-VariableDockerAdapter -Snapshots @($inventory)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
    }

    Invoke-TestCase 'cleanup_rejects_multiple_related_volumes' {
        $fixture = New-CleanupFixture
        $inventory = Copy-SyntheticObject $fixture.Initial
        $second = Copy-SyntheticObject $inventory.Volumes[0]
        $second.Name = 'other-volume'
        $inventory.Volumes = @($inventory.Volumes[0], $second)
        $adapter = New-VariableDockerAdapter -Snapshots @($inventory)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
    }

    foreach ($mutation in @('ContainerName', 'ContainerLabel', 'ContainerId', 'VolumeName', 'VolumeLabel', 'VolumeTime', 'VolumeDriver', 'VolumeScope', 'VolumeOptions')) {
        Invoke-TestCase ('cleanup_rejects_inventory_' + $mutation) {
            $fixture = New-CleanupFixture
            $inventory = Copy-SyntheticObject $fixture.Initial
            switch ($mutation) {
                'ContainerName' { $inventory.Containers[0].Name = 'wrong-name' }
                'ContainerLabel' { $inventory.Containers[0].Labels.'com.drt.owner' = 'ffffffffffffffffffffffffffffffff' }
                'ContainerId' { $inventory.Containers[0].Id = ('b' * 64) }
                'VolumeName' { $inventory.Volumes[0].Name = 'wrong-volume' }
                'VolumeLabel' { $inventory.Volumes[0].Labels.'com.drt.run-id' = '20260905-ffffff' }
                'VolumeTime' { $inventory.Volumes[0].CreatedAt = '2026-09-05T01:02:04Z' }
                'VolumeDriver' { $inventory.Volumes[0].Driver = 'other' }
                'VolumeScope' { $inventory.Volumes[0].Scope = 'global' }
                'VolumeOptions' { $inventory.Volumes[0].Options = [pscustomobject]@{ device='synthetic' } }
            }
            $adapter = New-VariableDockerAdapter -Snapshots @($inventory)
            Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        }.GetNewClosure()
    }

    Invoke-TestCase 'cleanup_rejects_running_container' {
        $fixture = New-CleanupFixture
        $inventory = Copy-SyntheticObject $fixture.Initial
        $inventory.Containers[0].State = 'running'
        $adapter = New-VariableDockerAdapter -Snapshots @($inventory)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
    }

    foreach ($kind in @('bind', 'anonymous-volume', 'other-volume')) {
        Invoke-TestCase ('cleanup_rejects_extra_mount_' + $kind) {
            $fixture = New-CleanupFixture
            $inventory = Copy-SyntheticObject $fixture.Initial
            $extra = switch ($kind) {
                'bind' { [pscustomobject]@{ Type='bind'; Name='' } }
                'anonymous-volume' { [pscustomobject]@{ Type='volume'; Name='' } }
                default { [pscustomobject]@{ Type='volume'; Name='unrelated-volume' } }
            }
            $inventory.Containers[0].Mounts = @($inventory.Containers[0].Mounts[0], $extra)
            $adapter = New-VariableDockerAdapter -Snapshots @($inventory)
            Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        }.GetNewClosure()
    }

    Invoke-TestCase 'cleanup_rejects_non_task_shared_volume_consumer' {
        $fixture = New-CleanupFixture
        $inventory = Copy-SyntheticObject $fixture.Initial
        $consumer = [pscustomobject]@{
            Id=('c' * 64); Name='unrelated'; Labels=[pscustomobject]@{}; State='exited';
            Mounts=@([pscustomobject]@{ Type='volume'; Name=$fixture.VolumeName })
        }
        $inventory.Containers = @($inventory.Containers[0], $consumer)
        $adapter = New-VariableDockerAdapter -Snapshots @($inventory)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode DryRunCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
    }

    Invoke-TestCase 'cleanup_stops_on_pre_delete_drift' {
        $fixture = New-CleanupFixture
        $drift = Copy-SyntheticObject $fixture.Initial
        $drift.Containers[0].State = 'created'
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $drift)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 0) 'DRIFT_DID_NOT_STOP_DELETE'
    }

    Invoke-TestCase 'cleanup_container_remove_failure_stops_without_retry' {
        $fixture = New-CleanupFixture
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial) -FailOperation 'RemoveContainer'
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 1) 'CONTAINER_REMOVE_RETRIED'
    }

    Invoke-TestCase 'cleanup_volume_remove_failure_reports_partial_state_without_retry' {
        $fixture = New-CleanupFixture
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial, $fixture.PostContainer) -FailOperation 'RemoveVolume'
        $caught = $null
        try { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor | Out-Null } catch { $caught = $_.Exception }
        Assert-True ($null -ne $caught -and [string] $caught.Data['Task12Completed'] -ceq 'CONTAINER_REMOVED') 'PARTIAL_STATE_MISSING'
        Assert-True ($adapter.State.Operations.Count -eq 2) 'VOLUME_REMOVE_RETRIED'
    }

    Invoke-TestCase 'cleanup_rejects_post_remove_volume_replacement' {
        $fixture = New-CleanupFixture
        $post = Copy-SyntheticObject $fixture.PostContainer
        $post.Volumes[0].CreatedAt = '2026-09-05T01:02:04Z'
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial, $post)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 1) 'REPLACEMENT_VOLUME_DELETED'
    }

    Invoke-TestCase 'cleanup_rejects_post_remove_new_consumer' {
        $fixture = New-CleanupFixture
        $post = Copy-SyntheticObject $fixture.PostContainer
        $post.Containers = @([pscustomobject]@{
            Id=('c' * 64); Name='new-consumer'; Labels=[pscustomobject]@{}; State='running';
            Mounts=@([pscustomobject]@{ Type='volume'; Name=$fixture.VolumeName })
        })
        $adapter = New-VariableDockerAdapter -Snapshots @($fixture.Initial, $fixture.Initial, $post)
        Assert-ThrowsTask12 { Invoke-Task12Cleanup -Mode ApplyCleanup -Receipt $fixture.Receipt -InventoryProvider $adapter.InventoryProvider -OperationExecutor $adapter.OperationExecutor }
        Assert-True ($adapter.State.Operations.Count -eq 1) 'SHARED_VOLUME_DELETED'
    }

    $adapterPath = Join-Path $opsRoot 'task12-docker-adapter.ps1'
    $runnerPath = Join-Path $opsRoot 'Invoke-Task12SafetyGate.ps1'
    $fixtureSource = Join-Path $here 'fixtures\task12-fake-docker.cs'
    $tempParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
    $testRunId = [guid]::NewGuid().ToString('N')
    $nativeRoot = Join-Path $tempParent ('p6-2 task12 native fixture ' + $testRunId)
    $fakeDocker = Join-Path $nativeRoot 'task12-fake-docker.exe'
    $fakeLog = Join-Path $nativeRoot 'docker-arguments.log'
    $fakeState = Join-Path $nativeRoot 'docker-state.txt'
    $environmentNames = @('TASK12_FAKE_LOG','TASK12_FAKE_MODE','TASK12_FAKE_STATE','TASK12_FAKE_CONTAINER_ID','TASK12_FAKE_CONTAINER_ID_2',
        'TASK12_FAKE_VOLUME_NAME','TASK12_FAKE_CONTAINER_JSON','TASK12_FAKE_VOLUME_JSON','DOCKER_HOST','DOCKER_CONTEXT',
        'DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','DOCKER_API_VERSION')
    $savedEnvironment = @{}
    foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    New-Item -ItemType Directory -Path $nativeRoot | Out-Null
    Add-Type -Path $fixtureSource -OutputAssembly $fakeDocker -OutputType ConsoleApplication
    Copy-Item -LiteralPath $fakeDocker -Destination (Join-Path $nativeRoot 'docker.exe')
    $nativeFixture = New-CleanupFixture
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_LOG', $fakeLog, 'Process')
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', '', 'Process')
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_STATE', $fakeState, 'Process')
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_ID', ('a' * 64), 'Process')
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_VOLUME_NAME', $nativeFixture.VolumeName, 'Process')
    $dockerContainer = [pscustomobject]@{
        Id=('a' * 64); Name=('/' + $nativeFixture.ContainerName);
        Config=[pscustomobject]@{ Labels=$nativeFixture.Initial.Containers[0].Labels };
        State=[pscustomobject]@{ Status='exited' };
        Mounts=@($nativeFixture.Initial.Containers[0].Mounts)
    }
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', (@($dockerContainer) | ConvertTo-Json -Compress -Depth 10), 'Process')
    [Environment]::SetEnvironmentVariable('TASK12_FAKE_VOLUME_JSON', (@($nativeFixture.Initial.Volumes[0]) | ConvertTo-Json -Compress -Depth 10), 'Process')
    foreach ($name in @('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','DOCKER_API_VERSION')) {
        [Environment]::SetEnvironmentVariable($name, 'TASK12_SYNTHETIC_SENSITIVE', 'Process')
    }

    try {
        Invoke-TestCase 'native_adapter_artifact_exists_and_loads' {
            Assert-True (Test-Path -LiteralPath $adapterPath -PathType Leaf) 'DOCKER_ADAPTER_MISSING'
            . $adapterPath
            Assert-True ($null -ne (Get-Command Get-Task12DockerInventory -ErrorAction SilentlyContinue)) 'ADAPTER_FUNCTION_MISSING'
        }

        if (Test-Path -LiteralPath $adapterPath -PathType Leaf) { . $adapterPath }

        Invoke-TestCase 'native_adapter_parses_and_normalizes_complete_inventory' {
            if (Test-Path -LiteralPath $fakeLog) { Remove-Item -LiteralPath $fakeLog -Force }
            $inventory = Get-Task12DockerInventory -DockerExecutablePath $fakeDocker `
                -Endpoint $nativeFixture.Receipt.DockerEndpoint -TimeoutMilliseconds 5000
            Assert-True (@($inventory.Containers).Count -eq 1 -and @($inventory.Volumes).Count -eq 1) 'INVENTORY_COUNTS'
            Assert-True ($inventory.Containers[0].Name -ceq $nativeFixture.ContainerName) 'CONTAINER_NAME_NOT_NORMALIZED'
            Assert-True ($inventory.Containers[0].State -ceq 'exited') 'CONTAINER_STATE_NOT_NORMALIZED'
            $logLines = @(Get-Content -LiteralPath $fakeLog)
            Assert-True ($logLines.Count -eq 4) 'DOCKER_READ_COMMAND_COUNT'
            Assert-True (@($logLines | Where-Object { $_ -notmatch ('^--host\t' + [regex]::Escape($nativeFixture.Receipt.DockerEndpoint) + '\t') }).Count -eq 0) 'ENDPOINT_NOT_EXPLICIT'
            Assert-True (@($logLines | Where-Object { $_ -notmatch 'ENV=\|\|\|\|$' }).Count -eq 0) 'DOCKER_ROUTE_ENV_NOT_CLEARED'
        }

        Invoke-TestCase 'native_adapter_uses_exact_non_force_delete_arguments' {
            if (Test-Path -LiteralPath $fakeLog) { Remove-Item -LiteralPath $fakeLog -Force }
            if (Test-Path -LiteralPath $fakeState) { Remove-Item -LiteralPath $fakeState -Force }
            Remove-Task12DockerContainer -DockerExecutablePath $fakeDocker -Endpoint $nativeFixture.Receipt.DockerEndpoint `
                -ContainerId ('a' * 64) -TimeoutMilliseconds 5000
            Remove-Task12DockerVolume -DockerExecutablePath $fakeDocker -Endpoint $nativeFixture.Receipt.DockerEndpoint `
                -VolumeName $nativeFixture.VolumeName -TimeoutMilliseconds 5000
            $lines = @(Get-Content -LiteralPath $fakeLog)
            Assert-True ($lines.Count -eq 2) 'DELETE_COMMAND_COUNT'
            Assert-True ($lines[0] -match ('\tcontainer\trm\t' + ('a' * 64) + '\tENV=')) 'CONTAINER_DELETE_SHAPE'
            Assert-True ($lines[1] -match ('\tvolume\trm\t' + [regex]::Escape($nativeFixture.VolumeName) + '\tENV=')) 'VOLUME_DELETE_SHAPE'
            Assert-True (($lines -join "`n") -notmatch '(--force|-f|--volumes|prune|context)') 'UNSAFE_DELETE_OPTION'
        }

        Invoke-TestCase 'native_adapter_nonzero_exit_is_fixed_and_nonleaking' {
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', 'fail-list', 'Process')
            try {
                $caught = $null
                try { Get-Task12DockerInventory -DockerExecutablePath $fakeDocker -Endpoint $nativeFixture.Receipt.DockerEndpoint -TimeoutMilliseconds 5000 | Out-Null } catch { $caught = $_.Exception.Message }
                Assert-True ($caught -ceq 'TASK12_DOCKER_COMMAND_FAILED') 'NATIVE_EXIT_CODE_UNSAFE'
                Assert-True ($caught -notmatch 'SYNTHETIC_SECRET') 'NATIVE_STDERR_LEAKED'
            } finally { [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', '', 'Process') }
        }

        Invoke-TestCase 'native_adapter_rejects_malformed_json_structure_with_fixed_code' {
            $savedJson = [Environment]::GetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', 'Process')
            if (Test-Path -LiteralPath $fakeState) { Remove-Item -LiteralPath $fakeState -Force }
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', '[{"SyntheticSensitive":"TASK12_SYNTHETIC_SENSITIVE"}]', 'Process')
            try {
                $caught = $null
                try { Get-Task12DockerInventory -DockerExecutablePath $fakeDocker -Endpoint $nativeFixture.Receipt.DockerEndpoint -TimeoutMilliseconds 5000 | Out-Null } catch { $caught = $_.Exception.Message }
                Assert-True ($caught -ceq 'TASK12_DOCKER_JSON_INVALID') 'MALFORMED_STRUCTURE_CODE_UNSAFE'
                Assert-True ($caught -notmatch 'SYNTHETIC_SENSITIVE') 'MALFORMED_STRUCTURE_LEAKED'
            } finally { [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', $savedJson, 'Process') }
        }

        Invoke-TestCase 'fix_round1_i2_native_adapter_preserves_bind_and_volume_paths' {
            $savedJson = [Environment]::GetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', 'Process')
            if (Test-Path -LiteralPath $fakeState) { Remove-Item -LiteralPath $fakeState -Force }
            $other = [pscustomobject]@{
                Id=('b' * 64); Name='/unrelated'; Config=[pscustomobject]@{ Labels=[pscustomobject]@{} };
                State=[pscustomobject]@{ Status='running' };
                Mounts=@([pscustomobject]@{ Type='bind'; Source='C:\synthetic'; Destination='/synthetic' })
            }
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_ID_2', ('b' * 64), 'Process')
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', (@($dockerContainer, $other) | ConvertTo-Json -Compress -Depth 10), 'Process')
            try {
                $inventory = Get-Task12DockerInventory -DockerExecutablePath $fakeDocker -Endpoint $nativeFixture.Receipt.DockerEndpoint -TimeoutMilliseconds 5000
                Assert-True (@($inventory.Containers).Count -eq 2) 'BIND_INVENTORY_COUNT'
                Assert-True ($inventory.Containers[1].Mounts[0].Type -ceq 'bind' -and $inventory.Containers[1].Mounts[0].Name -ceq '') 'BIND_NOT_NORMALIZED'
                Assert-True ($inventory.Containers[1].Mounts[0].Source -ceq 'C:\synthetic') 'BIND_SOURCE_DROPPED'
                Assert-True ($inventory.Containers[1].Mounts[0].Destination -ceq '/synthetic') 'BIND_DESTINATION_DROPPED'
                Assert-True ($inventory.Volumes[0].Mountpoint -ceq $nativeFixture.Initial.Volumes[0].Mountpoint) 'VOLUME_MOUNTPOINT_DROPPED'
                $plan = Assert-Task12Receipt -Receipt $nativeFixture.Receipt
                Assert-ThrowsTask12 { Assert-Task12InitialInventory -Inventory $inventory -Plan $plan }
            } finally {
                [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_ID_2', '', 'Process')
                [Environment]::SetEnvironmentVariable('TASK12_FAKE_CONTAINER_JSON', $savedJson, 'Process')
            }
        }

        Invoke-TestCase 'native_adapter_timeout_is_bounded_and_fixed' {
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', 'timeout', 'Process')
            $clock = [Diagnostics.Stopwatch]::StartNew()
            try {
                $caught = $null
                try { Get-Task12DockerInventory -DockerExecutablePath $fakeDocker -Endpoint $nativeFixture.Receipt.DockerEndpoint -TimeoutMilliseconds 200 | Out-Null } catch { $caught = $_.Exception.Message }
                Assert-True ($caught -ceq 'TASK12_DOCKER_COMMAND_TIMEOUT') 'NATIVE_TIMEOUT_CODE_UNSAFE'
                Assert-True ($clock.Elapsed.TotalSeconds -lt 8) 'NATIVE_TIMEOUT_NOT_BOUNDED'
            } finally { $clock.Stop(); [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', '', 'Process') }
        }

        Invoke-TestCase 'cli_verify_acceptance_has_fixed_safe_stdout' {
            $acceptance = New-AcceptanceFixture
            foreach ($record in @($acceptance.Expected + $acceptance.Results)) {
                $record | Add-Member -NotePropertyName SyntheticSensitive -NotePropertyValue 'TASK12_SYNTHETIC_SENSITIVE'
            }
            $expectedPath = Join-Path $nativeRoot 'expected sensitive.json'
            $resultsPath = Join-Path $nativeRoot 'results sensitive.json'
            Write-TestUtf8 $expectedPath ($acceptance.Expected | ConvertTo-Json -Depth 10)
            Write-TestUtf8 $resultsPath ($acceptance.Results | ConvertTo-Json -Depth 10)
            $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath,'-Mode','VerifyAcceptance','-ExpectedPath',$expectedPath,'-ResultsPath',$resultsPath)
            $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
            Assert-True ($result.ExitCode -eq 0 -and $lines.Count -eq 1) 'CLI_ACCEPTANCE_EXIT_OR_LINES'
            Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=PASS MODE=VerifyAcceptance ACCEPTED=4') 'CLI_ACCEPTANCE_RECORD'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_ACCEPTANCE_STDERR'
            Assert-True (($result.Stdout + $result.Stderr) -notmatch 'TASK12_SYNTHETIC_SENSITIVE') 'CLI_ACCEPTANCE_SECRET_LEAK'
            Assert-True (($result.Stdout + $result.Stderr) -notmatch [regex]::Escape($nativeRoot)) 'CLI_ACCEPTANCE_PATH_LEAK'
        }

        Invoke-TestCase 'cli_accepts_utf8_bom_json' {
            $acceptance = New-AcceptanceFixture
            $expectedPath = Join-Path $nativeRoot 'expected bom.json'
            $resultsPath = Join-Path $nativeRoot 'results bom.json'
            $bom = New-Object System.Text.UTF8Encoding($true, $true)
            [System.IO.File]::WriteAllText($expectedPath, ($acceptance.Expected | ConvertTo-Json -Depth 10), $bom)
            [System.IO.File]::WriteAllText($resultsPath, ($acceptance.Results | ConvertTo-Json -Depth 10), $bom)
            $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath,'-Mode','VerifyAcceptance','-ExpectedPath',$expectedPath,'-ResultsPath',$resultsPath)
            $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
            Assert-True ($result.ExitCode -eq 0 -and $lines.Count -eq 1 -and $lines[0] -ceq 'TASK12_SAFETY_STATUS=PASS MODE=VerifyAcceptance ACCEPTED=4') 'CLI_BOM_JSON_REJECTED'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_BOM_STDERR'
        }

        Invoke-TestCase 'cli_omitted_mode_defaults_to_dry_run' {
            $missingPath = Join-Path $nativeRoot 'TASK12_SYNTHETIC_SENSITIVE_missing_receipt.json'
            $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath,'-ReceiptPath',$missingPath)
            $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
            Assert-True ($result.ExitCode -eq 1 -and $lines.Count -eq 1) 'CLI_DEFAULT_MODE_EXIT_OR_LINES'
            Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=FAIL MODE=DryRunCleanup CODE=JSON_INPUT_INVALID COMPLETED=NONE') 'CLI_DEFAULT_MODE_NOT_DRY'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_DEFAULT_MODE_STDERR'
            Assert-True (($result.Stdout + $result.Stderr) -notmatch 'TASK12_SYNTHETIC_SENSITIVE') 'CLI_DEFAULT_MODE_LEAK'
        }

        Invoke-TestCase 'cli_dry_cleanup_uses_adapter_without_delete' {
            if (Test-Path -LiteralPath $fakeLog) { Remove-Item -LiteralPath $fakeLog -Force }
            if (Test-Path -LiteralPath $fakeState) { Remove-Item -LiteralPath $fakeState -Force }
            $receiptPath = Join-Path $nativeRoot 'receipt.json'
            Write-TestUtf8 $receiptPath ($nativeFixture.Receipt | ConvertTo-Json -Depth 10)
            $fixturePath = $nativeRoot + ';' + [Environment]::GetEnvironmentVariable('Path', 'Process')
            $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath,'-Mode','DryRunCleanup','-ReceiptPath',$receiptPath,'-TimeoutMilliseconds','5000') -Environment @{ Path=$fixturePath }
            $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
            Assert-True ($result.ExitCode -eq 0 -and $lines.Count -eq 1) 'CLI_DRY_EXIT_OR_LINES'
            Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=PASS MODE=DryRunCleanup CONTAINERS=1 VOLUMES=1 ACTIONS=0') 'CLI_DRY_RECORD'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_DRY_STDERR'
            Assert-True ((Get-Content -Raw $fakeLog) -notmatch '\t(rm|prune)\t') 'CLI_DRY_DELETED'
        }

        Invoke-TestCase 'cli_apply_cleanup_runs_only_fake_exact_actions' {
            if (Test-Path -LiteralPath $fakeLog) { Remove-Item -LiteralPath $fakeLog -Force }
            if (Test-Path -LiteralPath $fakeState) { Remove-Item -LiteralPath $fakeState -Force }
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', '', 'Process')
            $receiptPath = Join-Path $nativeRoot 'apply receipt.json'
            Write-TestUtf8 $receiptPath ($nativeFixture.Receipt | ConvertTo-Json -Depth 10)
            $fixturePath = $nativeRoot + ';' + [Environment]::GetEnvironmentVariable('Path', 'Process')
            $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath,'-Mode','ApplyCleanup','-ReceiptPath',$receiptPath,'-TimeoutMilliseconds','5000') -Environment @{ Path=$fixturePath }
            $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
            Assert-True ($result.ExitCode -eq 0 -and $lines.Count -eq 1) 'CLI_APPLY_EXIT_OR_LINES'
            Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=PASS MODE=ApplyCleanup CONTAINERS=1 VOLUMES=1 ACTIONS=2 COMPLETED=CONTAINER_REMOVED,VOLUME_REMOVED') 'CLI_APPLY_RECORD'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_APPLY_STDERR'
            $deleteLines = @(Get-Content $fakeLog | Where-Object { $_ -match '\t(container|volume)\trm\t' })
            Assert-True ($deleteLines.Count -eq 2) 'CLI_APPLY_DELETE_COUNT'
            Assert-True (($deleteLines -join "`n") -notmatch '(--force|-f|--volumes|prune|context)') 'CLI_APPLY_UNSAFE_OPTION'
        }

        Invoke-TestCase 'cli_partial_delete_failure_is_safe_and_reports_completed_step' {
            if (Test-Path -LiteralPath $fakeLog) { Remove-Item -LiteralPath $fakeLog -Force }
            if (Test-Path -LiteralPath $fakeState) { Remove-Item -LiteralPath $fakeState -Force }
            [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', 'fail-volume', 'Process')
            try {
                $receiptPath = Join-Path $nativeRoot 'partial receipt.json'
                Write-TestUtf8 $receiptPath ($nativeFixture.Receipt | ConvertTo-Json -Depth 10)
                $fixturePath = $nativeRoot + ';' + [Environment]::GetEnvironmentVariable('Path', 'Process')
                $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath,'-Mode','ApplyCleanup','-ReceiptPath',$receiptPath,'-TimeoutMilliseconds','5000') -Environment @{ Path=$fixturePath }
                $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
                Assert-True ($result.ExitCode -eq 1 -and $lines.Count -eq 1) 'CLI_PARTIAL_EXIT_OR_LINES'
                Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=FAIL MODE=ApplyCleanup CODE=CLEANUP_REJECTED COMPLETED=CONTAINER_REMOVED') 'CLI_PARTIAL_RECORD'
                Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_PARTIAL_STDERR'
                Assert-True (($result.Stdout + $result.Stderr) -notmatch 'SYNTHETIC_SECRET') 'CLI_PARTIAL_SECRET_LEAK'
                $deleteLines = @(Get-Content $fakeLog | Where-Object { $_ -match '\t(container|volume)\trm\t' })
                Assert-True ($deleteLines.Count -eq 2) 'CLI_PARTIAL_RETRY_DETECTED'
            } finally { [Environment]::SetEnvironmentVariable('TASK12_FAKE_MODE', '', 'Process') }
        }

        foreach ($cliCase in @('invalid-mode','missing-json','malformed-json','malformed-receipt')) {
            Invoke-TestCase ('cli_safe_failure_' + $cliCase) {
                $badPath = Join-Path $nativeRoot ('TASK12_SYNTHETIC_SENSITIVE_' + $cliCase + '.json')
                $arguments = @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$runnerPath)
                switch ($cliCase) {
                    'invalid-mode' { $arguments += @('-Mode','DeleteEverything') }
                    'missing-json' { $arguments += @('-Mode','VerifyAcceptance','-ExpectedPath',$badPath,'-ResultsPath',$badPath) }
                    'malformed-json' { Write-TestUtf8 $badPath '{TASK12_SYNTHETIC_SENSITIVE'; $arguments += @('-Mode','VerifyAcceptance','-ExpectedPath',$badPath,'-ResultsPath',$badPath) }
                    'malformed-receipt' { Write-TestUtf8 $badPath '[]'; $arguments += @('-Mode','DryRunCleanup','-ReceiptPath',$badPath) }
                }
                $result = Invoke-TestPowerShellProcess -Arguments $arguments
                $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
                Assert-True ($result.ExitCode -eq 1 -and $lines.Count -eq 1) 'CLI_FAILURE_EXIT_OR_LINES'
                Assert-True ($lines[0] -match '^TASK12_SAFETY_STATUS=FAIL MODE=(Invalid|VerifyAcceptance|DryRunCleanup) CODE=[A-Z0-9_]+ COMPLETED=NONE$') 'CLI_FAILURE_RECORD'
                Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_FAILURE_STDERR'
                Assert-True (($result.Stdout + $result.Stderr) -notmatch 'TASK12_SYNTHETIC_SENSITIVE') 'CLI_FAILURE_SECRET_LEAK'
                Assert-True (($result.Stdout + $result.Stderr) -notmatch [regex]::Escape($nativeRoot)) 'CLI_FAILURE_PATH_LEAK'
            }.GetNewClosure()
        }

        Invoke-TestCase 'cli_missing_library_is_fixed_and_nonleaking' {
            $isolated = Join-Path $nativeRoot 'isolated runner'
            New-Item -ItemType Directory -Path $isolated | Out-Null
            $isolatedRunner = Join-Path $isolated 'Invoke-Task12SafetyGate.ps1'
            Copy-Item -LiteralPath $runnerPath -Destination $isolatedRunner
            $result = Invoke-TestPowerShellProcess -Arguments @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$isolatedRunner,'-Mode','VerifyAcceptance','-ExpectedPath','TASK12_SYNTHETIC_SENSITIVE','-ResultsPath','TASK12_SYNTHETIC_SENSITIVE')
            $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
            Assert-True ($result.ExitCode -eq 1 -and $lines.Count -eq 1) 'CLI_LIBRARY_EXIT_OR_LINES'
            Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=FAIL MODE=VerifyAcceptance CODE=LIBRARY_LOAD_FAILED COMPLETED=NONE') 'CLI_LIBRARY_RECORD'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_LIBRARY_STDERR'
            Assert-True (($result.Stdout + $result.Stderr) -notmatch [regex]::Escape($nativeRoot)) 'CLI_LIBRARY_PATH_LEAK'
        }

        foreach ($brokenLibrary in @('task12-safety-lib.ps1','task12-docker-adapter.ps1')) {
            Invoke-TestCase ('fix_round1_m1_cli_parse_failure_' + ($brokenLibrary -replace '\.ps1$','')) {
                $isolated = Join-Path $nativeRoot ('broken ' + $brokenLibrary)
                New-Item -ItemType Directory -Path $isolated | Out-Null
                $isolatedRunner = Join-Path $isolated 'Invoke-Task12SafetyGate.ps1'
                Copy-Item -LiteralPath $runnerPath -Destination $isolatedRunner
                foreach ($libraryName in @('task12-safety-lib.ps1','task12-docker-adapter.ps1')) {
                    Copy-Item -LiteralPath (Join-Path $opsRoot $libraryName) -Destination (Join-Path $isolated $libraryName)
                }
                $caseLog = Join-Path $isolated 'must-not-call-docker.log'
                $caseState = Join-Path $isolated 'fake-docker-state.txt'
                $receiptPath = Join-Path $isolated 'TASK12_SYNTHETIC_SENSITIVE_receipt.json'
                Write-TestUtf8 -Path $receiptPath -Text ($nativeFixture.Receipt | ConvertTo-Json -Depth 10)
                $fixturePath = $nativeRoot + ';' + [Environment]::GetEnvironmentVariable('Path', 'Process')
                $oldLog = [Environment]::GetEnvironmentVariable('TASK12_FAKE_LOG', 'Process')
                $oldState = [Environment]::GetEnvironmentVariable('TASK12_FAKE_STATE', 'Process')
                [Environment]::SetEnvironmentVariable('TASK12_FAKE_LOG', $caseLog, 'Process')
                [Environment]::SetEnvironmentVariable('TASK12_FAKE_STATE', $caseState, 'Process')
                try {
                    $positive = Invoke-TestPowerShellProcess -Arguments @(
                        '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$isolatedRunner,
                        '-ReceiptPath',$receiptPath) -Environment @{ Path=$fixturePath }
                    $positiveLines = @($positive.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
                    Assert-True ($positive.ExitCode -eq 0 -and $positiveLines.Count -eq 1) 'CLI_PARSE_LIBRARY_POSITIVE_CONTROL_EXIT'
                    Assert-True ($positiveLines[0] -ceq 'TASK12_SAFETY_STATUS=PASS MODE=DryRunCleanup CONTAINERS=1 VOLUMES=1 ACTIONS=0') 'CLI_PARSE_LIBRARY_POSITIVE_CONTROL_RECORD'
                    Assert-True ([string]::IsNullOrEmpty($positive.Stderr)) 'CLI_PARSE_LIBRARY_POSITIVE_CONTROL_STDERR'
                    Assert-True (Test-Path -LiteralPath $caseLog -PathType Leaf) 'CLI_PARSE_LIBRARY_POSITIVE_CONTROL_NOT_REACHED'
                    Assert-True (@(Get-Content -LiteralPath $caseLog).Count -eq 4) 'CLI_PARSE_LIBRARY_POSITIVE_CONTROL_INVENTORY_COUNT'

                    $resolvedCaseLog = [System.IO.Path]::GetFullPath($caseLog)
                    $resolvedIsolated = [System.IO.Path]::GetFullPath($isolated).TrimEnd('\')
                    if ([System.IO.Path]::GetFullPath((Split-Path -Parent $resolvedCaseLog)).TrimEnd('\') -cne $resolvedIsolated -or
                        (Split-Path -Leaf $resolvedCaseLog) -cne 'must-not-call-docker.log') {
                        throw 'CLI_PARSE_LIBRARY_LOG_BOUNDARY_INVALID'
                    }
                    Remove-Item -LiteralPath $resolvedCaseLog -Force -ErrorAction Stop
                    Assert-True (-not (Test-Path -LiteralPath $resolvedCaseLog)) 'CLI_PARSE_LIBRARY_LOG_RESET_FAILED'

                    Write-TestUtf8 -Path (Join-Path $isolated $brokenLibrary) -Text "function Broken-Task12Library { 'TASK12_SYNTHETIC_SENSITIVE"
                    $result = Invoke-TestPowerShellProcess -Arguments @(
                        '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$isolatedRunner,
                        '-ReceiptPath',$receiptPath) -Environment @{ Path=$fixturePath }
                } finally {
                    [Environment]::SetEnvironmentVariable('TASK12_FAKE_LOG', $oldLog, 'Process')
                    [Environment]::SetEnvironmentVariable('TASK12_FAKE_STATE', $oldState, 'Process')
                }
                $lines = @($result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
                Assert-True ($result.ExitCode -eq 1 -and $lines.Count -eq 1) 'CLI_PARSE_LIBRARY_EXIT_OR_LINES'
                Assert-True ($lines[0] -ceq 'TASK12_SAFETY_STATUS=FAIL MODE=DryRunCleanup CODE=LIBRARY_LOAD_FAILED COMPLETED=NONE') 'CLI_PARSE_LIBRARY_RECORD'
                Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'CLI_PARSE_LIBRARY_STDERR'
                Assert-True (-not (Test-Path -LiteralPath $caseLog)) 'CLI_PARSE_LIBRARY_TOUCHED_DOCKER'
                Assert-True (($result.Stdout + $result.Stderr) -notmatch 'TASK12_SYNTHETIC_SENSITIVE') 'CLI_PARSE_LIBRARY_SECRET_LEAK'
                Assert-True (($result.Stdout + $result.Stderr) -notmatch [regex]::Escape($isolated)) 'CLI_PARSE_LIBRARY_PATH_LEAK'
            }.GetNewClosure()
        }
    } finally {
        foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
        $resolvedParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $nativeRoot)).TrimEnd('\')
        $resolvedLeaf = Split-Path -Leaf $nativeRoot
        if ($resolvedParent -cne $tempParent -or $resolvedLeaf -cne ('p6-2 task12 native fixture ' + $testRunId)) {
            $script:Failures.Add('cleanup:TEMP_PATH_BOUNDARY_INVALID')
        } else {
            try { Remove-Item -LiteralPath $nativeRoot -Recurse -Force -ErrorAction Stop } catch { $script:Failures.Add('cleanup:TEMP_CLEANUP_FAILED') }
        }
    }
}

Write-Output ('TOTAL=' + $script:Total)
Write-Output ('PASSED=' + $script:Passed)
Write-Output ('FAILED=' + $script:Failures.Count)
foreach ($failure in $script:Failures) { Write-Output ('FAIL=' + $failure) }
if ($script:Failures.Count -ne 0) { exit 1 }
exit 0
