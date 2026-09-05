# Task 1A：纯安全合同与只读Plan；不创建演练资源、不启动服务、不删除文件。
Set-StrictMode -Version Latest

function Get-P6Field {
    param($Value, [string] $Name)
    if ($null -eq $Value) { return $null }
    if ($Value -is [Collections.IDictionary]) { return $Value[$Name] }
    $property=$Value.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
function Get-P6Sha256 {
    param([byte[]] $Bytes)
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Get-P6GitValue {
    param([string] $RepositoryRoot, [string] $Operation)
    # 仅固定只读命令；不拼接调用者的命令/URL，输出仅在内存消费。
    $arguments=switch -CaseSensitive ($Operation) {
        'Head' { 'rev-parse --verify HEAD' }
        'Branch' { 'branch --show-current' }
        'Root' { 'rev-parse --show-toplevel' }
        default { throw 'REHEARSAL_PREFLIGHT_FAILED' }
    }
    $psi=New-Object Diagnostics.ProcessStartInfo
    $psi.FileName=(Get-Command git.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $psi.WorkingDirectory=$RepositoryRoot
    $psi.Arguments=$arguments
    $psi.UseShellExecute=$false
    $psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true
    $psi.RedirectStandardError=$true
    foreach ($key in @($psi.EnvironmentVariables.Keys)) { if ($key -match '^GIT_') { $psi.EnvironmentVariables.Remove($key) } }
    # 沙箱身份可能不是工作树拥有者。仅为此固定只读子进程信任确切目录，不改全局配置。
    $psi.EnvironmentVariables['GIT_CONFIG_COUNT']='1'
    $psi.EnvironmentVariables['GIT_CONFIG_KEY_0']='safe.directory'
    $psi.EnvironmentVariables['GIT_CONFIG_VALUE_0']=[IO.Path]::GetFullPath($RepositoryRoot)
    $psi.EnvironmentVariables['GIT_OPTIONAL_LOCKS']='0'
    $process=New-Object Diagnostics.Process
    $process.StartInfo=$psi
    try {
        if (-not $process.Start()) { throw 'REHEARSAL_PREFLIGHT_FAILED' }
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) { throw 'REHEARSAL_PREFLIGHT_FAILED' }
        if (-not [Threading.Tasks.Task]::WaitAll(@($stdout,$stderr),5000) -or $process.ExitCode -ne 0) { throw 'REHEARSAL_PREFLIGHT_FAILED' }
        return $stdout.Result.Trim()
    } catch { throw 'REHEARSAL_PREFLIGHT_FAILED' }
    finally {
        # 只结束亲自启动的短命git句柄，不按PID重新查找/杀进程。
        try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) } } catch {}
        $process.Dispose()
    }
}
function Get-P6IsolationPlan {
    param([string] $RepositoryRoot, $RepositoryState=$null)
    try {
        $root=[IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\','/')
        if ($null -eq $RepositoryState) {
            $RepositoryState=[pscustomobject]@{ Head=(Get-P6GitValue $root 'Head'); Branch=(Get-P6GitValue $root 'Branch'); Root=(Get-P6GitValue $root 'Root') }
        }
        if ((Get-P6Field $RepositoryState 'Head') -cne 'f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e' -or
            (Get-P6Field $RepositoryState 'Branch') -cne 'codex/p6-2-ops-safety-gates') { throw 'REHEARSAL_HEAD_MISMATCH' }
        $gitRoot=[IO.Path]::GetFullPath([string](Get-P6Field $RepositoryState 'Root')).TrimEnd('\','/')
        if (-not [StringComparer]::OrdinalIgnoreCase.Equals($gitRoot,$root)) { throw 'REHEARSAL_ROOT_MISMATCH' }
        # Windows根路径按绝对路径、去尾分隔符、大小写不敏感规范化；只把不可逆摘要加入计划。
        $rootDigest=Get-P6Sha256 ([Text.Encoding]::UTF8.GetBytes($gitRoot.ToUpperInvariant()))
        $manifest=@('p6-isolation-plan-v2',[string]$RepositoryState.Head,[string]$RepositoryState.Branch,('root='+$rootDigest))
        # 固定清单；未来wire/runbook加入自动使指纹失效；不枚举private目录。
        foreach ($relative in @(
            'tools/ops-safety/Invoke-P6CompositeIsolationRehearsal.ps1',
            'tools/ops-safety/p6-composite-isolation-lib.ps1',
            'tools/ops-safety/fixtures/P6CompositeFlywayTool.java',
            'tools/ops-safety/fixtures/P6CompositeWireHarness.java',
            'tools/ops-safety/Invoke-Task12SafetyGate.ps1',
            'tools/ops-safety/task12-safety-lib.ps1',
            'tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1',
            'docs/pilot/p6-2-local-isolation-rehearsal-runbook.md')) {
            $path=Assert-P6ChildPath $root (Join-Path $root $relative)
            $hash=if ([IO.File]::Exists($path)) { Get-P6Sha256 ([IO.File]::ReadAllBytes($path)) } else { 'ABSENT' }
            $manifest+=$relative+'='+$hash
        }
        return [pscustomobject]@{
            Status='PLAN'; Actions=0; Executable=$false
            Fingerprint=(Get-P6Sha256 ([Text.Encoding]::UTF8.GetBytes(($manifest -join "`n"))))
            Head=$RepositoryState.Head; Dependencies='JDK21,POSTGRESQL17_POSTGIS,MAVEN,POWERSHELL'
            Boundary='NEW_NATIVE_CLUSTER,LOOPBACK_ONLY,NO_DOCKER,NO_CLOUD,NO_EXISTING_DB'; RunLocation='SDD_RUN_CHILD_ONLY'
        }
    } catch {
        if ([string]$_.Exception.Message -cin @('REHEARSAL_HEAD_MISMATCH','REHEARSAL_ROOT_MISMATCH','REHEARSAL_PATH_INVALID')) { throw $_.Exception.Message }
        throw 'REHEARSAL_PREFLIGHT_FAILED'
    }
}
function Assert-P6Confirmation {
    param($Plan, [AllowEmptyString()][string] $ConfirmationToken)
    $fingerprint=Get-P6Field $Plan 'Fingerprint'
    if ($fingerprint -isnot [string] -or $fingerprint -cnotmatch '^[a-f0-9]{64}$' -or
        $ConfirmationToken -cnotmatch '^[a-f0-9]{64}$' -or $ConfirmationToken -cne $fingerprint) { throw 'REHEARSAL_CONFIRMATION_INVALID' }
}
function Assert-P6ChildPath {
    param([string] $Root, [string] $Path, [switch] $MustExist)
    try {
        # 拒绝UNC/设备路径/ADS/跳转片段；不能仅做路径字符串前缀比较。
        foreach ($candidate in @($Root,$Path)) {
            if ($candidate -cnotmatch '^[A-Za-z]:[\\/]' -or $candidate.Substring(2) -match '[:*?"<>|\x00-\x1f]' -or
                $candidate -match '(^|[\\/])\.{1,2}([\\/]|$)' -or $candidate -match '[. ]([\\/]|$)') { throw 'invalid' }
        }
        $canonicalRoot=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')
        $canonical=[IO.Path]::GetFullPath($Path).TrimEnd('\','/')
        if (-not $canonical.StartsWith($canonicalRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'invalid' }
        $walk=$canonical
        while (-not [string]::IsNullOrEmpty($walk)) {
            if (Test-Path -LiteralPath $walk) {
                $item=Get-Item -LiteralPath $walk -Force -ErrorAction Stop
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'invalid' }
            }
            $parent=[IO.Path]::GetDirectoryName($walk)
            if ($parent -eq $walk) { break }
            $walk=$parent
        }
        if ($MustExist -and -not (Test-Path -LiteralPath $canonical)) { throw 'invalid' }
        return $canonical
    } catch { throw 'REHEARSAL_PATH_INVALID' }
}
function Assert-P6NewRunDirectory {
    param([string] $Root,[string] $Path)
    $canonical=Assert-P6ChildPath $Root $Path
    if ([IO.Path]::GetFileName($canonical) -cnotmatch '^native-[a-f0-9]{32}$' -or
        -not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetDirectoryName($canonical),[IO.Path]::GetFullPath($Root).TrimEnd('\','/'))) { throw 'REHEARSAL_PATH_INVALID' }
    if (Test-Path -LiteralPath $canonical) { throw 'REHEARSAL_RUN_EXISTS' }
    return $canonical
}
function Assert-P6LoopbackPorts {
    param($Ports,$Listeners)
    if ($null -eq $Ports -or @($Ports).Count -ne 4 -or $null -eq $Listeners) { throw 'REHEARSAL_PORT_INVALID' }
    $seen=New-Object 'Collections.Generic.HashSet[int]'
    foreach ($port in $Ports) {
        if (($port -isnot [int] -and $port -isnot [long]) -or $port -lt 1024 -or $port -gt 65535 -or -not $seen.Add([int]$port)) { throw 'REHEARSAL_PORT_INVALID' }
    }
    foreach ($listener in $Listeners) {
        $port=Get-P6Field $listener 'LocalPort'
        if ($null -eq $port -or $null -eq (Get-P6Field $listener 'LocalAddress') -or $null -eq (Get-P6Field $listener 'OwningProcess')) { throw 'REHEARSAL_PORT_INVALID' }
        if ($seen.Contains([int]$port)) { throw 'REHEARSAL_PORT_OCCUPIED' }
    }
}
function Assert-P6ListenerEvidence {
    param($Evidence)
    try {
        # 空结果也必须由一次明确成功的观测提供；未知/失败绝不能降格为空数组。
        if ($null -eq $Evidence -or (Get-P6Field $Evidence 'Succeeded') -isnot [bool] -or -not $Evidence.Succeeded -or
            $null -eq $Evidence.PSObject.Properties['Items'] -or $Evidence.Items -isnot [array]) { throw 'invalid' }
        foreach ($listener in $Evidence.Items) {
            if ($null -eq $listener -or (Get-P6Field $listener 'ObservationSucceeded') -isnot [bool] -or -not $listener.ObservationSucceeded) { throw 'invalid' }
            $port=Get-P6Field $listener 'LocalPort'
            $owner=Get-P6Field $listener 'OwningProcess'
            foreach ($number in @($port,$owner)) {
                if ($number -isnot [int16] -and $number -isnot [uint16] -and $number -isnot [int32] -and $number -isnot [uint32] -and $number -isnot [int64]) { throw 'invalid' }
            }
            if ($port -lt 1 -or $port -gt 65535 -or $owner -lt 1 -or $owner -gt [int]::MaxValue) { throw 'invalid' }
            $address=Get-P6Field $listener 'LocalAddress'
            $parsedAddress=$null
            if ($address -isnot [string] -or -not [Net.IPAddress]::TryParse($address,[ref]$parsedAddress)) { throw 'invalid' }
        }
        return $Evidence
    } catch { throw 'REHEARSAL_LISTENER_UNPROVEN' }
}
function Assert-P6Receipt {
    param($Receipt,[string] $RunParent,$Marker)
    try {
        if ((Get-P6Field $Receipt 'SchemaVersion') -ne 1 -or (Get-P6Field $Marker 'SchemaVersion') -ne 1) { throw 'invalid' }
        foreach ($field in @('RunId','OwnerNonce','RunDirectory','CreatedAt')) {
            $value=Get-P6Field $Receipt $field
            if ($value -isnot [string] -or $value -cne (Get-P6Field $Marker $field)) { throw 'invalid' }
        }
        if ($Receipt.RunId -cnotmatch '^[a-f0-9]{32}$' -or $Receipt.OwnerNonce -cnotmatch '^[a-f0-9]{64}$') { throw 'invalid' }
        # 直接检查属性，避免PowerShell把合法的空数组展开成null。
        if ($null -eq $Receipt.PSObject.Properties['Processes'] -or $null -eq $Receipt.Processes) { throw 'invalid' }
        $created=[DateTimeOffset]::ParseExact($Receipt.CreatedAt,'o',[Globalization.CultureInfo]::InvariantCulture)
        if ($created -gt [DateTimeOffset]::UtcNow) { throw 'invalid' }
        $root=Assert-P6ChildPath $RunParent $Receipt.RunDirectory -MustExist
        if (-not [StringComparer]::OrdinalIgnoreCase.Equals($root,(Join-Path $RunParent ('native-'+$Receipt.RunId)))) { throw 'invalid' }
        $data=Assert-P6ChildPath $root ([string](Get-P6Field $Receipt 'PgData'))
        if (-not [StringComparer]::OrdinalIgnoreCase.Equals($data,(Join-Path $root 'pgdata'))) { throw 'invalid' }
        if ((Get-P6Field $Receipt 'PgDatabase') -cne 'composite_live' -or (Get-P6Field $Receipt 'MigrationDatabase') -cne 'composite_onboard') { throw 'invalid' }
        Assert-P6LoopbackPorts (Get-P6Field $Receipt 'Ports') @()
        if ((Get-P6Field $Marker 'PgPort') -ne $Receipt.Ports[0]) { throw 'invalid' }
        return $root
    } catch { throw 'REHEARSAL_RECEIPT_INVALID' }
}
function Assert-P6ProcessIdentity {
    param($Receipt,$Recorded,$Observed)
    try {
        if ($null -eq $Observed -or $null -eq $Recorded) { throw 'invalid' }
        $processId=Get-P6Field $Recorded 'Pid'
        if (($processId -isnot [int] -and $processId -isnot [long]) -or $processId -le 0 -or $processId -eq $PID -or $processId -ne (Get-P6Field $Observed 'Pid')) { throw 'invalid' }
        $owned=@((Get-P6Field $Receipt 'Processes') | Where-Object { (Get-P6Field $_ 'Pid') -eq $processId })
        if ($owned.Count -ne 1) { throw 'invalid' }
        foreach ($field in @('StartTimeUtc','ExecutablePath','WorkingDirectory','RunId','OwnerNonce','Kind','ArgumentMarker')) {
            if ((Get-P6Field $Recorded $field) -cne (Get-P6Field $owned[0] $field)) { throw 'invalid' }
        }
        foreach ($field in @('StartTimeUtc','ExecutablePath','WorkingDirectory')) {
            $value=Get-P6Field $Recorded $field
            if ($value -isnot [string] -or $value -cne (Get-P6Field $Observed $field)) { throw 'invalid' }
        }
        $start=[DateTimeOffset]::ParseExact($Recorded.StartTimeUtc,'o',[Globalization.CultureInfo]::InvariantCulture)
        $created=[DateTimeOffset]::ParseExact([string]$Receipt.CreatedAt,'o',[Globalization.CultureInfo]::InvariantCulture)
        if ($start -lt $created -or $start -gt [DateTimeOffset]::UtcNow -or $Recorded.WorkingDirectory -cne $Receipt.RunDirectory -or
            (Get-P6Field $Recorded 'RunId') -cne $Receipt.RunId -or (Get-P6Field $Recorded 'OwnerNonce') -cne $Receipt.OwnerNonce) { throw 'invalid' }
        $kind=Get-P6Field $Recorded 'Kind'
        $command=Get-P6Field $Observed 'CommandLine'
        if ($command -isnot [string]) { throw 'invalid' }
        if ($kind -ceq 'Java') {
            if ($Recorded.ExecutablePath -cne 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe') { throw 'invalid' }
            $marker='-Dp6.rehearsal.run='+$Receipt.RunId
            if ($Recorded.ArgumentMarker -cne $marker -or $command -cnotmatch ('(?:^|\s)"?'+[regex]::Escape($marker)+'"?(?:\s|$)')) { throw 'invalid' }
        } elseif ($kind -ceq 'Postgres') {
            if ($Recorded.ExecutablePath -cne 'C:\Program Files\PostgreSQL\17\bin\postgres.exe' -or $Recorded.ArgumentMarker -cne $Receipt.PgData -or
                $command -cnotmatch ('(?:^|\s)-D\s+"'+[regex]::Escape([string]$Receipt.PgData)+'"(?:\s|$)')) { throw 'invalid' }
        } else { throw 'invalid' }
        return $true
    } catch { throw 'REHEARSAL_PROCESS_UNPROVEN' }
}
function Assert-P6PgIdentity {
    param($Receipt,$Recorded,$Observed,$PidFileLines,$Listeners)
    try {
        Assert-P6ProcessIdentity $Receipt $Recorded $Observed | Out-Null
        if ($Recorded.Kind -cne 'Postgres' -or $null -eq $PidFileLines -or @($PidFileLines).Count -lt 8 -or $null -eq $Listeners) { throw 'invalid' }
        $epoch=([DateTimeOffset]::Parse($Recorded.StartTimeUtc)).ToUnixTimeSeconds()
        if ($PidFileLines[0] -cne [string]$Recorded.Pid -or $PidFileLines[1] -cne $Receipt.PgData -or
            $PidFileLines[2] -cne [string]$epoch -or $PidFileLines[3] -cne [string]$Receipt.Ports[0] -or
            $PidFileLines[5] -cne '127.0.0.1' -or $PidFileLines[7] -cne 'ready') { throw 'invalid' }
        $matching=@($Listeners | Where-Object { $_.LocalPort -eq $Receipt.Ports[0] -or $_.OwningProcess -eq $Recorded.Pid })
        if ($matching.Count -ne 1 -or $matching[0].LocalAddress -cne '127.0.0.1' -or
            $matching[0].LocalPort -ne $Receipt.Ports[0] -or $matching[0].OwningProcess -ne $Recorded.Pid) { throw 'invalid' }
        return $true
    } catch { throw 'REHEARSAL_PG_UNPROVEN' }
}
function Invoke-P6ValidatedStop {
    param($Receipt,[string] $RunParent,$Recorded,[scriptblock] $ReadOwnershipEvidence,[scriptblock] $StopProcess,[scriptblock] $WaitStopped,[string] $Kind)
    # 这个危险入口自身强制全部前置验证；直接调用它也没有跳过验证或默认成功的分支。
    try {
        if ($Kind -cnotin @('Java','Postgres') -or (Get-P6Field $Recorded 'Kind') -cne $Kind -or
            $null -eq $ReadOwnershipEvidence -or $null -eq $StopProcess -or $null -eq $WaitStopped) { throw 'invalid' }
        $evidence=& $ReadOwnershipEvidence $Recorded 5000
        if ((Get-P6Field $evidence 'Succeeded') -isnot [bool] -or -not $evidence.Succeeded -or
            $null -eq $evidence.PSObject.Properties['Marker'] -or $null -eq $evidence.PSObject.Properties['ObservedProcess'] -or
            $null -eq $evidence.PSObject.Properties['Listeners']) { throw 'invalid' }
        # 当前marker与现存路径链在stop前重验，不能只信启动时的内存receipt。
        $root=Assert-P6Receipt $Receipt $RunParent $evidence.Marker
        $listeners=Assert-P6ListenerEvidence $evidence.Listeners
        if ($Kind -ceq 'Postgres') {
            if ($null -eq $evidence.PSObject.Properties['PidFileLines'] -or $evidence.PidFileLines -isnot [array]) { throw 'invalid' }
            Assert-P6ChildPath $root $Receipt.PgData -MustExist | Out-Null
            Assert-P6PgIdentity $Receipt $Recorded $evidence.ObservedProcess $evidence.PidFileLines $listeners.Items | Out-Null
        } else {
            Assert-P6ProcessIdentity $Receipt $Recorded $evidence.ObservedProcess | Out-Null
        }
    } catch { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_PROCESS_UNPROVEN' } }
    try { & $StopProcess $Recorded | Out-Null }
    catch { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_STOP_FAILED' } }
    try {
        $stopped=& $WaitStopped $Recorded 5000
        if ($stopped -isnot [bool] -or -not $stopped) { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_STOP_TIMEOUT' } }
        $after=& $ReadOwnershipEvidence $Recorded 5000
        if ((Get-P6Field $after 'Succeeded') -isnot [bool] -or -not $after.Succeeded -or
            $null -eq $after.PSObject.Properties['ObservedProcess'] -or $null -ne $after.ObservedProcess) { throw 'invalid' }
        Assert-P6Receipt $Receipt $RunParent (Get-P6Field $after 'Marker') | Out-Null
        $afterListeners=Assert-P6ListenerEvidence (Get-P6Field $after 'Listeners')
        foreach ($listener in $afterListeners.Items) {
            if ($listener.OwningProcess -eq $Recorded.Pid -or ($Kind -ceq 'Postgres' -and $listener.LocalPort -eq $Receipt.Ports[0])) { throw 'invalid' }
        }
    } catch { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_PROCESS_UNPROVEN' } }
    return [pscustomobject]@{ Status='STOPPED'; Code='REHEARSAL_STOP_CONFIRMED' }
}
function Invoke-P6OwnedStop {
    param($Receipt,[string] $RunParent,$Recorded,[scriptblock] $ReadOwnershipEvidence,[scriptblock] $StopProcess,[scriptblock] $WaitStopped)
    # 保留名称的旧入口现在严格限Java，Postgres不能绕过专属pidfile/listener证明。
    if ((Get-P6Field $Recorded 'Kind') -cne 'Java') { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_PROCESS_UNPROVEN' } }
    return Invoke-P6ValidatedStop $Receipt $RunParent $Recorded $ReadOwnershipEvidence $StopProcess $WaitStopped 'Java'
}
function Stop-P6OwnedPostgres {
    param($Receipt,[string] $RunParent,$Recorded,[scriptblock] $ReadOwnershipEvidence,[scriptblock] $StopProcess,[scriptblock] $WaitStopped)
    return Invoke-P6ValidatedStop $Receipt $RunParent $Recorded $ReadOwnershipEvidence $StopProcess $WaitStopped 'Postgres'
}
function Assert-P6RemovalProof {
    param($Receipt,[string] $RunParent,$Marker,[string] $Target,$ProcessObservations,$Listeners)
    try {
        $root=Assert-P6Receipt $Receipt $RunParent $Marker
        $canonical=Assert-P6ChildPath $root $Target -MustExist
        $listenerEvidence=Assert-P6ListenerEvidence $Listeners
        if ($canonical -cnotin @((Join-Path $root 'pgdata'),(Join-Path $root 'secrets'),(Join-Path $root 'gateway-outbox'))) { throw 'invalid' }
        # 每个收据PID必须有独立、成功、明确“不存在”的观测；空数组不能伪装全部已停。
        $owned=@($Receipt.Processes)
        if ($null -eq $ProcessObservations -or @($ProcessObservations).Count -ne $owned.Count -or $null -eq $Listeners) { throw 'invalid' }
        $seen=New-Object 'Collections.Generic.HashSet[int]'
        foreach ($entry in $owned) {
            $processId=Get-P6Field $entry 'Pid'
            if (($processId -isnot [int] -and $processId -isnot [long]) -or $processId -le 0 -or -not $seen.Add([int]$processId)) { throw 'invalid' }
            $proof=@($ProcessObservations | Where-Object { (Get-P6Field $_ 'Pid') -eq $processId })
            if ($proof.Count -ne 1 -or (Get-P6Field $proof[0] 'ObservationSucceeded') -isnot [bool] -or
                -not $proof[0].ObservationSucceeded -or (Get-P6Field $proof[0] 'Exists') -isnot [bool] -or $proof[0].Exists) { throw 'invalid' }
        }
        foreach ($listener in $listenerEvidence.Items) { if ($listener.LocalPort -in $Receipt.Ports) { throw 'invalid' } }
        $pending=New-Object 'Collections.Generic.Stack[string]'
        $pending.Push($canonical)
        while ($pending.Count -gt 0) {
            foreach ($item in @(Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop)) {
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'invalid' }
                if ($item.PSIsContainer) { $pending.Push($item.FullName) }
            }
        }
        return [pscustomobject]@{ Status='PROVEN'; Target=$canonical }
    } catch { throw 'REHEARSAL_REMOVAL_UNPROVEN' }
}
