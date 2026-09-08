Set-StrictMode -Version Latest

function Assert-Task12ObjectRecord {
    param(
        [Parameter(Mandatory)] [AllowNull()] $Record,
        [Parameter(Mandatory)] [string[]] $Fields,
        [Parameter(Mandatory)] [AllowEmptyCollection()] [string[]] $UuidFields
    )

    if ($null -eq $Record -or $Record -is [string] -or $Record -is [System.Collections.IEnumerable]) {
        throw 'TASK12_RECORD_MALFORMED'
    }
    foreach ($field in $Fields) {
        $property = $Record.PSObject.Properties[$field]
        if ($null -eq $property -or $property.Value -isnot [string] -or
            [string]::IsNullOrWhiteSpace([string] $property.Value)) {
            throw 'TASK12_FIELD_INVALID'
        }
    }
    foreach ($field in $UuidFields) {
        $parsed = [guid]::Empty
        if (-not [guid]::TryParse([string] $Record.$field, [ref] $parsed) -or $parsed -eq [guid]::Empty) {
            throw 'TASK12_UUID_INVALID'
        }
    }
}

function Assert-Task12Acceptance {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [AllowNull()] [AllowEmptyCollection()] [object[]] $Expected,
        [Parameter(Mandatory)] [AllowNull()] [AllowEmptyCollection()] [object[]] $Results
    )

    if (@($Expected).Count -ne 4 -or @($Results).Count -ne 4) { throw 'TASK12_ACCEPTANCE_COUNT_INVALID' }
    $aliases = @('terminal-01', 'terminal-02', 'terminal-03', 'terminal-04')
    $expectedByAlias = @{}
    $terminalIds = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $vehicleToSystem = @{}
    $systemToVehicle = @{}
    foreach ($record in $Expected) {
        Assert-Task12ObjectRecord -Record $record -Fields @('SafeAlias', 'TerminalId', 'VehicleId', 'OnboardSystemId') `
            -UuidFields @('TerminalId', 'VehicleId', 'OnboardSystemId')
        $alias = [string] $record.SafeAlias
        if ($alias -cnotin $aliases -or $expectedByAlias.ContainsKey($alias)) { throw 'TASK12_ALIAS_SET_INVALID' }
        if (-not $terminalIds.Add(([guid] $record.TerminalId).ToString('D'))) { throw 'TASK12_TERMINAL_ID_DUPLICATE' }
        $vehicle = ([guid] $record.VehicleId).ToString('D')
        $system = ([guid] $record.OnboardSystemId).ToString('D')
        if (($vehicleToSystem.ContainsKey($vehicle) -and $vehicleToSystem[$vehicle] -cne $system) -or
            ($systemToVehicle.ContainsKey($system) -and $systemToVehicle[$system] -cne $vehicle)) {
            throw 'TASK12_VEHICLE_SYSTEM_CONFLICT'
        }
        $vehicleToSystem[$vehicle] = $system
        $systemToVehicle[$system] = $vehicle
        $expectedByAlias[$alias] = $record
    }
    if (@($expectedByAlias.Keys).Count -ne 4) { throw 'TASK12_ALIAS_SET_INVALID' }

    $resultAliases = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($record in $Results) {
        Assert-Task12ObjectRecord -Record $record -Fields @('SafeAlias', 'TerminalId', 'VehicleId', 'OnboardSystemId', 'Status') `
            -UuidFields @('TerminalId', 'VehicleId', 'OnboardSystemId')
        $alias = [string] $record.SafeAlias
        if ($alias -cnotin $aliases -or -not $resultAliases.Add($alias) -or -not $expectedByAlias.ContainsKey($alias)) {
            throw 'TASK12_RESULT_ALIAS_INVALID'
        }
        if ([string] $record.Status -cne 'PASS') { throw 'TASK12_RESULT_STATUS_INVALID' }
        $expectedRecord = $expectedByAlias[$alias]
        foreach ($field in @('TerminalId', 'VehicleId', 'OnboardSystemId')) {
            if (([guid] $record.$field) -ne ([guid] $expectedRecord.$field)) { throw 'TASK12_RESULT_MAPPING_INVALID' }
        }
    }
    if ($resultAliases.Count -ne 4) { throw 'TASK12_RESULT_ALIAS_INVALID' }
    return [pscustomobject]@{ Count=4; Status='PASS' }
}

function Get-Task12PropertyValue {
    param([AllowNull()] $Object, [Parameter(Mandatory)] [string] $Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) { return $Object[$Name] }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-Task12OrdinalLabelEntry {
    param([AllowNull()] $Labels, [Parameter(Mandatory)] [string] $RequiredName)
    if ($null -eq $Labels) { return [pscustomobject]@{ Found=$false; Value=$null } }
    $found = $false
    $value = $null
    $entries = if ($Labels -is [System.Collections.IDictionary]) {
        @($Labels.GetEnumerator() | ForEach-Object { [pscustomobject]@{ Key=[string] $_.Key; Value=$_.Value } })
    } else {
        @($Labels.PSObject.Properties | ForEach-Object { [pscustomobject]@{ Key=[string] $_.Name; Value=$_.Value } })
    }
    foreach ($entry in $entries) {
        if ([StringComparer]::OrdinalIgnoreCase.Equals($entry.Key, $RequiredName)) {
            if (-not [StringComparer]::Ordinal.Equals($entry.Key, $RequiredName) -or $found) {
                throw 'TASK12_LABEL_KEY_INVALID'
            }
            $found = $true
            $value = $entry.Value
        }
    }
    return [pscustomobject]@{ Found=$found; Value=$value }
}

function Assert-Task12Receipt {
    [CmdletBinding()]
    param([Parameter(Mandatory)] [AllowNull()] $Receipt)

    Assert-Task12ObjectRecord -Record $Receipt `
        -Fields @('RunId', 'OwnerNonce', 'DockerEndpoint', 'ContainerId', 'VolumeCreatedAt') -UuidFields @()
    $schemaVersion = Get-Task12PropertyValue -Object $Receipt -Name 'SchemaVersion'
    $schemaIsInteger = $schemaVersion -is [byte] -or $schemaVersion -is [sbyte] -or
        $schemaVersion -is [int16] -or $schemaVersion -is [uint16] -or
        $schemaVersion -is [int32] -or $schemaVersion -is [uint32] -or
        $schemaVersion -is [int64] -or $schemaVersion -is [uint64]
    if (-not $schemaIsInteger -or [Convert]::ToInt64($schemaVersion) -ne 1) {
        throw 'TASK12_RECEIPT_SCHEMA_INVALID'
    }
    if ([string] $Receipt.RunId -cnotmatch '^\d{8}-[a-f0-9]{6}$') { throw 'TASK12_RUN_ID_INVALID' }
    if ([string] $Receipt.OwnerNonce -cnotmatch '^[a-f0-9]{32}$') { throw 'TASK12_OWNER_NONCE_INVALID' }
    if ([string] $Receipt.ContainerId -cnotmatch '^[a-f0-9]{64}$') { throw 'TASK12_CONTAINER_ID_INVALID' }
    if ([string] $Receipt.DockerEndpoint -cnotin @(
            'npipe:////./pipe/dockerDesktopLinuxEngine',
            'npipe:////./pipe/docker_engine',
            'unix:///var/run/docker.sock')) {
        throw 'TASK12_DOCKER_ENDPOINT_INVALID'
    }
    $createdAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string] $Receipt.VolumeCreatedAt,
            [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind, [ref] $createdAt)) {
        throw 'TASK12_VOLUME_CREATED_AT_INVALID'
    }
    return [pscustomobject]@{
        RunId = [string] $Receipt.RunId
        OwnerNonce = [string] $Receipt.OwnerNonce
        DockerEndpoint = [string] $Receipt.DockerEndpoint
        ContainerId = [string] $Receipt.ContainerId
        VolumeCreatedAt = $createdAt
        ContainerName = 'drt-p6-2-task12-pg-' + [string] $Receipt.RunId
        VolumeName = 'drt-p6-2-task12-pgdata-' + [string] $Receipt.RunId
    }
}

function Test-Task12RequiredLabels {
    param([AllowNull()] $Labels, [Parameter(Mandatory)] $Plan)
    if ($null -eq $Labels) { return $false }
    $task = Get-Task12OrdinalLabelEntry -Labels $Labels -RequiredName 'com.drt.task'
    $run = Get-Task12OrdinalLabelEntry -Labels $Labels -RequiredName 'com.drt.run-id'
    $owner = Get-Task12OrdinalLabelEntry -Labels $Labels -RequiredName 'com.drt.owner'
    return ($task.Found -and $task.Value -is [string] -and [string] $task.Value -ceq 'p6-2-task12' -and
        $run.Found -and $run.Value -is [string] -and [string] $run.Value -ceq $Plan.RunId -and
        $owner.Found -and $owner.Value -is [string] -and [string] $owner.Value -ceq $Plan.OwnerNonce)
}

function Assert-Task12InventoryMountSafety {
    param([Parameter(Mandatory)] [AllowEmptyCollection()] [object[]] $Containers)
    foreach ($container in $Containers) {
        if ($null -eq $container) { throw 'TASK12_INVENTORY_MALFORMED' }
        $mountsProperty = $container.PSObject.Properties['Mounts']
        if ($null -eq $mountsProperty -or $null -eq $mountsProperty.Value) { throw 'TASK12_MOUNT_INVENTORY_UNPROVEN' }
        foreach ($mount in @($mountsProperty.Value)) {
            if ($null -eq $mount) { throw 'TASK12_MOUNT_INVENTORY_UNPROVEN' }
            $typeProperty = $mount.PSObject.Properties['Type']
            if ($null -eq $typeProperty -or $typeProperty.Value -isnot [string] -or
                [string]::IsNullOrWhiteSpace([string] $typeProperty.Value)) {
                throw 'TASK12_MOUNT_INVENTORY_UNPROVEN'
            }
            $type = [string] $typeProperty.Value
            if ($type -ceq 'bind') { throw 'TASK12_BIND_MOUNT_PRESENT' }
            if ($type -ceq 'volume') {
                $nameProperty = $mount.PSObject.Properties['Name']
                if ($null -eq $nameProperty -or $nameProperty.Value -isnot [string] -or
                    [string]::IsNullOrWhiteSpace([string] $nameProperty.Value)) {
                    throw 'TASK12_MOUNT_INVENTORY_UNPROVEN'
                }
            } elseif ($type -cne 'tmpfs') {
                throw 'TASK12_MOUNT_TYPE_UNKNOWN'
            }
        }
    }
}

function Test-Task12RelatedContainer {
    param([AllowNull()] $Container, [Parameter(Mandatory)] $Plan)
    if ($null -eq $Container) { return $false }
    $labels = Get-Task12PropertyValue -Object $Container -Name 'Labels'
    $task = [string] (Get-Task12PropertyValue -Object $labels -Name 'com.drt.task')
    $run = [string] (Get-Task12PropertyValue -Object $labels -Name 'com.drt.run-id')
    $owner = [string] (Get-Task12PropertyValue -Object $labels -Name 'com.drt.owner')
    return (([string] (Get-Task12PropertyValue -Object $Container -Name 'Id')) -ceq $Plan.ContainerId -or
        ([string] (Get-Task12PropertyValue -Object $Container -Name 'Name')) -ceq $Plan.ContainerName -or
        $run -ceq $Plan.RunId -or ($task -ceq 'p6-2-task12' -and $owner -ceq $Plan.OwnerNonce))
}

function Test-Task12RelatedVolume {
    param([AllowNull()] $Volume, [Parameter(Mandatory)] $Plan)
    if ($null -eq $Volume) { return $false }
    $labels = Get-Task12PropertyValue -Object $Volume -Name 'Labels'
    $task = [string] (Get-Task12PropertyValue -Object $labels -Name 'com.drt.task')
    $run = [string] (Get-Task12PropertyValue -Object $labels -Name 'com.drt.run-id')
    $owner = [string] (Get-Task12PropertyValue -Object $labels -Name 'com.drt.owner')
    return (([string] (Get-Task12PropertyValue -Object $Volume -Name 'Name')) -ceq $Plan.VolumeName -or
        $run -ceq $Plan.RunId -or ($task -ceq 'p6-2-task12' -and $owner -ceq $Plan.OwnerNonce))
}

function Assert-Task12VolumeIdentity {
    param([Parameter(Mandatory)] $Volume, [Parameter(Mandatory)] $Plan)
    if (([string] (Get-Task12PropertyValue -Object $Volume -Name 'Name')) -cne $Plan.VolumeName -or
        ([string] (Get-Task12PropertyValue -Object $Volume -Name 'Driver')) -cne 'local' -or
        ([string] (Get-Task12PropertyValue -Object $Volume -Name 'Scope')) -cne 'local' -or
        -not (Test-Task12RequiredLabels -Labels (Get-Task12PropertyValue -Object $Volume -Name 'Labels') -Plan $Plan)) {
        throw 'TASK12_VOLUME_IDENTITY_INVALID'
    }
    $createdAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string] (Get-Task12PropertyValue -Object $Volume -Name 'CreatedAt'),
            [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind, [ref] $createdAt) -or
        $createdAt -ne $Plan.VolumeCreatedAt) {
        throw 'TASK12_VOLUME_CREATED_AT_MISMATCH'
    }
    $options = Get-Task12PropertyValue -Object $Volume -Name 'Options'
    if ($null -ne $options) {
        $optionCount = if ($options -is [System.Collections.IDictionary]) { $options.Count } else { @($options.PSObject.Properties).Count }
        if ($optionCount -ne 0) { throw 'TASK12_VOLUME_OPTIONS_UNSAFE' }
    }
}

function Get-Task12VolumeConsumers {
    param([Parameter(Mandatory)] [AllowEmptyCollection()] [object[]] $Containers, [Parameter(Mandatory)] [string] $VolumeName)
    $consumers = New-Object 'System.Collections.Generic.List[object]'
    foreach ($container in $Containers) {
        if ($null -eq $container) { throw 'TASK12_INVENTORY_MALFORMED' }
        foreach ($mount in @((Get-Task12PropertyValue -Object $container -Name 'Mounts'))) {
            if ($null -ne $mount -and
                ([string] (Get-Task12PropertyValue -Object $mount -Name 'Type')) -ceq 'volume' -and
                ([string] (Get-Task12PropertyValue -Object $mount -Name 'Name')) -ceq $VolumeName) {
                $consumers.Add($container)
                break
            }
        }
    }
    return @($consumers.ToArray())
}

function Assert-Task12InitialInventory {
    param([Parameter(Mandatory)] [AllowNull()] $Inventory, [Parameter(Mandatory)] $Plan)
    if ($null -eq $Inventory) { throw 'TASK12_INVENTORY_MALFORMED' }
    $containersProperty = $Inventory.PSObject.Properties['Containers']
    $volumesProperty = $Inventory.PSObject.Properties['Volumes']
    if ($null -eq $containersProperty -or $null -eq $volumesProperty) { throw 'TASK12_INVENTORY_MALFORMED' }
    $containers = @($containersProperty.Value)
    $volumes = @($volumesProperty.Value)
    # 全库存任何 bind 都拒绝自动清理，不能凭路径字符串推定不存在别名。
    Assert-Task12InventoryMountSafety -Containers $containers
    # 候选联合筛查：名称、ID、RunId、owner任一路命中都不能被忽略。
    $relatedContainers = @($containers | Where-Object { Test-Task12RelatedContainer -Container $_ -Plan $Plan })
    $relatedVolumes = @($volumes | Where-Object { Test-Task12RelatedVolume -Volume $_ -Plan $Plan })
    if ($relatedContainers.Count -ne 1) { throw 'TASK12_CONTAINER_CANDIDATE_AMBIGUOUS' }
    if ($relatedVolumes.Count -ne 1) { throw 'TASK12_VOLUME_CANDIDATE_AMBIGUOUS' }
    $container = $relatedContainers[0]
    $volume = $relatedVolumes[0]
    if (([string] (Get-Task12PropertyValue -Object $container -Name 'Id')) -cne $Plan.ContainerId -or
        ([string] (Get-Task12PropertyValue -Object $container -Name 'Name')) -cne $Plan.ContainerName -or
        -not (Test-Task12RequiredLabels -Labels (Get-Task12PropertyValue -Object $container -Name 'Labels') -Plan $Plan)) {
        throw 'TASK12_CONTAINER_IDENTITY_INVALID'
    }
    $state = [string] (Get-Task12PropertyValue -Object $container -Name 'State')
    if ($state -cnotin @('created', 'exited')) { throw 'TASK12_CONTAINER_STATE_UNSAFE' }
    $mounts = @((Get-Task12PropertyValue -Object $container -Name 'Mounts'))
    if ($mounts.Count -ne 1 -or $null -eq $mounts[0] -or
        ([string] (Get-Task12PropertyValue -Object $mounts[0] -Name 'Type')) -cne 'volume' -or
        ([string] (Get-Task12PropertyValue -Object $mounts[0] -Name 'Name')) -cne $Plan.VolumeName) {
        throw 'TASK12_CONTAINER_MOUNTS_UNSAFE'
    }
    Assert-Task12VolumeIdentity -Volume $volume -Plan $Plan
    $consumers = @(Get-Task12VolumeConsumers -Containers $containers -VolumeName $Plan.VolumeName)
    if ($consumers.Count -ne 1 -or
        ([string] (Get-Task12PropertyValue -Object $consumers[0] -Name 'Id')) -cne $Plan.ContainerId) {
        throw 'TASK12_VOLUME_CONSUMERS_UNSAFE'
    }
    $fingerprint = @(
        [string] (Get-Task12PropertyValue -Object $container -Name 'Id'),
        [string] (Get-Task12PropertyValue -Object $container -Name 'Name'),
        $state,
        [string] (Get-Task12PropertyValue -Object $volume -Name 'Name'),
        ([DateTimeOffset] (Get-Task12PropertyValue -Object $volume -Name 'CreatedAt')).ToUniversalTime().ToString('o')
    ) -join '|'
    return [pscustomobject]@{ Fingerprint=$fingerprint; Container=$container; Volume=$volume }
}

function Assert-Task12PostContainerInventory {
    param([Parameter(Mandatory)] [AllowNull()] $Inventory, [Parameter(Mandatory)] $Plan)
    if ($null -eq $Inventory) { throw 'TASK12_INVENTORY_MALFORMED' }
    $containersProperty = $Inventory.PSObject.Properties['Containers']
    $volumesProperty = $Inventory.PSObject.Properties['Volumes']
    if ($null -eq $containersProperty -or $null -eq $volumesProperty) { throw 'TASK12_INVENTORY_MALFORMED' }
    $containers = @($containersProperty.Value)
    $volumes = @($volumesProperty.Value)
    Assert-Task12InventoryMountSafety -Containers $containers
    if (@($containers | Where-Object { Test-Task12RelatedContainer -Container $_ -Plan $Plan }).Count -ne 0) {
        throw 'TASK12_CONTAINER_STILL_PRESENT'
    }
    $relatedVolumes = @($volumes | Where-Object { Test-Task12RelatedVolume -Volume $_ -Plan $Plan })
    if ($relatedVolumes.Count -ne 1) { throw 'TASK12_VOLUME_CANDIDATE_AMBIGUOUS' }
    Assert-Task12VolumeIdentity -Volume $relatedVolumes[0] -Plan $Plan
    if (@(Get-Task12VolumeConsumers -Containers $containers -VolumeName $Plan.VolumeName).Count -ne 0) {
        throw 'TASK12_VOLUME_CONSUMERS_UNSAFE'
    }
}

function New-Task12CleanupException {
    param([Parameter(Mandatory)] [string] $Code, [Parameter(Mandatory)] [string] $Completed)
    $exception = New-Object System.InvalidOperationException($Code)
    $exception.Data['Task12Completed'] = $Completed
    return $exception
}

function Invoke-Task12InventoryRead {
    param([Parameter(Mandatory)] [scriptblock] $InventoryProvider, [Parameter(Mandatory)] [string] $Stage)
    try { return (& $InventoryProvider $Stage) } catch { throw (New-Task12CleanupException -Code 'TASK12_DOCKER_INVENTORY_FAILED' -Completed 'NONE') }
}

function Invoke-Task12Cleanup {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $Mode,
        [Parameter(Mandatory)] [AllowNull()] $Receipt,
        [Parameter(Mandatory)] [scriptblock] $InventoryProvider,
        [Parameter(Mandatory)] [scriptblock] $OperationExecutor
    )

    if ($Mode -cnotin @('DryRunCleanup','ApplyCleanup')) { throw 'TASK12_CLEANUP_MODE_INVALID' }
    $plan = Assert-Task12Receipt -Receipt $Receipt
    $initialInventory = Invoke-Task12InventoryRead -InventoryProvider $InventoryProvider -Stage 'Initial'
    $initialProof = Assert-Task12InitialInventory -Inventory $initialInventory -Plan $plan
    if ($Mode -ceq 'DryRunCleanup') {
        return [pscustomobject]@{ Mode=$Mode; Containers=1; Volumes=1; Actions=0; Completed='NONE' }
    }

    # 删除前再次读取，任何相关状态漂移都先停下。
    $preDeleteInventory = Invoke-Task12InventoryRead -InventoryProvider $InventoryProvider -Stage 'PreDelete'
    $preDeleteProof = Assert-Task12InitialInventory -Inventory $preDeleteInventory -Plan $plan
    if ($preDeleteProof.Fingerprint -cne $initialProof.Fingerprint) { throw 'TASK12_INVENTORY_DRIFT' }
    try {
        & $OperationExecutor 'RemoveContainer' $plan.ContainerId $plan.DockerEndpoint
    } catch {
        throw (New-Task12CleanupException -Code 'TASK12_CONTAINER_REMOVE_FAILED' -Completed 'NONE')
    }

    $postContainerInventory = try { & $InventoryProvider 'PostContainerDelete' } catch {
        throw (New-Task12CleanupException -Code 'TASK12_DOCKER_INVENTORY_FAILED' -Completed 'CONTAINER_REMOVED')
    }
    try {
        Assert-Task12PostContainerInventory -Inventory $postContainerInventory -Plan $plan
    } catch {
        $code = if ([string] $_.Exception.Message -match '^TASK12_[A-Z0-9_]+$') { [string] $_.Exception.Message } else { 'TASK12_POST_DELETE_VALIDATION_FAILED' }
        # 部分删除状态必须带回，便于人工核对，绝不自动接管。
        throw (New-Task12CleanupException -Code $code -Completed 'CONTAINER_REMOVED')
    }
    try {
        & $OperationExecutor 'RemoveVolume' $plan.VolumeName $plan.DockerEndpoint
    } catch {
        throw (New-Task12CleanupException -Code 'TASK12_VOLUME_REMOVE_FAILED' -Completed 'CONTAINER_REMOVED')
    }
    return [pscustomobject]@{ Mode=$Mode; Containers=1; Volumes=1; Actions=2; Completed='CONTAINER_REMOVED,VOLUME_REMOVED' }
}
