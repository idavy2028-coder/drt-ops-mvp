[CmdletBinding()]
param([ValidateSet('All','PowerShell','Java')] [string] $Phase = 'All', [string] $NameFilter = '')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:total = 0
$script:passed = 0
$script:failures = New-Object 'System.Collections.Generic.List[string]'
$ops = Split-Path -Parent $PSScriptRoot
$repo = [IO.Path]::GetFullPath((Join-Path $ops '../..'))
$testRoot = Join-Path $repo ('.superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal/contract-tests-' + [guid]::NewGuid().ToString('N'))
[void] [IO.Directory]::CreateDirectory($testRoot)
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')

function Check([bool] $Condition, [string] $Code) { if (-not $Condition) { throw $Code } }
function Case([string] $Name, [scriptblock] $Body) {
    if ($NameFilter -ne '' -and $Name -notlike $NameFilter) { return }
    $script:total++
    try { & $Body; $script:passed++ } catch {
        $code = [string] $_.Exception.Message
        if ($code -cnotmatch '^[A-Z][A-Z0-9_]+$') { $code = 'TEST_INFRASTRUCTURE_ERROR' }
        $script:failures.Add($Name + ':' + $code)
    }
}
function Reject([scriptblock] $Body, [string] $Code) {
    try { & $Body | Out-Null } catch {
        Check ([string] $_.Exception.Message -ceq $Code) 'WRONG_REJECTION_CODE'
        return
    }
    throw 'REJECTION_MISSING'
}
function CopyValue($Value) { return ($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json) }
function ListenerEvidence([object[]] $Items=@()) { return [pscustomobject]@{ Succeeded=$true; Items=$Items } }
function RunTestProcess([string] $Executable, [string[]] $Arguments, [int] $Timeout = 30000, [hashtable] $Environment = @{}) {
    if ($Executable -eq 'powershell.exe') { $Arguments = @('-NonInteractive','-ExecutionPolicy','Bypass') + $Arguments }
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $Executable
    $psi.Arguments = (($Arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($key in @($psi.EnvironmentVariables.Keys)) {
        if ($key -match '^(DRT_|JT_|P6_|JAVA_TOOL_OPTIONS$|_JAVA_OPTIONS$|JDK_JAVA_OPTIONS$)') { $psi.EnvironmentVariables.Remove($key) }
    }
    foreach ($key in $Environment.Keys) { $psi.EnvironmentVariables[$key] = $Environment[$key] }
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'TEST_PROCESS_START_FAILED' }
    try {
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($Timeout)) { throw 'TEST_PROCESS_TIMEOUT' }
        if (-not [Threading.Tasks.Task]::WaitAll(@($outTask, $errTask), 5000)) { throw 'TEST_CAPTURE_TIMEOUT' }
        return [pscustomobject]@{ ExitCode=$process.ExitCode; Out=$outTask.Result; Error=$errTask.Result }
    } finally {
        # 只使用本测试亲自创建且仍持有的进程句柄，不按名字或端口清理。
        if (-not $process.HasExited) { $process.Kill(); [void] $process.WaitForExit(5000) }
        $process.Dispose()
    }
}
function Fixture {
    $runId = [guid]::NewGuid().ToString('N')
    $parent = Join-Path $testRoot 'runs'
    $root = Join-Path $parent ('native-' + $runId)
    [void] [IO.Directory]::CreateDirectory((Join-Path $root 'pgdata'))
    $created = [DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o')
    $marker = [pscustomobject]@{ SchemaVersion=1; RunId=$runId; OwnerNonce=('a' * 64); RunDirectory=$root; CreatedAt=$created; PgPort=45431 }
    $receipt = [pscustomobject]@{
        SchemaVersion=1; RunId=$runId; OwnerNonce=('a' * 64); RunDirectory=$root; CreatedAt=$created
        Ports=@(45431,45432,45433,45434); PgData=(Join-Path $root 'pgdata'); PgDatabase='composite_live'; MigrationDatabase='composite_onboard'
    }
    $recorded = [pscustomobject]@{
        Pid=424242; StartTimeUtc=[DateTimeOffset]::UtcNow.AddSeconds(-20).ToString('o')
        ExecutablePath='C:\Program Files\Java\jdk-21.0.10\bin\java.exe'; WorkingDirectory=$root
        RunId=$runId; OwnerNonce=('a' * 64); Kind='Java'; ArgumentMarker=('-Dp6.rehearsal.run=' + $runId)
    }
    $observed = CopyValue $recorded
    $observed | Add-Member CommandLine ('"C:\Program Files\Java\jdk-21.0.10\bin\java.exe" ' + $recorded.ArgumentMarker + ' -jar "' + $root + '\app.jar"')
    $receipt | Add-Member Processes @($recorded)
    return [pscustomobject]@{ Parent=$parent; Receipt=$receipt; Marker=$marker; Recorded=$recorded; Observed=$observed }
}
function InvokeJavaFixtureStop($Fixture,[scriptblock] $ReadProcess,[scriptblock] $Stop,[scriptblock] $Wait) {
    if ((Get-Command Invoke-P6OwnedStop).Parameters.ContainsKey('RunParent')) {
        $read={ [pscustomobject]@{ Succeeded=$true; Marker=$Fixture.Marker; ObservedProcess=(& $ReadProcess); Listeners=(ListenerEvidence) } }.GetNewClosure()
        return Invoke-P6OwnedStop $Fixture.Receipt $Fixture.Parent $Fixture.Recorded $read $Stop $Wait
    }
    # 仅用于RED阶段重现旧入口；生产代码没有兼容绕过分支。
    return Invoke-P6OwnedStop $Fixture.Receipt $Fixture.Recorded $ReadProcess $Stop $Wait
}
function PgFixture {
    $f=Fixture
    $f.Recorded.Kind='Postgres'; $f.Recorded.ExecutablePath='C:\Program Files\PostgreSQL\17\bin\postgres.exe'; $f.Recorded.ArgumentMarker=$f.Receipt.PgData
    $f.Observed=CopyValue $f.Recorded
    $f.Observed | Add-Member CommandLine ('postgres.exe -D "'+$f.Receipt.PgData+'" -h 127.0.0.1 -p 45431')
    $epoch=([DateTimeOffset]::Parse($f.Recorded.StartTimeUtc)).ToUnixTimeSeconds()
    $lines=@('424242',$f.Receipt.PgData,[string]$epoch,'45431','','127.0.0.1','','ready')
    $listeners=ListenerEvidence @([pscustomobject]@{ ObservationSucceeded=$true; LocalAddress='127.0.0.1'; LocalPort=45431; OwningProcess=424242 })
    $f | Add-Member Evidence ([pscustomobject]@{ Succeeded=$true; Marker=$f.Marker; ObservedProcess=$f.Observed; PidFileLines=$lines; Listeners=$listeners })
    return $f
}
function StoppedEvidence($Fixture) {
    return [pscustomobject]@{ Succeeded=$true; Marker=$Fixture.Marker; ObservedProcess=$null; PidFileLines=@(); Listeners=(ListenerEvidence) }
}
function InvokePgFixtureStop($Fixture,[scriptblock] $Read,[scriptblock] $Stop,[scriptblock] $Wait) {
    if ($null -ne (Get-Command Stop-P6OwnedPostgres -ErrorAction SilentlyContinue)) {
        return Stop-P6OwnedPostgres $Fixture.Receipt $Fixture.Parent $Fixture.Recorded $Read $Stop $Wait
    }
    $readProcess={ $evidence=& $Read; return $evidence.ObservedProcess }.GetNewClosure()
    return Invoke-P6OwnedStop $Fixture.Receipt $Fixture.Recorded $readProcess $Stop $Wait
}

if ($Phase -cin @('All','PowerShell')) {
    foreach ($shape in @('receipt_schema','marker_drift','wrong_parent','pidfile_data','pidfile_epoch','wildcard_listener','empty_listeners','failed_evidence','string_success','missing_marker','missing_pidfile','missing_listeners','failed_listener_query','malformed_listener')) {
        Case ('fix1_I3_pg_rejects_'+$shape) {
            $f=PgFixture
            switch ($shape) {
                'receipt_schema' { $f.Receipt.SchemaVersion=999 }
                'marker_drift' { $f.Evidence.Marker.OwnerNonce='b'*64 }
                'wrong_parent' { $f.Parent=Join-Path $testRoot 'unowned-parent' }
                'pidfile_data' { $f.Evidence.PidFileLines[1]=$testRoot }
                'pidfile_epoch' { $f.Evidence.PidFileLines[2]='1' }
                'wildcard_listener' { $f.Evidence.Listeners.Items[0].LocalAddress='0.0.0.0' }
                'empty_listeners' { $f.Evidence.Listeners=ListenerEvidence }
                'failed_evidence' { $f.Evidence.Succeeded=$false }
                'string_success' { $f.Evidence.Succeeded='true' }
                'missing_marker' { $f.Evidence.PSObject.Properties.Remove('Marker') }
                'missing_pidfile' { $f.Evidence.PSObject.Properties.Remove('PidFileLines') }
                'missing_listeners' { $f.Evidence.PSObject.Properties.Remove('Listeners') }
                'failed_listener_query' { $f.Evidence.Listeners.Succeeded=$false }
                'malformed_listener' { $f.Evidence.Listeners.Items[0].PSObject.Properties.Remove('LocalPort') }
            }
            $calls=New-Object 'Collections.Generic.List[string]'
            $r=InvokePgFixtureStop $f { if($calls.Count -eq 0){$f.Evidence}else{StoppedEvidence $f} } { $calls.Add('stop') } { $true }
            Check ($r.Status -ceq 'RETAINED' -and $calls.Count -eq 0) 'UNPROVEN_PG_STOP_CALLED'
            Check ($r.Code -cmatch '^REHEARSAL_[A-Z_]+$') 'UNSAFE_STOP_CODE'
        }
    }
    Case 'fix1_I3_pg_valid_evidence_stops_once' {
        $f=PgFixture; $calls=New-Object 'Collections.Generic.List[string]'
        $r=InvokePgFixtureStop $f { if($calls.Count -eq 0){$f.Evidence}else{StoppedEvidence $f} } { $calls.Add('stop') } { $true }
        Check ($null -ne (Get-Command Stop-P6OwnedPostgres -ErrorAction SilentlyContinue)) 'PG_SAFE_ENTRY_MISSING'
        Check ($r.Status -ceq 'STOPPED' -and $calls.Count -eq 1) 'VALID_PG_STOP_FAILED'
    }
    Case 'fix1_I3_generic_stop_cannot_accept_postgres' {
        $f=PgFixture; $calls=New-Object 'Collections.Generic.List[string]'
        $r=InvokeJavaFixtureStop $f { if($calls.Count -eq 0){$f.Observed}else{$null} } { $calls.Add('stop') } { $true }
        Check ($r.Status -ceq 'RETAINED' -and $calls.Count -eq 0) 'PG_BYPASSED_DEDICATED_ENTRY'
    }
    foreach ($shape in @('receipt_schema','marker_drift','wrong_parent')) {
        Case ('fix1_I3_java_rejects_'+$shape) {
            $f=Fixture
            switch($shape) { 'receipt_schema' {$f.Receipt.SchemaVersion=999}; 'marker_drift' {$f.Marker.OwnerNonce='b'*64}; 'wrong_parent' {$f.Parent=Join-Path $testRoot 'unowned-parent'} }
            $calls=New-Object 'Collections.Generic.List[string]'
            $r=InvokeJavaFixtureStop $f { if($calls.Count -eq 0){$f.Observed}else{$null} } { $calls.Add('stop') } { $true }
            Check ($r.Status -ceq 'RETAINED' -and $calls.Count -eq 0) 'UNPROVEN_JAVA_STOP_CALLED'
        }
    }
    foreach ($shape in @('read_failure','stop_failure','timeout','still_alive','post_read_failure')) {
        Case ('fix1_I3_pg_retains_'+$shape) {
            $f=PgFixture; $calls=New-Object 'Collections.Generic.List[string]'
            $read={
                if($shape -eq 'read_failure' -or ($shape -eq 'post_read_failure' -and $calls.Count -gt 0)){throw 'SYNTHETIC_SECRET_DO_NOT_LOG'}
                if($calls.Count -eq 0 -or $shape -eq 'still_alive'){$f.Evidence}else{StoppedEvidence $f}
            }
            $stop={ $calls.Add('stop'); if($shape -eq 'stop_failure'){throw 'SYNTHETIC_SECRET_DO_NOT_LOG'} }
            $r=InvokePgFixtureStop $f $read $stop { $shape -ne 'timeout' }
            Check ($r.Status -ceq 'RETAINED') 'PG_FAILURE_NOT_RETAINED'
            if($shape -eq 'read_failure'){Check ($calls.Count -eq 0) 'FAILED_READ_STOPPED'}
            Check (($r|ConvertTo-Json -Compress) -notmatch 'SECRET|[A-Z]:\\') 'PG_FAILURE_LEAK'
        }
    }
    Case 'fix1_I2_successful_empty_listener_evidence' {
        $f=Fixture; $f.Receipt.Processes=@()
        $r=Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData @() (ListenerEvidence)
        Check ($r.Status -ceq 'PROVEN') 'EMPTY_SUCCESS_REJECTED'
    }
    foreach ($shape in @('failed_query','missing_success','missing_items','raw_array','null_item','failed_item','missing_port','string_port','bad_port','missing_address','bad_address','missing_pid','string_pid','bad_pid')) {
        Case ('fix1_I2_rejects_'+$shape) {
            $f=Fixture; $f.Receipt.Processes=@()
            $item=[pscustomobject]@{ ObservationSucceeded=$true; LocalAddress='127.0.0.1'; LocalPort=45555; OwningProcess=5678 }
            $evidence=ListenerEvidence @($item)
            switch ($shape) {
                'failed_query' { $evidence.Succeeded=$false }
                'missing_success' { $evidence.PSObject.Properties.Remove('Succeeded') }
                'missing_items' { $evidence.PSObject.Properties.Remove('Items') }
                'raw_array' { $evidence=@() }
                'null_item' { $evidence.Items=@($null) }
                'failed_item' { $item.ObservationSucceeded=$false }
                'missing_port' { $item.PSObject.Properties.Remove('LocalPort') }
                'string_port' { $item.LocalPort='45555' }
                'bad_port' { $item.LocalPort=70000 }
                'missing_address' { $item.PSObject.Properties.Remove('LocalAddress') }
                'bad_address' { $item.LocalAddress='SYNTHETIC_SECRET_INVALID_HOST' }
                'missing_pid' { $item.PSObject.Properties.Remove('OwningProcess') }
                'string_pid' { $item.OwningProcess='5678' }
                'bad_pid' { $item.OwningProcess=-1 }
            }
            Reject { Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData @() $evidence } 'REHEARSAL_REMOVAL_UNPROVEN'
        }
    }
    Case 'fix1_I1_distinct_root_rejects_original_token' {
        $firstRoot=Join-Path $testRoot 'identical-a'
        $secondRoot=Join-Path $testRoot 'identical-b'
        foreach ($root in @($firstRoot,$secondRoot)) {
            $target=Join-Path $root 'tools/ops-safety'
            [void][IO.Directory]::CreateDirectory($target)
            [IO.File]::Copy((Join-Path $ops 'p6-composite-isolation-lib.ps1'),(Join-Path $target 'p6-composite-isolation-lib.ps1'))
        }
        $first=Get-P6IsolationPlan $firstRoot ([pscustomobject]@{ Head='f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e'; Branch='codex/p6-2-ops-safety-gates'; Root=$firstRoot })
        $second=Get-P6IsolationPlan $secondRoot ([pscustomobject]@{ Head='f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e'; Branch='codex/p6-2-ops-safety-gates'; Root=$secondRoot })
        Check ($first.Fingerprint -cne $second.Fingerprint) 'ROOT_NOT_BOUND'
        Reject { Assert-P6Confirmation $second $first.Fingerprint } 'REHEARSAL_CONFIRMATION_INVALID'
        $same=Get-P6IsolationPlan $firstRoot.ToUpperInvariant() ([pscustomobject]@{ Head='f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e'; Branch='codex/p6-2-ops-safety-gates'; Root=$firstRoot })
        Check ($same.Fingerprint -ceq $first.Fingerprint) 'ROOT_CASE_NOT_CANONICAL'
    }
    Case 'plan_default_runner_no_file_mutations' {
        $before = @(Get-ChildItem $repo -Force | ForEach-Object { $_.Name + '|' + $_.LastWriteTimeUtc.Ticks }) -join "`n"
        $r = RunTestProcess 'powershell.exe' @('-NoProfile','-File',(Join-Path $ops 'Invoke-P6CompositeIsolationRehearsal.ps1'))
        $after = @(Get-ChildItem $repo -Force | ForEach-Object { $_.Name + '|' + $_.LastWriteTimeUtc.Ticks }) -join "`n"
        Check ($r.ExitCode -eq 0 -and $r.Out -cmatch 'P6_REHEARSAL_STATUS=PLAN') 'PLAN_MISSING'
        Check ($r.Out -cmatch 'ACTIONS=0' -and $r.Out -cmatch 'FINGERPRINT=[a-f0-9]{64}') 'PLAN_CONTRACT_MISSING'
        Check ($before -ceq $after) 'PLAN_CREATED_FILES'
        Check ($r.Error -eq '' -and $r.Out -notmatch '[A-Z]:\\') 'PLAN_PATH_LEAK'
    }
    Case 'execute_is_inert_even_with_secret_input' {
        $sentinel = 'SYNTHETIC_SECRET_DO_NOT_LOG_819273'
        $r = RunTestProcess 'powershell.exe' @('-NoProfile','-File',(Join-Path $ops 'Invoke-P6CompositeIsolationRehearsal.ps1'),'-Mode','Execute','-ConfirmationToken',$sentinel)
        Check ($r.ExitCode -eq 1 -and $r.Out -cmatch 'CODE=REHEARSAL_EXECUTE_NOT_IMPLEMENTED ACTIONS=0') 'EXECUTE_NOT_INERT'
        Check (($r.Out + $r.Error) -notmatch $sentinel -and $r.Error -eq '') 'SECRET_LEAK'
    }
    Case 'runner_refuses_unknown_path_or_endpoint_parameter' {
        $r=RunTestProcess 'powershell.exe' @('-NoProfile','-File',(Join-Path $ops 'Invoke-P6CompositeIsolationRehearsal.ps1'),'-Endpoint','SYNTHETIC_SECRET_DO_NOT_LOG')
        Check ($r.ExitCode -eq 1 -and $r.Out -cmatch 'CODE=REHEARSAL_ARGUMENT_INVALID ACTIONS=0') 'UNKNOWN_PARAMETER_NOT_REJECTED'
        Check (($r.Out+$r.Error) -notmatch 'SECRET|[A-Z]:\\') 'UNKNOWN_PARAMETER_LEAK'
    }
    foreach ($mode in @('plan','Bogus','SYNTHETIC_SECRET_DO_NOT_LOG')) {
        Case ('invalid_mode_' + $mode.Length) {
            $r = RunTestProcess 'powershell.exe' @('-NoProfile','-File',(Join-Path $ops 'Invoke-P6CompositeIsolationRehearsal.ps1'),'-Mode',$mode)
            Check ($r.ExitCode -eq 1 -and $r.Out -cmatch 'CODE=REHEARSAL_MODE_INVALID ACTIONS=0') 'MODE_NOT_REJECTED'
            Check ($r.Error -eq '' -and $r.Out -notmatch 'SYNTHETIC_SECRET') 'MODE_LEAK'
        }
    }
    Case 'plan_rejects_non_target_head' {
        Reject { Get-P6IsolationPlan $repo ([pscustomobject]@{ Head=('0' * 40); Branch='codex/p6-2-ops-safety-gates'; Root=$repo }) } 'REHEARSAL_HEAD_MISMATCH'
    }
    Case 'plan_rejects_non_target_branch' {
        Reject { Get-P6IsolationPlan $repo ([pscustomobject]@{ Head='f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e'; Branch='main'; Root=$repo }) } 'REHEARSAL_HEAD_MISMATCH'
    }
    Case 'plan_hash_binds_tool_bytes_and_confirmation' {
        $state = [pscustomobject]@{ Head='f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e'; Branch='codex/p6-2-ops-safety-gates'; Root=$repo }
        $a = Get-P6IsolationPlan $repo $state
        Assert-P6Confirmation $a $a.Fingerprint
        Reject { Assert-P6Confirmation $a ('0' * 64) } 'REHEARSAL_CONFIRMATION_INVALID'
        Reject { Assert-P6Confirmation $a ($a.Fingerprint.ToUpperInvariant()) } 'REHEARSAL_CONFIRMATION_INVALID'
        Check ($a.Actions -eq 0 -and $a.Fingerprint -cmatch '^[a-f0-9]{64}$') 'FINGERPRINT_INVALID'
    }
    Case 'plan_copy_hash_changes_when_tool_bytes_change' {
        $copyRoot=Join-Path $testRoot 'plan-copy'
        $copyTools=Join-Path $copyRoot 'tools/ops-safety'
        [void][IO.Directory]::CreateDirectory($copyTools)
        $copySource=Join-Path $copyTools 'p6-composite-isolation-lib.ps1'
        [IO.File]::Copy((Join-Path $ops 'p6-composite-isolation-lib.ps1'),$copySource)
        $state=[pscustomobject]@{ Head='f1d3b42dac7c945fc9b9f5aa7f989790909a1d2e'; Branch='codex/p6-2-ops-safety-gates'; Root=$copyRoot }
        $a=Get-P6IsolationPlan $copyRoot $state
        [IO.File]::AppendAllText($copySource,"`n# synthetic test mutation`n")
        $b=Get-P6IsolationPlan $copyRoot $state
        Check ($a.Fingerprint -cne $b.Fingerprint) 'TOOL_DRIFT_NOT_BOUND'
        Reject { Assert-P6Confirmation $b $a.Fingerprint } 'REHEARSAL_CONFIRMATION_INVALID'
    }
    Case 'plan_refuses_git_environment_root_injection' {
        $r=RunTestProcess 'powershell.exe' @('-NoProfile','-File',(Join-Path $ops 'Invoke-P6CompositeIsolationRehearsal.ps1')) 30000 @{ GIT_DIR='SYNTHETIC_SECRET_INVALID_GIT_DIRECTORY'; GIT_WORK_TREE='SYNTHETIC_SECRET_INVALID_WORKTREE' }
        Check ($r.ExitCode -eq 0 -and $r.Out -cmatch 'P6_REHEARSAL_STATUS=PLAN' -and $r.Error -eq '') 'GIT_ENV_INJECTION_NOT_CLEARED'
        Check ($r.Out -notmatch 'SYNTHETIC_SECRET') 'GIT_ENV_LEAK'
    }
    Case 'path_accepts_only_strict_child' {
        $f = Fixture
        $p = Assert-P6ChildPath $f.Receipt.RunDirectory $f.Receipt.PgData -MustExist
        Check ($p -ceq $f.Receipt.PgData) 'CHILD_PATH_REJECTED'
        Reject { Assert-P6ChildPath $f.Receipt.RunDirectory $f.Receipt.RunDirectory } 'REHEARSAL_PATH_INVALID'
        Reject { Assert-P6ChildPath $f.Receipt.RunDirectory ($f.Receipt.RunDirectory + '-other\pgdata') } 'REHEARSAL_PATH_INVALID'
        Reject { Assert-P6ChildPath $f.Receipt.RunDirectory (Join-Path $f.Receipt.RunDirectory '..\escape') } 'REHEARSAL_PATH_INVALID'
        Reject { Assert-P6ChildPath $f.Receipt.RunDirectory (Join-Path $f.Receipt.RunDirectory 'pgdata:secret') } 'REHEARSAL_PATH_INVALID'
        Reject { Assert-P6ChildPath $f.Receipt.RunDirectory '\\server\share\pgdata' } 'REHEARSAL_PATH_INVALID'
    }
    Case 'receipt_accepts_exact_marker_and_layout' {
        $f = Fixture
        Assert-P6Receipt $f.Receipt $f.Parent $f.Marker | Out-Null
    }
    Case 'initial_receipt_with_no_started_process_is_valid' {
        $f=Fixture
        $f.Receipt.Processes=@()
        Assert-P6Receipt $f.Receipt $f.Parent $f.Marker | Out-Null
        Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData @() (ListenerEvidence) | Out-Null
    }
    foreach ($field in @('RunId','OwnerNonce','RunDirectory','CreatedAt','PgPort')) {
        Case ('receipt_rejects_marker_drift_' + $field) {
            $f = Fixture
            if ($field -eq 'PgPort') { $f.Marker.PgPort=45440 } else { $f.Marker.$field='drift' }
            Reject { Assert-P6Receipt $f.Receipt $f.Parent $f.Marker } 'REHEARSAL_RECEIPT_INVALID'
        }
    }
    Case 'receipt_rejects_existing_directory_as_new' {
        $f = Fixture
        Reject { Assert-P6NewRunDirectory $f.Parent $f.Receipt.RunDirectory } 'REHEARSAL_RUN_EXISTS'
    }
    Case 'ports_reject_nonloopback_existing_and_duplicate' {
        Assert-P6LoopbackPorts @(45431,45432,45433,45434) @() | Out-Null
        Reject { Assert-P6LoopbackPorts @(45431,45432,45433,45433) @() } 'REHEARSAL_PORT_INVALID'
        Reject { Assert-P6LoopbackPorts @(45431,45432,45433,45434) @([pscustomobject]@{ LocalAddress='127.0.0.1'; LocalPort=45432; OwningProcess=7 }) } 'REHEARSAL_PORT_OCCUPIED'
        Reject { Assert-P6LoopbackPorts @(45431,45432,45433,'host:45434') @() } 'REHEARSAL_PORT_INVALID'
    }
    Case 'process_exact_identity_is_accepted' {
        $f = Fixture
        Assert-P6ProcessIdentity $f.Receipt $f.Recorded $f.Observed | Out-Null
    }
    foreach ($field in @('Pid','StartTimeUtc','ExecutablePath','WorkingDirectory','CommandLine')) {
        Case ('process_refuses_reuse_or_drift_' + $field) {
            $f = Fixture
            if ($field -eq 'Pid') { $f.Observed.Pid=424243 } else { $f.Observed.$field='drift' }
            Reject { Assert-P6ProcessIdentity $f.Receipt $f.Recorded $f.Observed } 'REHEARSAL_PROCESS_UNPROVEN'
        }
    }
    Case 'process_refuses_marker_prefix_match' {
        $f = Fixture
        $f.Observed.CommandLine = $f.Recorded.ArgumentMarker + 'extra'
        Reject { Assert-P6ProcessIdentity $f.Receipt $f.Recorded $f.Observed } 'REHEARSAL_PROCESS_UNPROVEN'
    }
    Case 'stop_refuses_reused_pid_without_calling_stop' {
        $f = Fixture; $f.Observed.Pid=9
        $calls = New-Object 'System.Collections.Generic.List[string]'
        $r = InvokeJavaFixtureStop $f { $f.Observed } { $calls.Add('stop') } { $true }
        Check ($r.Status -ceq 'RETAINED' -and $r.Code -ceq 'REHEARSAL_PROCESS_UNPROVEN' -and $calls.Count -eq 0) 'UNOWNED_PROCESS_STOPPED'
    }
    Case 'stop_failure_retains_and_suppresses_exception_secret' {
        $f = Fixture
        $r = InvokeJavaFixtureStop $f { $f.Observed } { throw 'SYNTHETIC_SECRET_DO_NOT_LOG' } { $true }
        Check ($r.Status -ceq 'RETAINED' -and $r.Code -ceq 'REHEARSAL_STOP_FAILED') 'STOP_FAILURE_NOT_RETAINED'
        Check (($r | ConvertTo-Json -Compress) -notmatch 'SECRET|[A-Z]:\\') 'STOP_REPORT_LEAK'
    }
    Case 'stop_timeout_retains' {
        $f = Fixture
        $r = InvokeJavaFixtureStop $f { $f.Observed } {} { $false }
        Check ($r.Status -ceq 'RETAINED' -and $r.Code -ceq 'REHEARSAL_STOP_TIMEOUT') 'TIMEOUT_NOT_RETAINED'
    }
    Case 'stop_success_requires_second_absent_observation' {
        $f = Fixture; $calls = New-Object 'System.Collections.Generic.List[string]'
        $r = InvokeJavaFixtureStop $f { if ($calls.Count -eq 0) { $f.Observed } else { $null } } { $calls.Add('stop') } { $true }
        Check ($r.Status -ceq 'STOPPED' -and $calls.Count -eq 1) 'OWNED_STOP_NOT_CONFIRMED'
    }
    Case 'stop_false_success_retains' {
        $f = Fixture
        $r = InvokeJavaFixtureStop $f { $f.Observed } {} { $true }
        Check ($r.Status -ceq 'RETAINED') 'LIVE_PROCESS_DECLARED_STOPPED'
    }
    Case 'process_record_must_belong_to_receipt' {
        $f=Fixture
        $f.Receipt.Processes=@()
        Reject { Assert-P6ProcessIdentity $f.Receipt $f.Recorded $f.Observed } 'REHEARSAL_PROCESS_UNPROVEN'
    }
    Case 'pg_exact_data_pid_epoch_and_loopback_are_required' {
        $f = Fixture
        $f.Recorded.Kind='Postgres'; $f.Recorded.ExecutablePath='C:\Program Files\PostgreSQL\17\bin\postgres.exe'; $f.Recorded.ArgumentMarker=$f.Receipt.PgData
        $f.Observed = CopyValue $f.Recorded
        $f.Observed | Add-Member CommandLine ('postgres.exe -D "' + $f.Receipt.PgData + '" -h 127.0.0.1 -p 45431')
        $epoch = ([DateTimeOffset]::Parse($f.Recorded.StartTimeUtc)).ToUnixTimeSeconds()
        $lines = @('424242',$f.Receipt.PgData,[string]$epoch,'45431','','127.0.0.1','','ready')
        $listeners = @([pscustomobject]@{ LocalAddress='127.0.0.1'; LocalPort=45431; OwningProcess=424242 })
        Assert-P6PgIdentity $f.Receipt $f.Recorded $f.Observed $lines $listeners | Out-Null
        $listeners[0].LocalAddress='0.0.0.0'
        Reject { Assert-P6PgIdentity $f.Receipt $f.Recorded $f.Observed $lines $listeners } 'REHEARSAL_PG_UNPROVEN'
        $listeners[0].LocalAddress='127.0.0.1'; $lines[1]=$testRoot
        Reject { Assert-P6PgIdentity $f.Receipt $f.Recorded $f.Observed $lines $listeners } 'REHEARSAL_PG_UNPROVEN'
    }
    Case 'removal_requires_absent_processes_ports_and_correct_marker' {
        $f=Fixture
        $proof=@([pscustomobject]@{ Pid=$f.Recorded.Pid; ObservationSucceeded=$true; Exists=$false })
        Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData $proof (ListenerEvidence) | Out-Null
        Reject { Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData @() (ListenerEvidence) } 'REHEARSAL_REMOVAL_UNPROVEN'
        Reject { Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData @($f.Observed) (ListenerEvidence) } 'REHEARSAL_REMOVAL_UNPROVEN'
        Reject { Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.RunDirectory $proof (ListenerEvidence) } 'REHEARSAL_REMOVAL_UNPROVEN'
        Reject { Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData $proof (ListenerEvidence @([pscustomobject]@{ ObservationSucceeded=$true; LocalAddress='127.0.0.1'; LocalPort=45431; OwningProcess=9 })) } 'REHEARSAL_REMOVAL_UNPROVEN'
    }
    Case 'removal_observation_must_prove_each_recorded_pid_absent' {
        $f=Fixture
        foreach ($proof in @(
            @([pscustomobject]@{ Pid=$f.Recorded.Pid; ObservationSucceeded=$false; Exists=$false }),
            @([pscustomobject]@{ Pid=$f.Recorded.Pid; ObservationSucceeded=$true; Exists=$true }),
            @([pscustomobject]@{ Pid=9; ObservationSucceeded=$true; Exists=$false }))) {
            Reject { Assert-P6RemovalProof $f.Receipt $f.Parent $f.Marker $f.Receipt.PgData $proof (ListenerEvidence) } 'REHEARSAL_REMOVAL_UNPROVEN'
        }
    }
}

if ($Phase -cin @('All','Java')) {
    # JDBC动态代理只代替I/O；真实runAction负责固定SQL、事务、白名单与失败停止。
    $source = @'
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
public class P6CompositeFlywayContractTest {
    static int total, passed;
    interface Test { void run() throws Exception; }
    static void check(boolean condition, String code) { if (!condition) throw new AssertionError(code); }
    static void test(String name, Test body) {
        total++;
        try { body.run(); passed++; } catch(Throwable failure) {
            System.out.println("FAIL=" + name + ":" + (failure instanceof AssertionError ? failure.getMessage() : "CONTRACT_NOT_SATISFIED"));
        }
    }
    static class Db implements InvocationHandler {
        List<String> events=new ArrayList<>();
        int terminals=0, systems=0, demos=2, changed=2; boolean auto=true; String fail="";
        Connection connection() { return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},this); }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name=method.getName();
            if (name.equals("getAutoCommit")) return auto;
            if (name.equals("setAutoCommit")) { events.add("auto:"+args[0]); return null; }
            if (name.equals("setTransactionIsolation")) { check((int)args[0]==Connection.TRANSACTION_SERIALIZABLE,"ISOLATION"); events.add("serializable"); return null; }
            if (name.equals("commit") || name.equals("rollback") || name.equals("close")) { events.add(name); if(fail.equals(name)) throw new SQLException("SYNTHETIC_SECRET"); return null; }
            if (name.equals("createStatement")) return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                if(m.getName().equals("setQueryTimeout")) { check((int)a[0]>0 && (int)a[0]<=10,"UNBOUNDED_QUERY"); events.add("timeout"); return null; }
                if(m.getName().equals("close")) return null;
                String sql=(String)a[0]; events.add(sql);
                if(fail.equals(sql) || fail.equals("sql")) throw new SQLException("SYNTHETIC_SECRET");
                if(m.getName().equals("execute")) return false;
                if(m.getName().equals("executeUpdate")) return changed;
                int value=switch(sql) {
                    case "SELECT count(*) FROM jt_terminals" -> terminals;
                    case "SELECT count(*) FROM onboard_systems" -> systems;
                    case "SELECT count(*) FROM vehicles WHERE id IN ('33333333-3333-3333-3333-333333333331','33333333-3333-3333-3333-333333333332') AND dispatchable = true" -> demos;
                    default -> throw new AssertionError("ARBITRARY_SQL");
                };
                int[] cursor={0};
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResultSet.class},(rp,rm,ra)->switch(rm.getName()) {
                    case "next" -> ++cursor[0]==1;
                    case "getLong" -> (long)value;
                    case "close" -> null;
                    default -> throw new AssertionError("UNEXPECTED_RESULT_CALL");
                });
            });
            throw new AssertionError("UNEXPECTED_JDBC_CALL_"+name);
        }
    }
    static class Migration implements P6CompositeFlywayTool.Migrations {
        List<String> events=new ArrayList<>();
        public void migrate(String target) { events.add(target); }
        public void validate() { events.add("validate"); }
    }
    static void rejected(String action, Db db, Migration migration, String code) throws Exception {
        try { P6CompositeFlywayTool.runAction(action,db::connection,migration); throw new AssertionError("REJECTION_MISSING"); }
        catch(IllegalStateException e) { check(e.getMessage().equals(code),"WRONG_CODE"); }
    }
    public static void main(String[] args) {
        for(String action : List.of("MIGRATE_19","MIGRATE_20","MIGRATE_21","VALIDATE")) test("fixed_"+action,()->{
            Db db=new Db(); Migration m=new Migration(); P6CompositeFlywayTool.runAction(action,db::connection,m);
            String expected=action.equals("VALIDATE")?"validate":action.substring(8);
            check(m.events.equals(List.of(expected)),"WRONG_MIGRATION_TARGET"); check(db.events.isEmpty(),"MIGRATION_OPENED_SQL");
        });
        for(String action : Arrays.asList(null,"","migrate_19","MIGRATE_22","REPAIR","SQL","MIGRATE_19; DROP DATABASE x")) test("reject_action_"+total,()->{
            Db db=new Db(); Migration m=new Migration(); rejected(action,db,m,"REHEARSAL_FLYWAY_ACTION_INVALID");
            check(db.events.isEmpty() && m.events.isEmpty(),"INVALID_ACTION_SIDE_EFFECT");
        });
        test("prepare_exact_two_and_transaction",()->{
            Db db=new Db(); Migration m=new Migration(); P6CompositeFlywayTool.runAction("PREPARE_V20",db::connection,m);
            check(m.events.isEmpty(),"PREPARE_MIGRATED");
            check(db.events.contains("UPDATE vehicles SET dispatchable = false WHERE id IN ('33333333-3333-3333-3333-333333333331','33333333-3333-3333-3333-333333333332') AND dispatchable = true"),"UPDATE_NOT_FIXED");
            check(db.events.indexOf("auto:false")>=0 && db.events.indexOf("commit")>db.events.indexOf("auto:false"),"NO_TRANSACTION");
            check(db.events.get(db.events.size()-1).equals("close") && !db.events.contains("rollback"),"SUCCESS_NOT_CLOSED");
            check(db.events.contains("LOCK TABLE jt_terminals, onboard_systems, vehicles IN SHARE ROW EXCLUSIVE MODE"),"RACE_UNLOCKED");
        });
        for(String shape : List.of("terminals","systems","demos","rowcount")) test("prepare_reject_"+shape,()->{
            Db db=new Db(); switch(shape) {case "terminals"->db.terminals=1;case "systems"->db.systems=1;case "demos"->db.demos=1;case "rowcount"->db.changed=1;}
            rejected("PREPARE_V20",db,new Migration(),"REHEARSAL_FLYWAY_FIXTURE_INVALID");
            check(!db.events.contains("commit") && db.events.contains("rollback") && db.events.get(db.events.size()-1).equals("close"),"FAILED_FIXTURE_COMMITTED");
            if(!shape.equals("rowcount")) check(db.events.stream().noneMatch(x->x.startsWith("UPDATE")),"INVALID_SHAPE_UPDATED");
        });
        test("prepare_sql_exception_suppressed_and_rolled_back",()->{
            Db db=new Db(); db.fail="sql"; rejected("PREPARE_V20",db,new Migration(),"REHEARSAL_FLYWAY_DATABASE_FAILED");
            check(db.events.contains("rollback") && !db.events.contains("commit"),"SQL_FAILURE_NO_ROLLBACK");
        });
        test("prepare_refuses_borrowed_transaction",()->{
            Db db=new Db(); db.auto=false; rejected("PREPARE_V20",db,new Migration(),"REHEARSAL_FLYWAY_FIXTURE_INVALID");
            check(!db.events.contains("commit") && db.events.stream().noneMatch(x->x.startsWith("UPDATE")),"BORROWED_TRANSACTION_MUTATED");
        });
        test("prepare_rollback_failure_is_not_reported_clean",()->{
            Db db=new Db(); db.changed=0; db.fail="rollback";
            rejected("PREPARE_V20",db,new Migration(),"REHEARSAL_FLYWAY_ROLLBACK_UNPROVEN");
            check(!db.events.contains("commit") && db.events.contains("close"),"ROLLBACK_FAILURE_NOT_CLOSED");
        });
        test("migration_exception_does_not_escape",()->{
            try {
                P6CompositeFlywayTool.runAction("MIGRATE_19",()->{throw new AssertionError("OPENED_SQL");},new P6CompositeFlywayTool.Migrations(){
                    public void migrate(String t) {throw new IllegalStateException("SYNTHETIC_SECRET");}
                    public void validate() {throw new AssertionError("UNEXPECTED_VALIDATE");}
                });
                throw new AssertionError("REJECTION_MISSING");
            } catch(IllegalStateException e) {check(e.getMessage().equals("REHEARSAL_FLYWAY_DATABASE_FAILED") && e.getCause()==null,"MIGRATION_SECRET_LEAK");}
        });
        Map<String,String> env=new HashMap<>();
        env.put("P6_REHEARSAL_RUN_ID","1".repeat(32)); env.put("P6_REHEARSAL_OWNER_NONCE","a".repeat(64));
        env.put("P6_REHEARSAL_RUN_DIRECTORY","D:\\synthetic\\native-"+"1".repeat(32));
        env.put("P6_REHEARSAL_JDBC_URL","jdbc:postgresql://127.0.0.1:45431/composite_live");
        env.put("P6_REHEARSAL_DB_USER","composite"); env.put("P6_REHEARSAL_DB_PASSWORD","SYNTHETIC_SECRET_"+"x".repeat(32)); env.put("P6_REHEARSAL_ACTION","PREPARE_V20");
        Properties marker=new Properties(); marker.setProperty("SchemaVersion","1"); marker.setProperty("PgPort","45431");
        marker.setProperty("RunId",env.get("P6_REHEARSAL_RUN_ID"));marker.setProperty("OwnerNonce",env.get("P6_REHEARSAL_OWNER_NONCE"));marker.setProperty("RunDirectory",env.get("P6_REHEARSAL_RUN_DIRECTORY"));
        test("environment_exact_run_and_loopback",()->P6CompositeFlywayTool.validateEnvironment(env,marker));
        for(String bad : List.of("jdbc:postgresql://localhost:45431/composite_live","jdbc:postgresql://10.0.0.1:45431/composite_live","jdbc:postgresql://127.0.0.1:45432/composite_live","jdbc:postgresql://127.0.0.1:45431/composite_onboard","jdbc:postgresql://127.0.0.1:45431/composite_live?options=-c%20foo=bar")) test("environment_endpoint_reject_"+total,()->{
            Map<String,String> broken=new HashMap<>(env); broken.put("P6_REHEARSAL_JDBC_URL",bad);
            try { P6CompositeFlywayTool.validateEnvironment(broken,marker);throw new AssertionError("REJECTION_MISSING"); }
            catch(IllegalStateException e) {check(e.getMessage().equals("REHEARSAL_FLYWAY_ENDPOINT_INVALID") && e.getCause()==null,"ENDPOINT_ERROR_LEAK");}
        });
        for(String key : List.of("RunId","OwnerNonce","RunDirectory","SchemaVersion","PgPort")) test("marker_drift_"+key,()->{
            Properties broken=new Properties();broken.putAll(marker);broken.setProperty(key,"SYNTHETIC_SECRET");
            try {P6CompositeFlywayTool.validateEnvironment(env,broken);throw new AssertionError("REJECTION_MISSING");}
            catch(IllegalStateException e) {check(e.getMessage().matches("REHEARSAL_FLYWAY_(OWNERSHIP|ENDPOINT)_INVALID"),"MARKER_SECRET_LEAK");}
        });
        System.out.println("P6_FLYWAY_TESTS TOTAL="+total+" PASSED="+passed+" FAILED="+(total-passed));
        System.exit(total==passed?0:1);
    }
}
'@
    $sourcePath = Join-Path $testRoot 'P6CompositeFlywayContractTest.java'
    [IO.File]::WriteAllText($sourcePath,$source,(New-Object Text.UTF8Encoding($false)))
    $javac = 'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
    $java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $repo 'apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar'))
    try {
        $entries=@($archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/flyway-core-[^/]+\.jar$' })
        if ($entries.Count -ne 1) { throw 'JAVA_DEPENDENCY_INFRASTRUCTURE_FAILED' }
        $flywayJar=Join-Path $testRoot 'flyway-core.jar'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entries[0],$flywayJar)
    } finally { $archive.Dispose() }
    $classpath=$testRoot+';'+$flywayJar
    $compile = RunTestProcess $javac @('-encoding','UTF-8','-cp',$flywayJar,'-d',$testRoot,(Join-Path $ops 'fixtures/P6CompositeFlywayTool.java'),$sourcePath)
    if ($compile.ExitCode -ne 0) { throw 'JAVA_COMPILE_INFRASTRUCTURE_FAILED' }
    Case 'java_flyway_contract_suite' {
        $r = RunTestProcess $java @('-cp',$classpath,'P6CompositeFlywayContractTest')
        [Console]::Out.Write($r.Out)
        Check ($r.ExitCode -eq 0 -and $r.Error -eq '') 'JAVA_CONTRACT_FAILED'
    }
    Case 'java_main_rejects_secret_args_without_leak' {
        $r = RunTestProcess $java @('-cp',$classpath,'P6CompositeFlywayTool','SYNTHETIC_SECRET_DO_NOT_LOG')
        Check ($r.ExitCode -eq 1 -and $r.Out -cmatch 'CODE=REHEARSAL_FLYWAY_ARGUMENT_INVALID') 'JAVA_ARGS_NOT_REJECTED'
        Check (($r.Out+$r.Error) -notmatch 'SECRET|Exception|[A-Z]:\\') 'JAVA_SECRET_LEAK'
    }
}
[Console]::Out.WriteLine(('P6_ISOLATION_TESTS TOTAL={0} PASSED={1} FAILED={2}' -f $script:total,$script:passed,($script:total-$script:passed)))
foreach ($failure in $script:failures) { [Console]::Out.WriteLine('FAIL=' + $failure) }
if ($script:failures.Count -gt 0) { exit 1 }
exit 0
