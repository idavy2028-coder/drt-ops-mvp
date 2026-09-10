[CmdletBinding()]
param([ValidateSet('All','Core','CoreIdentityLifecycle','CoreReceiptRemoval','Resources','ResourceStages','ResourceFailureCleanup','Held')][string]$Phase='All',[string]$NameFilter='',[switch]$Diagnose,[switch]$Worker)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
$repo=[IO.Path]::GetFullPath((Join-Path $ops '../..'))
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
function Invoke-ResourceTestHost([string[]]$Arguments,[int]$Timeout=55000) {
    $diagnosticStage='CREATE'
    if($Timeout -le 0 -or $Timeout -gt 55000){throw 'TEST_HOST_DEADLINE_INVALID'}
    $timer=[Diagnostics.Stopwatch]::StartNew();$p=$null;$started=$false;$timedOut=$false
    try {
        $p=New-Object Diagnostics.Process
        $info=New-Object Diagnostics.ProcessStartInfo
        $info.FileName=(Get-Command powershell.exe -CommandType Application|Select-Object -First 1).Source
        $info.Arguments=(@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass')+$Arguments|ForEach-Object{'"'+$_.Replace('"','\"')+'"'}) -join ' '
        $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
        $p.StartInfo=$info
        $diagnosticStage='START'
        $started=$p.Start();if(-not $started){throw 'TEST_HOST_START_FAILED'}
        $diagnosticStage='CAPTURE'
        $out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync()
        $diagnosticStage='WAIT'
        $timedOut= -not $p.WaitForExit([Math]::Max(1,$Timeout-[int]$timer.ElapsedMilliseconds))
        # 只结束亲自启动的原宿主句柄；超时不证明后代已退出，必须 Retained。
        if($timedOut -and -not $p.HasExited){$p.Kill()}
        $diagnosticStage='STOP'
        $remaining=[Math]::Max(0,60000-[int]$timer.ElapsedMilliseconds)
        if(-not $p.WaitForExit([Math]::Min(4000,$remaining))){throw 'TEST_HOST_STOP_UNPROVEN'}
        $remaining=[Math]::Max(0,60000-[int]$timer.ElapsedMilliseconds)
        $diagnosticStage='DRAIN'
        if(-not [Threading.Tasks.Task]::WaitAll(@($out,$err),[Math]::Min(1000,$remaining))){throw 'TEST_HOST_CAPTURE_UNPROVEN'}
        $diagnosticStage='RESULT'
        return [pscustomobject]@{ExitCode=$p.ExitCode;Out=$out.Result;Error=$err.Result;TimedOut=$timedOut;Retained=$timedOut;Code=$(if($timedOut){'TEST_HOST_TIMEOUT'}else{'TEST_HOST_EXITED'});Stopped=$p.HasExited;Elapsed=$timer.ElapsedMilliseconds}
    } catch {
        $safeCode=switch -CaseSensitive($diagnosticStage){
            'CREATE' {'TEST_HOST_CREATE_FAILED'}
            'START' {'TEST_HOST_START_FAILED'}
            'WAIT' {'TEST_HOST_WAIT_FAILED'}
            'STOP' {'TEST_HOST_STOP_UNPROVEN'}
            'RESULT' {'TEST_HOST_RESULT_UNPROVEN'}
            default {'TEST_HOST_CAPTURE_UNPROVEN'}
        }
        if($Diagnose){[Console]::Out.WriteLine(('TEST_DIAG HOST_THROW STAGE={0} DETAIL={1} ELAPSED_MS={2}' -f $diagnosticStage,$safeCode,$timer.ElapsedMilliseconds))}
        throw $safeCode
    } finally {
        try{
            if($started -and -not $p.HasExited){
                $p.Kill()
                $remaining=[Math]::Max(0,60000-[int]$timer.ElapsedMilliseconds)
                if(-not $p.WaitForExit($remaining)){throw 'stop'}
            }
            if($null -ne $p){$p.Dispose()}
        }catch{throw 'TEST_HOST_STOP_UNPROVEN'}
    }
}
function Assert-ResourceHostCompleted($Result) {
    if($Result.TimedOut -or $Result.Retained){throw 'TEST_HOST_TIMEOUT'}
    if(-not $Result.Stopped -or $Result.ExitCode -ne 0 -or $Result.Error -ne ''){throw 'TEST_HOST_FAILED'}
}
function Test-ResourceTransportFailureCodes {
    $original=(Get-Command Invoke-ResourceTestHost).ScriptBlock.ToString()
    $mismatches=0
    foreach($shape in @('START','CAPTURE')){
        $body=$original
        if($shape -ceq 'START'){
            $body=$body.Replace('$info.FileName=(Get-Command powershell.exe -CommandType Application|Select-Object -First 1).Source',"`$info.FileName=[IO.Path]::Combine(`$testRoot,'missing-host.exe')")
            $expected='TEST_HOST_START_FAILED'
        }else{
            $body=$body.Replace('$out=$p.StandardOutput.ReadToEndAsync();$err=$p.StandardError.ReadToEndAsync()',"throw 'SYNTHETIC_CAPTURE_FAILURE'")
            $expected='TEST_HOST_CAPTURE_UNPROVEN'
        }
        $controlled=[scriptblock]::Create($body)
        $actual='NO_FAILURE';try{&$controlled @('-Command','exit 0') 5000|Out-Null}catch{$actual=$_.Exception.Message}
        $matched=$actual -ceq $expected
        [Console]::Out.WriteLine(('TEST_DIAG TRANSPORT_FAILURE SHAPE={0} FIXED_CODE_MATCH={1}' -f $shape,$matched))
        if(-not $matched){$mismatches++}
    }
    Check ($mismatches -eq 0) 'TRANSPORT_FAILURE_CODE_UNSAFE'
}
function Assert-ResourceCaseCoverage([string[]]$Names,[int]$Expected,[string[]]$ExpectedNames=@()) {
    $unique=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    if($Names.Count -ne $Expected){throw 'TEST_HOST_COVERAGE_INVALID'}
    foreach($name in $Names){if($name -cnotmatch '^[A-Za-z0-9_]+$' -or -not $unique.Add($name)){throw 'TEST_HOST_COVERAGE_INVALID'}}
    if($ExpectedNames.Count -gt 0){
        if($ExpectedNames.Count -ne $Expected){throw 'TEST_HOST_COVERAGE_INVALID'}
        foreach($name in $ExpectedNames){if(-not $unique.Remove($name)){throw 'TEST_HOST_COVERAGE_INVALID'}}
        if($unique.Count -ne 0){throw 'TEST_HOST_COVERAGE_INVALID'}
    }
}
function Get-ResourceHostPlan {
    # 全部五十四项各自独立宿主；阶段仅作标签和父级选择，不再组合估时。
    $held=@('D3_java_specs_fixed_args_artifact_hash_and_env_whitelist','D3_java_actual_held_start_stop_API','D3_java_actual_held_start_stop_GW')
    $identity=@('D3_test_host_deadline_retains_and_blocks_next_group','D3_test_host_coverage_rejects_duplicates_and_omissions','D3_executable_case_only_receipt_and_identity','D3_executable_case_only_postgres_command')
    $identity+=@(0..6|ForEach-Object{'D3_executable_rejects_nonidentity_'+$_})
    $identity+=@('D3_lifetime_exact_thirty_minutes_and_clock_rollback','D3_child_health_rechecked_during_actual_wait','D3_child_unknown_stop_retains_original_handle','D3_child_rejects_deadline_above_sixty_seconds_before_start')
    $receipt=@('D3_receipt_chain_zero_pg_api_gw_preserves_predecessors')
    $receipt+=@('disk_drift','backup_exists','role_order','duplicate_pid','wrong_exe','wrong_nonce','modified_prefix'|ForEach-Object{'D3_receipt_refuses_'+$_})
    $receipt+=@('D3_current_receipt_rejects_predecessor_tamper')
    $receipt+=@('success','listener','inventory','query_failure','short_ticket','marker_drift','wrong_root'|ForEach-Object{'D3_removal_'+$_})
    $stages=@('resource_pipeline_real_stage_order_and_safe_report')+@('SNAPSHOT','PG_START','PG_READY','PG_TOOL','API_START','API_READY','API_TOOL','GW_START','GW_READY','GW_TOOL'|ForEach-Object{'resource_pipeline_failure_'+$_})
    $cleanup=@('DEADLINE_API','RETAIN_API','GW_STOP','API_STOP','PG_STOP','REMOVAL','DELETE','REPORT','GUARD_API_TOOL_AFTER'|ForEach-Object{'resource_pipeline_failure_'+$_})
    Assert-ResourceCaseCoverage $held 3
    Assert-ResourceCaseCoverage $identity 15
    Assert-ResourceCaseCoverage $receipt 16
    Assert-ResourceCaseCoverage $stages 11
    Assert-ResourceCaseCoverage $cleanup 9
    Assert-ResourceCaseCoverage @($held+$identity+$receipt+$stages+$cleanup) 54
    foreach($name in $held){[pscustomobject]@{Name='Held';Count=1;Names=@($name);NameFilter=$name}}
    foreach($name in $identity){[pscustomobject]@{Name='CoreIdentityLifecycle';Count=1;Names=@($name);NameFilter=$name}}
    foreach($name in $receipt){[pscustomobject]@{Name='CoreReceiptRemoval';Count=1;Names=@($name);NameFilter=$name}}
    foreach($name in $stages){[pscustomobject]@{Name='ResourceStages';Count=1;Names=@($name);NameFilter=$name}}
    foreach($name in $cleanup){[pscustomobject]@{Name='ResourceFailureCleanup';Count=1;Names=@($name);NameFilter=$name}}
}
function Assert-ResourceHostResult($Result,[string[]]$ExpectedNames) {
    Assert-ResourceHostCompleted $Result
    if($ExpectedNames.Count -ne 1){throw 'TEST_HOST_SINGLE_PLAN_MISSING'}
    if($Result.Elapsed -gt 60000 -or $Result.Out.Length -gt 65536){throw 'TEST_HOST_FAILED'}
    $summary=[regex]::Matches($Result.Out,'(?m)^P6_RESOURCE_TESTS TOTAL=(\d+) PASSED=(\d+) FAILED=(\d+)\r?$')
    if($summary.Count -ne 1){throw 'TEST_HOST_SUMMARY_INVALID'}
    $total=[int]$summary[0].Groups[1].Value;$passed=[int]$summary[0].Groups[2].Value;$failed=[int]$summary[0].Groups[3].Value
    $caseNames=@([regex]::Matches($Result.Out,'(?m)^P6_RESOURCE_CASE NAME=([A-Za-z0-9_]+)\r?$')|ForEach-Object{$_.Groups[1].Value})
    Assert-ResourceCaseCoverage $caseNames $ExpectedNames.Count $ExpectedNames
    if($total -ne $ExpectedNames.Count -or $passed -ne $total -or $failed -ne 0){throw 'TEST_HOST_COVERAGE_INVALID'}
    if([regex]::Matches($Result.Out,'(?m)^P6_RESOURCE_CLEANUP CHILDREN=0 ARTIFACTS=0\r?$').Count -ne 1){throw 'TEST_HOST_CLEANUP_UNPROVEN'}
    [pscustomobject]@{Total=$total;Passed=$passed;Failed=$failed;Names=$caseNames}
}
function Get-ResourceCaseGroup([string]$Name) {
    if($Name -ceq 'resource_pipeline_real_stage_order_and_safe_report' -or $Name -cmatch '^resource_pipeline_failure_(SNAPSHOT|(PG|API|GW)_(START|READY|TOOL))$'){return 'ResourceStages'}
    if($Name -cmatch '^resource_pipeline_failure_(DEADLINE_API|RETAIN_API|GW_STOP|API_STOP|PG_STOP|REMOVAL|DELETE|REPORT|GUARD_API_TOOL_AFTER)$'){return 'ResourceFailureCleanup'}
    throw 'TEST_HOST_CASE_GROUP_UNKNOWN'
}
function Assert-ResourceWorkerFilter([string]$Group,[string]$Filter) {
    # 所有 worker 在 fixture 创建前都须取得唯一完整名称；聚合阶段不允许直接执行。
    $matches=@(Get-ResourceHostPlan|Where-Object{$_.Name -ceq $Group -and $_.NameFilter -ceq $Filter})
    if($matches.Count -ne 1 -or $Filter -eq ''){throw 'TEST_HOST_EXACT_FILTER_REQUIRED'}
}
if(-not $Worker){
    $groups=@(Get-ResourceHostPlan)
    $names=New-Object 'Collections.Generic.List[string]';$sum=0;$pass=0;$fail=0
    $overall=[Diagnostics.Stopwatch]::StartNew()
    $activeGroup='NONE';$activeCase='NONE'
    try {
        foreach($group in $groups){
            if($Phase -cne 'All' -and $Phase -cne $group.Name -and -not ($Phase -ceq 'Core' -and $group.Name -clike 'Core*') -and -not ($Phase -ceq 'Resources' -and $group.Name -cin @('ResourceStages','ResourceFailureCleanup'))){continue}
            $expectedNames=@($group.Names|Where-Object{$NameFilter -eq '' -or $_ -clike $NameFilter})
            if($expectedNames.Count -eq 0){continue}
            $activeGroup=$group.Name;$activeCase=$group.NameFilter
            # 父级仅监督整体；预留完整单宿主绝对 60 秒，不放大单 child 预算。
            if($overall.ElapsedMilliseconds -gt 1740000){throw 'TEST_HOST_TOTAL_DEADLINE'}
            $arguments=@('-File',$PSCommandPath,'-Worker','-Phase',$group.Name)
            $arguments+=@('-NameFilter',$group.NameFilter)
            if($Diagnose){$arguments+='-Diagnose'}
            $r=Invoke-ResourceTestHost $arguments
            if($Diagnose){
                $summaryCount=[regex]::Matches($r.Out,'(?m)^P6_RESOURCE_TESTS TOTAL=').Count
                $caseCount=[regex]::Matches($r.Out,'(?m)^P6_RESOURCE_CASE NAME=').Count
                $cleanupCount=[regex]::Matches($r.Out,'(?m)^P6_RESOURCE_CLEANUP CHILDREN=0 ARTIFACTS=0\r?$').Count
                [Console]::Out.WriteLine(('TEST_DIAG HOST_RESULT EXIT={0} TIMED_OUT={1} RETAINED={2} STOPPED={3} ELAPSED_MS={4} OUT_LENGTH={5} ERROR_LENGTH={6} SUMMARY_COUNT={7} CASE_COUNT={8} CLEANUP_COUNT={9}' -f $r.ExitCode,$r.TimedOut,$r.Retained,$r.Stopped,$r.Elapsed,$r.Out.Length,$r.Error.Length,$summaryCount,$caseCount,$cleanupCount))
            }
            [Console]::Out.WriteLine(('P6_RESOURCE_HOST GROUP={0} CASE={1} ELAPSED_MS={2} DEADLINE_MS=60000 TIMED_OUT={3} STOPPED={4} RETAINED={5}' -f $group.Name,$group.NameFilter,$r.Elapsed,$r.TimedOut,$r.Stopped,$r.Retained))
            foreach($line in ($r.Out -split '\r?\n')){if($line -cmatch '^(FAIL=|TEST_DIAG |TEST_EXE )'){[Console]::Out.WriteLine($line)}}
            $verified=Assert-ResourceHostResult $r $expectedNames
            if($overall.ElapsedMilliseconds -gt 1800000){throw 'TEST_HOST_TOTAL_DEADLINE'}
            foreach($name in $verified.Names){$names.Add($name)}
            $sum+=$verified.Total;$pass+=$verified.Passed;$fail+=$verified.Failed
            [Console]::Out.WriteLine(('P6_RESOURCE_HOST_VERIFIED TOTAL={0} PASSED={1} FAILED={2} CHILDREN=0 ARTIFACTS=0' -f $verified.Total,$verified.Passed,$verified.Failed))
        }
        Assert-ResourceCaseCoverage @($names) $sum
        if($Phase -ceq 'All' -and $NameFilter -eq ''){Assert-ResourceCaseCoverage @($names) 54 @($groups|ForEach-Object{$_.Names})}
        [Console]::Out.WriteLine(('P6_RESOURCE_TESTS TOTAL={0} PASSED={1} FAILED={2}' -f $sum,$pass,$fail))
        if($sum -eq 0 -or $fail -ne 0){exit 1};exit 0
    }catch{
        $allowedDetails=@('TEST_HOST_DEADLINE_INVALID','TEST_HOST_CREATE_FAILED','TEST_HOST_START_FAILED','TEST_HOST_WAIT_FAILED','TEST_HOST_STOP_UNPROVEN','TEST_HOST_CAPTURE_UNPROVEN','TEST_HOST_RESULT_UNPROVEN','TEST_HOST_TIMEOUT','TEST_HOST_FAILED','TEST_HOST_COVERAGE_INVALID','TEST_HOST_SUMMARY_INVALID','TEST_HOST_CLEANUP_UNPROVEN','TEST_HOST_SINGLE_PLAN_MISSING','TEST_HOST_TOTAL_DEADLINE')
        $detail=if($_.Exception.Message -cin $allowedDetails){$_.Exception.Message}else{'TEST_HOST_UNCLASSIFIED'}
        if($Diagnose){
            [Console]::Out.WriteLine(('TEST_DIAG HOST_REJECT DETAIL={0}' -f $detail))
        }
        $code=if($_.Exception.Message -ceq 'TEST_HOST_TIMEOUT'){'TEST_HOST_TIMEOUT'}else{'TEST_HOST_REJECTED'}
        [Console]::Out.WriteLine(('P6_RESOURCE_TESTS TOTAL={0} PASSED={1} FAILED={2}' -f $sum,$pass,$fail))
        [Console]::Out.WriteLine(('P6_RESOURCE_HOST FAILED={0} GROUP={1} CASE={2} DETAIL={3} RETAINED=True NEXT_GROUP=False' -f $code,$activeGroup,$activeCase,$detail));exit 1
    }
}
Assert-ResourceWorkerFilter $Phase $NameFilter
if($Diagnose){
    # Compatibility flag only: never rewrite product functions or dereference exception/path objects.
    try{[Console]::Out.WriteLine('TEST_DIAG MODE=NON_MUTATING')}catch{}
}
$script:total=0;$script:passed=0;$script:failed=New-Object 'Collections.Generic.List[string]'
$script:cleanupProven=$true
$testRoot=Join-Path $repo ('.tmp/p6iso-tests-'+[guid]::NewGuid().ToString('N').Substring(0,8))
[void][IO.Directory]::CreateDirectory($testRoot)
function Check([bool]$ok,[string]$code){if(-not $ok){throw $code}}
function Close-TestOwnedProcess($Process) {
    try {
        # PowerShell 的直接 Id 读取可能吞掉 getter 异常；反射判断是否真正关联过进程。
        $associated=$false
        try{$unused=$Process.GetType().GetProperty('Id').GetValue($Process,$null);$associated=$true}catch{}
        if(-not $associated){$Process.Dispose();return}
        if(-not $Process.HasExited){$Process.Kill();if(-not $Process.WaitForExit(10000)){throw 'TEST_CHILD_STOP_UNPROVEN'}}
        if(-not $Process.HasExited){throw 'TEST_CHILD_STOP_UNPROVEN'}
        $Process.Dispose()
    }catch{$script:cleanupProven=$false;throw 'TEST_CHILD_STOP_UNPROVEN'}
}
function Case([string]$name,[scriptblock]$body){
    # worker 仅执行 manifest 指定的一个名称，绝不接受 wildcard 或职责组合。
    if($name -cne $NameFilter){return}
    $script:total++
    [Console]::Out.WriteLine('P6_RESOURCE_CASE NAME='+$name)
    try{&$body;$script:passed++}catch{
        $code=[string]$_.Exception.Message
        if($code -cnotmatch '^[A-Z][A-Z0-9_]+$'){$code='TEST_INFRASTRUCTURE_ERROR'}
        $script:failed.Add($name+':'+$code)
    }
}
function Reject([scriptblock]$body,[string]$code){
    try{&$body|Out-Null}catch{Check ($_.Exception.Message -ceq $code) 'WRONG_REJECTION';return}
    throw 'REJECTION_MISSING'
}
function NewFixture {
    param([string]$Parent=$testRoot)
    $run=[guid]::NewGuid().ToString('N');$root=Join-Path $Parent ('native-'+$run)
    [void][IO.Directory]::CreateDirectory((Join-Path $root 'pgdata'))
    $created=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o')
    $marker=[pscustomobject]@{SchemaVersion=1;RunId=$run;OwnerNonce=('a'*64);RunDirectory=$root;CreatedAt=$created;PgPort=45431}
    $receipt=[pscustomobject]@{SchemaVersion=1;RunId=$run;OwnerNonce=('a'*64);RunDirectory=$root;CreatedAt=$created;Ports=@(45431,45432,45433,45434);PgData=(Join-Path $root 'pgdata');PgDatabase='composite_live';MigrationDatabase='composite_onboard';Processes=@()}
    Protect-P6RunAcl $root
    Write-P6OwnedStorage $receipt $Parent $marker @{DbPassword=('Q'*40)}|Out-Null
    return [pscustomobject]@{Receipt=$receipt;RunParent=$Parent;Marker=$marker;ReceiptHash=(Get-P6Sha256 ([IO.File]::ReadAllBytes((Join-Path $root 'receipt.json'))))}
}
function Record($ctx,[string]$role,[int]$id){
    $pg=$role -ceq 'PG'
    return [pscustomobject]@{Pid=$id;StartTimeUtc=[DateTimeOffset]::UtcNow.AddSeconds(-1).ToString('o');ExecutablePath=$(if($pg){'C:\Program Files\PostgreSQL\17\bin\postgres.exe'}else{'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'});WorkingDirectory=$ctx.Receipt.RunDirectory;RunId=$ctx.Receipt.RunId;OwnerNonce=$ctx.Receipt.OwnerNonce;Kind=$(if($pg){'Postgres'}else{'Java'});Role=$role;ArgumentMarker=$(if($pg){$ctx.Receipt.PgData}else{'-Dp6.rehearsal.run='+$ctx.Receipt.RunId})}
}
function ChildSpec {
    return [pscustomobject]@{FileName=(Get-Command powershell.exe -CommandType Application|Select-Object -First 1).Source;Arguments=@('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 20');WorkingDirectory=$testRoot;Environment=(Get-P6ChildEnvironment $testRoot)}
}
function Test-Fix1MissingRemovalEvidence {
    $ctx=NewFixture (Get-P6NativeRunParent $testRoot)
    $ctx|Add-Member RepositoryRoot $testRoot;$ctx|Add-Member Tickets ([ordered]@{});$ctx|Add-Member ShortTickets (New-Object 'Collections.Generic.List[object]');$ctx|Add-Member ToolProcessIds @()
    $spec=ChildSpec;$spec.Arguments=@('-NoProfile','-NonInteractive','-Command','exit 0')
    $p=New-Object Diagnostics.Process;$p.StartInfo=New-P6ProcessStartInfo $spec
    [void]$p.Start();$ticks=$p.StartTime.ToUniversalTime().Ticks
    try{
        Check ($p.WaitForExit(5000)) 'TEST_CHILD_STOP_UNPROVEN'
        $record=Record $ctx 'PG' $p.Id
        Write-P6ChainedResourceReceipt $ctx 'PG' $record
        $ctx.Tickets['PG']=[pscustomobject]@{Process=$p;State='STOPPED';LaunchEvidence=[pscustomobject]@{StartTicks=$ticks}}
        foreach($name in @('pgdata','secrets')){
            $target=Assert-P6ChildPath $ctx.Receipt.RunDirectory (Join-Path $ctx.Receipt.RunDirectory $name) -MustExist
            Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
        }
        $calls=@{Count=0};$realProof=(Get-Command Assert-P6RemovalProof).ScriptBlock
        function Assert-P6RemovalProof {
            param($Receipt,$RunParent,$Marker,$Target,$ProcessObservations,$Listeners)
            $calls.Count++;&$realProof $Receipt $RunParent $Marker $Target $ProcessObservations $Listeners
        }
        $failures=0;$shapes=@('unknown','null','empty','missing','duplicate','exists','valid','inventory_null','inventory_raw','inventory_failed','inventory_missing','inventory_string','inventory_null_items','inventory_scalar_items','unregistered')
        foreach($shape in $shapes){
            $calls.Count=0
            $observe={param($kind,$id)
                if($kind -ceq 'PROCESS'){
                    $row=[pscustomobject]@{Pid=$id;ObservationSucceeded=$true;Exists=$false}
                    switch($shape){
                        'unknown' {$row.ObservationSucceeded=$false;$row.Exists=$null}
                        'null' {return $null}
                        'empty' {return @()}
                        'missing' {return [pscustomobject]@{Pid=$id}}
                        'duplicate' {return @($row,$row)}
                        'exists' {$row.Exists=$true}
                    }
                    return $row
                }
                if($kind -ceq 'RUN_PROCESSES'){
                    switch($shape){
                        'inventory_null' {return $null}
                        'inventory_raw' {return ,@()}
                        'inventory_failed' {return [pscustomobject]@{Succeeded=$false;Items=@()}}
                        'inventory_missing' {return [pscustomobject]@{Items=@()}}
                        'inventory_string' {return [pscustomobject]@{Succeeded='true';Items=@()}}
                        'inventory_null_items' {return [pscustomobject]@{Succeeded=$true;Items=$null}}
                        'inventory_scalar_items' {return [pscustomobject]@{Succeeded=$true;Items=424245}}
                        'unregistered' {return Read-P6ResourceProcessInventory $ctx { [pscustomobject]@{ProcessId=424245;ParentProcessId=1;CommandLine=('-Dp6.rehearsal.run='+$ctx.Receipt.RunId)} }}
                    }
                }
                [pscustomobject]@{Succeeded=$true;Items=@()}
            }
            $accepted=$false;$code=''
            try{$accepted=Assert-P6ResourceRemoval $ctx $observe}catch{$code=$_.Exception.Message}
            if($shape -ceq 'valid'){
                if(-not $accepted -or $calls.Count -lt 1){$failures++}
            }elseif($accepted -or $code -cne 'REHEARSAL_REMOVAL_UNPROVEN'){$failures++}
            if($shape -cin @('unknown','null','empty','missing','duplicate','exists') -and $calls.Count -lt 1){$failures++}
        }
        [Console]::Out.WriteLine(('P6_FIX1_REMOVAL SHAPES={0} FAILURES={1}' -f $shapes.Count,$failures))
        Check ($failures -eq 0 -and [IO.Directory]::Exists($ctx.Receipt.RunDirectory)) 'TASK1A_PROOF_SKIPPED_OR_UNKNOWN_ACCEPTED'
        $zero=NewFixture (Get-P6NativeRunParent $testRoot)
        $zero|Add-Member RepositoryRoot $testRoot;$zero|Add-Member Tickets ([ordered]@{});$zero|Add-Member ShortTickets (New-Object 'Collections.Generic.List[object]');$zero|Add-Member ToolProcessIds @()
        foreach($name in @('pgdata','secrets')){
            $target=Assert-P6ChildPath $zero.Receipt.RunDirectory (Join-Path $zero.Receipt.RunDirectory $name) -MustExist
            Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
        }
        $calls.Count=0
        Check ((Assert-P6ResourceRemoval $zero { [pscustomobject]@{Succeeded=$true;Items=@()} }) -and $calls.Count -ge 1) 'EMPTY_RUN_PROOF_SKIPPED'
    }finally{Close-TestOwnedProcess $p}
}
function Test-Fix1QuickGuard {
    # 当前宿主只提供 alive 属性观测，finally 只能 Dispose 包装对象，绝不停止它。
    $live=[Diagnostics.Process]::GetCurrentProcess()
    $dead=[Diagnostics.Process]::new();$exitSpec=ChildSpec
    $exitSpec.FileName=(Get-Command cmd.exe -CommandType Application|Select-Object -First 1).Source
    $exitSpec.Arguments=@('/d','/c','exit','0');$dead.StartInfo=New-P6ProcessStartInfo $exitSpec
    $deadStarted=$false;$deadExited=$false
    try{
        $deadStarted=$dead.Start();Check $deadStarted 'TEST_CHILD_START_FAILED'
        $deadExited=$dead.WaitForExit(10000);Check $deadExited 'TEST_CHILD_STOP_UNPROVEN'
        foreach($shape in @('exited','drain_failed','output_limit','clock_rollback','script_rejected')){
            $state=New-Object P6QuickGuardState;$state.Processes=@($live)
            $reader=$null;$other=$null;$calls=@{Count=0}
            try{
                if($shape -ceq 'exited'){$state.Processes=@($dead)}
                if($shape -ceq 'clock_rollback'){$state.LastElapsedMilliseconds=[long]::MaxValue}
                if($shape -cin @('drain_failed','output_limit')){
                    $bytes=if($shape -ceq 'output_limit'){[Text.Encoding]::ASCII.GetBytes(('X'*70000))}else{New-Object byte[] 0}
                    $reader=New-Object IO.StreamReader((New-Object IO.MemoryStream(,$bytes)))
                    $other=New-Object IO.StreamReader((New-Object IO.MemoryStream))
                    if($shape -ceq 'drain_failed'){$reader.Dispose()}
                    $drain=New-Object P6NativeStreamDrain($reader,$other);Check ($drain.Wait(1000)) 'TEST_DRAIN_NOT_FINISHED';$state.Drains=@($drain)
                }
                if($shape -ceq 'script_rejected'){$state={$calls.Count++;[Threading.Thread]::Sleep(350)}.GetNewClosure()}
                $factory={param($info)$calls.Count++;throw 'SYNTHETIC_SECRET'}.GetNewClosure()
                $result=Invoke-P6BoundedChild (ChildSpec) 1000 $factory $state
                $code=if($shape -ceq 'clock_rollback'){'REHEARSAL_CLOCK_INVALID'}elseif($shape -ceq 'script_rejected'){'REHEARSAL_NATIVE_FAILED'}else{'REHEARSAL_HEALTH_UNPROVEN'}
                Check ($result.Code -ceq $code -and $calls.Count -eq 0 -and $result.Cleanup -ceq 'NOT_STARTED') 'QUICK_GUARD_IGNORED'
            }finally{if($null -ne $reader){$reader.Dispose()};if($null -ne $other){$other.Dispose()}}
        }
        [Console]::Out.WriteLine('P6_FIX1_QUICK_GUARD SHAPES=5 FAILED=0')
        $wrapped=New-Object P6QuickGuardState;$etsCalls=@{Count=0}
        $override={$etsCalls.Count++;[Threading.Thread]::Sleep(350);return ''}.GetNewClosure()
        $wrapped|Add-Member -MemberType ScriptMethod -Name Check -Value $override -Force
        $prepared=[Diagnostics.Process]::new()
        $factory={param($info)$prepared.StartInfo=$info;$prepared}.GetNewClosure()
        $probeResult=$null
        try{
            $spec=ChildSpec;$timer=[Diagnostics.Stopwatch]::StartNew()
            $probeResult=Invoke-P6BoundedChild $spec 100 $factory $wrapped
            $timer.Stop()
            [Console]::Out.WriteLine(('TEST_DIAG ETS_CALLBACKS={0} BUDGET_MS=100 ELAPSED_MS={1} CODE={2}' -f $etsCalls.Count,$timer.ElapsedMilliseconds,$probeResult.Code))
            Check ($etsCalls.Count -eq 0) 'ETS_SCRIPT_METHOD_EXECUTED'
            Check ($probeResult.Code -ceq 'REHEARSAL_NATIVE_TIMEOUT' -and $timer.ElapsedMilliseconds -le 1000) 'ETS_GUARD_DEADLINE_FAILED'
        }finally{
            if($null -ne $probeResult -and $probeResult.Cleanup -ceq 'NOT_STARTED'){$prepared.Dispose()}else{Close-TestOwnedProcess $prepared}
        }
    }finally{
        $live.Dispose()
        if($deadExited -or -not $deadStarted){$dead.Dispose()}else{$script:cleanupProven=$false}
    }
}
function Test-Fix3DrainFailureRetainsProcess {
    $prepared=[Diagnostics.Process]::new()
    $factory={param($info)$prepared.StartInfo=$info;$prepared}.GetNewClosure()
    $body=(Get-Command Invoke-P6BoundedChild).ScriptBlock.ToString()
    # Only the constructor boundary is forced to fail; real start/ownership/cleanup flow remains.
    $body=$body.Replace('$drain=[P6NativeStreamDrain]::new($process.StandardOutput,$process.StandardError)',"throw 'SYNTHETIC_DRAIN_FAILURE'")
    $controlled=[scriptblock]::Create($body)
    try{
        $result=&$controlled (ChildSpec) 5000 $factory
        Check ($result.Code -ceq 'REHEARSAL_NATIVE_DRAIN_FAILED' -and $result.Retained -and $result.Cleanup -ceq 'RETAINED') 'DRAIN_FAILURE_DID_NOT_RETAIN'
        Check ([object]::ReferenceEquals($result.HeldTicket.Process,$prepared) -and -not $prepared.HasExited -and $null -eq $result.HeldTicket.Drain -and $result.CleanupWaitMilliseconds -eq 0) 'DRAIN_FAILURE_HANDLE_LOST'
    }finally{Close-TestOwnedProcess $prepared}
}
function Test-Fix1RetainedBeforePostProof {
    $ctx=NewFixture;$ctx|Add-Member Secrets @{DbPassword=('D'*40)};$ctx|Add-Member Tickets ([ordered]@{});$ctx|Add-Member ShortTickets (New-Object 'Collections.Generic.List[object]')
    $pg=[Diagnostics.Process]::new();$pg.StartInfo=New-P6ProcessStartInfo (ChildSpec)
    $tool=[Diagnostics.Process]::new();$tool.StartInfo=New-P6ProcessStartInfo (ChildSpec)
    [void]$pg.Start();[void]$tool.Start()
    try{
        $record=Record $ctx 'PG' $pg.Id
        $ctx.Tickets['PG']=[pscustomobject]@{Process=$pg;Recorded=$record;Drain=$null}
        $pending=[pscustomobject]@{Process=$tool;Drain=$null};$state=@{Returned=$false;PostChecks=0}
        # Only the already-covered PG proof/native boundary is fake here; real Stop-P6HeldResource
        # must transfer the exact retained native ticket before its own post-check can throw.
        $ctx|Add-Member TestOwnership ({param($Context,$Role,$Observe) [pscustomobject]@{ObservedProcess=$record}}.GetNewClosure())
        $ctx|Add-Member TestReceiptProof ({param($Context)if($state.Returned){$state.PostChecks++;throw 'REHEARSAL_RECEIPT_CHAIN_UNPROVEN'}}.GetNewClosure())
        $ctx|Add-Member TestOwnedStop ({
            param($Receipt,$RunParent,$Recorded,$ReadOwnershipEvidence,$StopProcess,$WaitStopped)
            &$StopProcess $Recorded|Out-Null
            [pscustomobject]@{Status='STOPPED'}
        }.GetNewClosure())
        $ctx|Add-Member TestSpec ({param($Action)Check ($Action -ceq 'PG_STOP') 'WRONG_STOP_ACTION';[pscustomobject]@{Action=$Action}})
        $ctx|Add-Member TestNative ({
            param($Spec,$TimeoutMilliseconds,$ProcessFactory,$QuickState)
            Check ($Spec.Action -ceq 'PG_STOP' -and $TimeoutMilliseconds -eq 40000) 'WRONG_STOP_NATIVE_SPEC'
            $state.Returned=$true
            [pscustomobject]@{Status='FAILED';Code='REHEARSAL_NATIVE_TIMEOUT';Retained=$true;HeldTicket=$pending}
        }.GetNewClosure())
        $body=(Get-Command Stop-P6HeldResource).ScriptBlock.ToString()
        $body=$body.Replace('Read-P6HeldResourceOwnership $Context $Role $Observe','& $Context.TestOwnership $Context $Role $Observe')
        $body=$body.Replace('Assert-P6CurrentResourceReceipt $Context','& $Context.TestReceiptProof $Context')
        $body=$body.Replace("Get-P6NativeToolSpec 'PG_STOP'","& `$Context.TestSpec 'PG_STOP'")
        $body=$body.Replace('Stop-P6OwnedPostgres $Context.Receipt','& $Context.TestOwnedStop $Context.Receipt')
        $body=$body.Replace('Invoke-P6BoundedChild $spec 40000 $null $quick','& $Context.TestNative $spec 40000 $null $quick')
        $controlled=[scriptblock]::Create($body)
        $result=&$controlled $ctx 'PG'
        Check ($result.Status -ceq 'RETAINED' -and $state.PostChecks -eq 1) 'POST_PROOF_FAILURE_NOT_EXERCISED'
        Check ($ctx.ShortTickets.Count -eq 1 -and [object]::ReferenceEquals($ctx.ShortTickets[0],$pending) -and -not $tool.HasExited) 'RETAINED_TICKET_LOST_AFTER_POST_PROOF'
    }finally{Close-TestOwnedProcess $tool;Close-TestOwnedProcess $pg}
}
function NewStatefulBoundary($ctx,[string]$fail='') {
    $state=[pscustomobject]@{Events=(New-Object 'Collections.Generic.List[string]');Open=(New-Object 'Collections.Generic.List[string]');Effects=(New-Object 'Collections.Generic.List[string]');Deleted=$false;Report=$null;Clock=[long]0;Fail=$fail;StopFailed=$false}
    $ctx|Add-Member Tickets ([ordered]@{})
    $ctx|Add-Member Lifetime (New-P6ResourceLifetime {$state.Clock}.GetNewClosure())
    $ctx|Add-Member ShortTickets (New-Object 'Collections.Generic.List[object]')
    $boundary=@{
        Snapshot={param($context)$state.Events.Add('SNAPSHOT');if($state.Fail -ceq 'SNAPSHOT'){throw 'SYNTHETIC_SECRET'};$true}.GetNewClosure()
        Guard={param($context,$phase)
            Assert-P6CurrentResourceReceipt $context
            foreach($role in $state.Open){Check ([IO.File]::Exists((Join-Path $context.Receipt.RunDirectory ($role+'.live')))) 'FAKE_RESOURCE_STATE_LOST'}
            if($state.Fail -ceq ('GUARD_'+$phase)){throw 'SYNTHETIC_SECRET'}
            $true
        }.GetNewClosure()
        Start={param($role,$context)
            $state.Events.Add($role+'_START')
            $state.Open.Add($role)
            [IO.File]::WriteAllText((Join-Path $context.Receipt.RunDirectory ($role+'.live')),'owned synthetic live resource')
            $context.Tickets[$role]=[pscustomobject]@{Owned=$true;Role=$role}
            Write-P6ChainedResourceReceipt $context $role (Record $context $role (424240+$state.Open.Count))
            if($state.Fail -ceq ($role+'_START')){return [pscustomobject]@{Status='FAILED'}}
            [pscustomobject]@{Status='STARTED'}
        }.GetNewClosure()
        Ready={param($role,$context)
            $state.Events.Add($role+'_READY')
            Check ($state.Open.Contains($role)) 'READY_WITHOUT_PROCESS'
            if($state.Fail -ceq ($role+'_READY')){throw 'SYNTHETIC_SECRET'}
            $true
        }.GetNewClosure()
        Tool={param($role,$context,$probe)
            $state.Events.Add($role+'_TOOL');$state.Effects.Add($role)
            if($state.Fail -ceq ('DEADLINE_'+$role)){$state.Clock=1800000}
            &$probe|Out-Null
            if($state.Fail -ceq ($role+'_TOOL')){return [pscustomobject]@{Status='FAILED';Retained=$false}}
            if($state.Fail -ceq ('RETAIN_'+$role)){return [pscustomobject]@{Status='FAILED';Retained=$true;HeldTicket=[pscustomobject]@{Owned=$true}}}
            [pscustomobject]@{Status='EXITED';Retained=$false}
        }.GetNewClosure()
        Stop={param($role,$context)
            $state.Events.Add($role+'_STOP')
            if($state.Fail -ceq ($role+'_STOP')){$state.StopFailed=$true;return [pscustomobject]@{Status='RETAINED'}}
            [IO.File]::Delete((Join-Path $context.Receipt.RunDirectory ($role+'.live')))
            [void]$state.Open.Remove($role)
            [pscustomobject]@{Status='STOPPED'}
        }.GetNewClosure()
        Removal={param($context)
            $state.Events.Add('REMOVAL');Check ($state.Open.Count -eq 0 -and -not $state.StopFailed) 'REMOVAL_WHILE_LIVE'
            if($state.Fail -ceq 'REMOVAL'){throw 'SYNTHETIC_SECRET'}
            $true
        }.GetNewClosure()
        Delete={param($context)
            $state.Events.Add('DELETE');Check ($state.Open.Count -eq 0 -and -not $state.StopFailed) 'DELETE_WHILE_LIVE'
            if($state.Fail -ceq 'DELETE'){throw 'SYNTHETIC_SECRET'}
            $state.Deleted=$true;$true
        }.GetNewClosure()
        Report={param($context,$report)
            $state.Events.Add('REPORT');$state.Report=$report
            Check (($report|ConvertTo-Json -Depth 8 -Compress) -notmatch 'SYNTHETIC_SECRET|[A-Z]:\\|42424|OwnerNonce') 'REPORT_SECRET_LEAK'
            if($state.Fail -ceq 'REPORT'){throw 'SYNTHETIC_SECRET'}
            $true
        }.GetNewClosure()
    }
    return [pscustomobject]@{State=$state;Boundary=$boundary}
}
function AddJavaContext($ctx) {
    $ctx|Add-Member Secrets @{DbPassword=('D'*40);JwtSecret=('J'*40);BootstrapPassword=('B'*40);GatewayCredential=('G'*40);H2Password=('H'*40)}
    $ctx|Add-Member Tickets ([ordered]@{})
    $ctx|Add-Member ArtifactHashes @{}
    $ctx|Add-Member Lifetime (New-P6ResourceLifetime)
    foreach($role in @('API','GW')){
        $jar=Join-Path $ctx.Receipt.RunDirectory $(if($role -ceq 'API'){'api.jar'}else{'gateway.jar'})
        [IO.File]::Copy($script:fakeJar,$jar)
        $ctx.ArtifactHashes[$role]=Get-P6Sha256 ([IO.File]::ReadAllBytes($jar))
    }
}
function FixtureObserver($ctx,[string]$role,[hashtable]$flags) {
    # Synthetic OS boundary is independent of receipt; do not substitute the requested path.
    $runtimeExecutable=$ctx.Tickets[$role].Process.MainModule.FileName
    return {
        param($kind,$processId)
        $ticket=$ctx.Tickets[$role];$p=$ticket.Process
        if($kind -ceq 'PROCESS'){
            if($p.HasExited){return [pscustomobject]@{ObservationSucceeded=$true;Exists=$false;Pid=$processId;Process=$null}}
            # Mirror Win32_Process CreationDate microsecond precision, independently from receipt.
            $observedTicks=$p.StartTime.ToUniversalTime().Ticks
            $start=(New-Object DateTimeOffset(($observedTicks-($observedTicks%10)),[TimeSpan]::Zero)).ToString('o')
            if($flags.Drift){$start=[DateTimeOffset]::UtcNow.AddHours(-2).ToString('o')}
            $observed=[pscustomobject]@{Pid=$p.Id;StartTimeUtc=$start;ExecutablePath=$(if($flags.WrongExe){'C:\wrong\java.exe'}else{$runtimeExecutable});CommandLine=('"'+$p.StartInfo.FileName+'" '+$p.StartInfo.Arguments)}
            return [pscustomobject]@{ObservationSucceeded=$true;Exists=$true;Pid=$p.Id;Process=$observed}
        }
        $ports=if($role -ceq 'API'){@($ctx.Receipt.Ports[1])}else{@($ctx.Receipt.Ports[2],$ctx.Receipt.Ports[3])}
        $items=@(if(-not $p.HasExited -and -not $flags.Empty){foreach($port in $ports){[pscustomobject]@{ObservationSucceeded=$true;LocalAddress=$(if($flags.Wildcard){'0.0.0.0'}else{'127.0.0.1'});LocalPort=$port;OwningProcess=$p.Id}}})
        return [pscustomobject]@{Succeeded=$true;Items=$items}
    }.GetNewClosure()
}
if($Phase -cin @('All','Held')){
    # Compile a tiny synthetic long-lived Java child, never a business API/GW or database.
    $compileRoot=Assert-P6ChildPath $testRoot (Join-Path $testRoot ('native-'+[guid]::NewGuid().ToString('N')))
    [void][IO.Directory]::CreateDirectory($compileRoot)
    Protect-P6RunAcl $compileRoot
    $compileSecrets=Assert-P6ChildPath $compileRoot (Join-Path $compileRoot 'secrets')
    [void][IO.Directory]::CreateDirectory($compileSecrets);Assert-P6PrivateAcl $compileSecrets
    $source=Join-Path $compileRoot 'SyntheticHeld.java'
    [IO.File]::WriteAllText($source,'public class SyntheticHeld { public static void main(String[] a) throws Exception { for(int i=0;i<150;i++){System.out.print("SYNTHETIC_SECRET");System.err.print("SYNTHETIC_SECRET");Thread.sleep(100);} } }')
    $compileSpec=[pscustomobject]@{FileName='C:\Program Files\Java\jdk-21.0.10\bin\javac.exe';Arguments=@('-encoding','UTF-8','-d',$compileRoot,$source);WorkingDirectory=$compileRoot;Environment=(Get-P6ChildEnvironment $compileRoot)}
    $compiled=Invoke-P6BoundedChild $compileSpec 60000
    Check ($compiled.Status -ceq 'EXITED') 'SYNTHETIC_JAVA_COMPILE_FAILED'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression
    $script:fakeJar=Join-Path $compileRoot 'synthetic.jar'
    $zip=[IO.Compression.ZipFile]::Open($script:fakeJar,[IO.Compression.ZipArchiveMode]::Create)
    try{
        $entry=$zip.CreateEntry('META-INF/MANIFEST.MF');$writer=New-Object IO.StreamWriter($entry.Open());$writer.Write("Manifest-Version: 1.0`r`nMain-Class: SyntheticHeld`r`n`r`n");$writer.Dispose()
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $compileRoot 'SyntheticHeld.class'),'SyntheticHeld.class')
    }finally{$zip.Dispose()}
    Case 'D3_java_specs_fixed_args_artifact_hash_and_env_whitelist' {
        $ctx=NewFixture;AddJavaContext $ctx
        foreach($role in @('API','GW')){
            $spec=Get-P6JavaResourceSpec $ctx $role
            Check ($spec.FileName -ceq 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe' -and $spec.Arguments.Count -eq 3 -and $spec.Arguments[0] -ceq ('-Dp6.rehearsal.run='+$ctx.Receipt.RunId) -and $spec.Arguments[1] -ceq '-jar') 'JAVA_COMMAND_NOT_FIXED'
            Check (($spec.Arguments -join ' ') -notmatch ('D'*40+'|'+('G'*40))) 'SECRET_IN_ARGUMENTS'
            Check ($spec.WorkingDirectory -ceq $ctx.Receipt.RunDirectory -and -not $spec.Environment.ContainsKey('JAVA_TOOL_OPTIONS') -and -not $spec.Environment.ContainsKey('HTTP_PROXY')) 'JAVA_CONFIG_INHERITED'
        }
        [IO.File]::AppendAllText((Join-Path $ctx.Receipt.RunDirectory 'api.jar'),'drift')
        Reject {Get-P6JavaResourceSpec $ctx 'API'} 'REHEARSAL_RESOURCE_SPEC_INVALID'
        # A real, already-exited test child must fail startup and retain its original ticket.
        $early=NewFixture;AddJavaContext $early
        Write-P6ChainedResourceReceipt $early 'PG' (Record $early 'PG' 424241)
        $exitFactory={param($info)
            $info.Arguments='-version'
            $child=New-Object Diagnostics.Process;$child.StartInfo=$info
            $child|Add-Member -MemberType ScriptProperty -Name HasExited -Value {$this.WaitForExit(1000)} -Force
            $child
        }
        try{
            $failed=Start-P6HeldResource $early 'API' $exitFactory
            Check ($failed.Status -ceq 'FAILED' -and $failed.Retained -and $early.Tickets.Contains('API') -and $early.Tickets.API.State -ceq 'FAILED' -and $early.Receipt.Processes.Count -eq 1) 'EXITED_LAUNCH_NOT_RETAINED'
            $early.Tickets.API.Process.PSObject.Properties.Remove('HasExited')
            Check ($early.Tickets.API.Process.HasExited) 'EARLY_EXIT_NOT_REAL'
            $denied=Stop-P6HeldResource $early 'API' {throw 'TEST_OBSERVATION_UNAVAILABLE'} {throw 'TEST_STOP_FORBIDDEN'}
            Check ($denied.Status -ceq 'RETAINED') 'PARTIAL_LAUNCH_STOPPED'
        }finally{
            foreach($ticket in $early.Tickets.Values){if($null -ne $ticket.Process){$ticket.Process.PSObject.Properties.Remove('HasExited');Close-TestOwnedProcess $ticket.Process}}
        }
    }
    foreach($role in @('API','GW')){
        Case ('D3_java_actual_held_start_stop_'+$role) {
            $ctx=NewFixture;AddJavaContext $ctx
            Write-P6ChainedResourceReceipt $ctx 'PG' (Record $ctx 'PG' 424241)
            if($role -ceq 'GW'){Write-P6ChainedResourceReceipt $ctx 'API' (Record $ctx 'API' 424242)}
            try{
                # The runtime module is intentionally unavailable at the launch-record boundary.
                # A launch request is not runtime observation; reading it here must fail this test.
                $moduleReads=@{Value=0}
                $factory={param($info)
                    $child=New-Object Diagnostics.Process;$child.StartInfo=$info
                    $child|Add-Member -MemberType ScriptProperty -Name MainModule -Value {$moduleReads.Value++;throw 'TEST_MODULE_UNAVAILABLE'}.GetNewClosure() -Force
                    $child
                }.GetNewClosure()
                $started=Start-P6HeldResource $ctx $role $factory
                Check ($started.Status -ceq 'STARTED' -and $ctx.Tickets.Contains($role)) 'HELD_JAVA_START_MISSING'
                $ticket=$ctx.Tickets[$role];$p=$ticket.Process
                Check ($moduleReads.Value -eq 0 -and $ticket.Recorded.LaunchExecutablePath -ceq 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe') 'LAUNCH_RECORD_READ_RUNTIME_MODULE'
                $p.PSObject.Properties.Remove('MainModule')
                Check (-not $p.HasExited -and $ctx.Receipt.Processes[-1].Pid -eq $p.Id) 'HELD_JAVA_RECEIPT_MISSING'
                $flags=@{Drift=$false;WrongExe=$false;Empty=$false;Wildcard=$false};$observe=FixtureObserver $ctx $role $flags
                $evidence=Read-P6HeldResourceOwnership $ctx $role $observe
                Check ([object]::ReferenceEquals($evidence.LaunchEvidence.Process,$p)) 'HELD_JAVA_HANDLE_REPLACED'
                Assert-P6HeldResourceHealth $ctx $role $false $observe
                $flags.Empty=$true
                Reject {Assert-P6HeldResourceHealth $ctx $role $false $observe} 'REHEARSAL_HEALTH_UNPROVEN'
                $flags.Empty=$false;$flags.Wildcard=$true
                Reject {Assert-P6HeldResourceHealth $ctx $role $false $observe} 'REHEARSAL_HEALTH_UNPROVEN'
                $flags.Wildcard=$false
                Reject {Assert-P6ResourceHealth $ctx '' $observe} 'REHEARSAL_HEALTH_UNPROVEN'
                $flags.Drift=$true;$calls=@{Value=0}
                $denied=Stop-P6HeldResource $ctx $role $observe {param($held)$calls.Value++;$held.Kill()}.GetNewClosure()
                Check ($denied.Status -ceq 'RETAINED' -and $calls.Value -eq 0 -and -not $p.HasExited) 'DRIFTED_JAVA_STOPPED'
                $flags.Drift=$false
                $flags.WrongExe=$true
                Reject {Assert-P6HeldResourceHealth $ctx $role $false $observe} 'REHEARSAL_HEALTH_UNPROVEN'
                $denied=Stop-P6HeldResource $ctx $role $observe {param($held)$calls.Value++;$held.Kill()}.GetNewClosure()
                Check ($denied.Status -ceq 'RETAINED' -and $calls.Value -eq 0 -and -not $p.HasExited) 'WRONG_EXE_JAVA_STOPPED'
                $flags.WrongExe=$false
                $p|Add-Member -MemberType ScriptProperty -Name MainModule -Value {throw 'TEST_MODULE_UNAVAILABLE'} -Force
                Reject {Assert-P6HeldResourceHealth $ctx $role $false $observe} 'REHEARSAL_HEALTH_UNPROVEN'
                $denied=Stop-P6HeldResource $ctx $role $observe {param($held)$calls.Value++;$held.Kill()}.GetNewClosure()
                Check ($denied.Status -ceq 'RETAINED' -and $calls.Value -eq 0 -and -not $p.HasExited) 'UNOBSERVED_MODULE_JAVA_STOPPED'
                $p.PSObject.Properties.Remove('MainModule')
                $stopped=Stop-P6HeldResource $ctx $role $observe {param($held)$calls.Value++;$held.Kill()}.GetNewClosure()
                Check ($stopped.Status -ceq 'STOPPED' -and $p.HasExited -and $ticket.State -ceq 'STOPPED' -and $calls.Value -eq 1) 'HELD_JAVA_STOP_MISSING'
            }finally{
                foreach($ticket in $ctx.Tickets.Values){if($null -ne $ticket.Process){Close-TestOwnedProcess $ticket.Process}}
            }
        }
    }
}
if($Phase -cin @('All','Core','CoreIdentityLifecycle','CoreReceiptRemoval')){
    Case 'D3_test_host_deadline_retains_and_blocks_next_group' {
        Test-ResourceTransportFailureCodes
        # 清理的对象可能尚未 Start；未关联对象仅 Dispose，真实进程必须停止。
        $unstarted=[Diagnostics.Process]::new();$disposed=@{Count=0}
        $disposeHandler=[EventHandler]({param($sender,$eventArgs)$disposed.Count++}.GetNewClosure())
        $unstarted.add_Disposed($disposeHandler)
        try{
            Close-TestOwnedProcess $unstarted
            Check ($disposed.Count -eq 1) 'TEST_UNSTARTED_NOT_DISPOSED'
        }finally{$unstarted.Dispose()}
        $startedChild=[Diagnostics.Process]::new();$startedChild.StartInfo=New-P6ProcessStartInfo (ChildSpec)
        $observer=$null
        try{
            Check ($startedChild.Start()) 'TEST_CHILD_START_FAILED'
            $observer=[Diagnostics.Process]::GetProcessById($startedChild.Id)
            Close-TestOwnedProcess $startedChild
            Check ($observer.WaitForExit(1000) -and $observer.HasExited) 'TEST_STARTED_NOT_STOPPED'
        }finally{
            if($null -ne $observer){$observer.Dispose()}
            Close-TestOwnedProcess $startedChild
        }
        Check ($null -ne (Get-Command Assert-ResourceHostResult -ErrorAction SilentlyContinue)) 'TEST_HOST_CASE_RESULT_MISSING'
        $normal=Invoke-ResourceTestHost @('-Command','exit 0')
        Assert-ResourceHostCompleted $normal
        $r=Invoke-ResourceTestHost @('-Command','Start-Sleep -Seconds 20') 300
        Check ($r.TimedOut -and $r.Stopped -and $r.Retained -and $r.Code -ceq 'TEST_HOST_TIMEOUT' -and $r.Elapsed -lt 5300) 'TEST_HOST_DEADLINE_MISSING'
        $events=New-Object 'Collections.Generic.List[string]'
        Reject {Assert-ResourceHostResult $r @('resource_pipeline_failure_DEADLINE_API');$events.Add('NEXT_GROUP')} 'TEST_HOST_TIMEOUT'
        Check ($events.Count -eq 0) 'TEST_HOST_TIMEOUT_CONTINUED'
        $single=Invoke-ResourceTestHost @('-File',$PSCommandPath,'-Worker','-Phase','ResourceFailureCleanup','-NameFilter','resource_pipeline_failure_DEADLINE_API')
        $verified=Assert-ResourceHostResult $single @('resource_pipeline_failure_DEADLINE_API')
        Check ($verified.Total -eq 1 -and $verified.Passed -eq 1 -and $verified.Failed -eq 0) 'TEST_HOST_SINGLE_CASE_MISSING'
        Reject {Assert-ResourceHostResult $single @('resource_pipeline_failure_DELETE')} 'TEST_HOST_COVERAGE_INVALID'
    }
    Case 'D3_test_host_coverage_rejects_duplicates_and_omissions' {
        Check ($null -ne (Get-Command Get-ResourceHostPlan -ErrorAction SilentlyContinue)) 'TEST_HOST_CASE_PLAN_MISSING'
        $plan=@(Get-ResourceHostPlan)
        Check ($plan.Count -eq 54) 'TEST_HOST_PLAN_GROUPS_WRONG'
        $singleHosts=@($plan)
        Check (@($singleHosts|Where-Object{$_.Count -ne 1}).Count -eq 0) 'TEST_HOST_SINGLE_PLAN_MISSING'
        foreach($hostCase in $singleHosts){Check ($hostCase.Count -eq 1 -and $hostCase.Names.Count -eq 1 -and $hostCase.NameFilter -ceq $hostCase.Names[0]) 'TEST_HOST_FILTER_NOT_EXACT'}
        Assert-ResourceCaseCoverage @('A','B') 2
        Reject {Assert-ResourceCaseCoverage @('A','A') 2} 'TEST_HOST_COVERAGE_INVALID'
        Reject {Assert-ResourceCaseCoverage @('A') 2} 'TEST_HOST_COVERAGE_INVALID'
        $stageNames=@('resource_pipeline_real_stage_order_and_safe_report')+@('SNAPSHOT','PG_START','PG_READY','PG_TOOL','API_START','API_READY','API_TOOL','GW_START','GW_READY','GW_TOOL'|ForEach-Object{'resource_pipeline_failure_'+$_})
        $cleanupNames=@('DEADLINE_API','RETAIN_API','GW_STOP','API_STOP','PG_STOP','REMOVAL','DELETE','REPORT','GUARD_API_TOOL_AFTER'|ForEach-Object{'resource_pipeline_failure_'+$_})
        Assert-ResourceCaseCoverage $stageNames 11
        Assert-ResourceCaseCoverage $cleanupNames 9
        Assert-ResourceCaseCoverage @($singleHosts|Where-Object{$_.Name -cin @('ResourceStages','ResourceFailureCleanup')}|ForEach-Object{$_.NameFilter}) 20 @($stageNames+$cleanupNames)
        foreach($hostCase in $singleHosts){Assert-ResourceWorkerFilter $hostCase.Name $hostCase.NameFilter}
        Reject {Assert-ResourceWorkerFilter 'Held' ''} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'Held' 'D3_java*'} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'CoreIdentityLifecycle' 'D3_removal_success'} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'All' 'D3_lifetime_exact_thirty_minutes_and_clock_rollback'} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'ResourceStages' ''} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'ResourceStages' 'resource_pipeline_failure_*'} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'ResourceStages' 'resource_pipeline_failure_DEADLINE_API'} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        Reject {Assert-ResourceWorkerFilter 'ResourceFailureCleanup' 'resource_pipeline_failure_UNKNOWN'} 'TEST_HOST_EXACT_FILTER_REQUIRED'
        $allNames=@($plan|ForEach-Object{$_.Names})
        Assert-ResourceCaseCoverage $allNames 54
        Reject {Assert-ResourceCaseCoverage @('A','C') 2 @('A','B')} 'TEST_HOST_COVERAGE_INVALID'
        Assert-ResourceCaseCoverage @($stageNames+$cleanupNames) 20
        foreach($name in $stageNames){Check ((Get-ResourceCaseGroup $name) -ceq 'ResourceStages') 'TEST_HOST_STAGE_GROUP_WRONG'}
        foreach($name in $cleanupNames){Check ((Get-ResourceCaseGroup $name) -ceq 'ResourceFailureCleanup') 'TEST_HOST_CLEANUP_GROUP_WRONG'}
        Reject {Get-ResourceCaseGroup 'resource_pipeline_failure_UNKNOWN'} 'TEST_HOST_CASE_GROUP_UNKNOWN'
    }
    Case 'D3_executable_case_only_receipt_and_identity' {
        $ctx=NewFixture;Write-P6ChainedResourceReceipt $ctx 'PG' (Record $ctx 'PG' 424241)
        $record=Record $ctx 'API' 424242;$record.ExecutablePath=$record.ExecutablePath.ToLowerInvariant()
        Write-P6ChainedResourceReceipt $ctx 'API' $record
        $observed=$record|ConvertTo-Json|ConvertFrom-Json
        $observed.ExecutablePath=$record.ExecutablePath.ToUpperInvariant()
        $observed|Add-Member CommandLine ('"'+$record.ExecutablePath+'" '+$record.ArgumentMarker+' -jar synthetic.jar')
        Assert-P6ProcessIdentity $ctx.Receipt $record $observed|Out-Null
    }
    Case 'D3_executable_case_only_postgres_command' {
        $ctx=NewFixture;$record=Record $ctx 'PG' 424241
        $record.ExecutablePath=$record.ExecutablePath.ToLowerInvariant()
        Write-P6ChainedResourceReceipt $ctx 'PG' $record
        $observed=$record|ConvertTo-Json|ConvertFrom-Json
        $observed.ExecutablePath=$record.ExecutablePath.ToUpperInvariant()
        $observed|Add-Member CommandLine ('"'+$observed.ExecutablePath+'" -D "'+$ctx.Receipt.PgData+'" -h 127.0.0.1 -p 45431')
        Assert-P6ProcessIdentity $ctx.Receipt $record $observed|Out-Null
    }
    foreach($badExe in @('C:\wrong\java.exe','C:\Program Files\Java\jdk-21.0.10\bin\wrong.exe','C:\Program Files\Java\jdk-21.0.10\bin\java.exe\extra','C:java.exe','C:\Program Files\Java\jdk-21.0.10\bin\java.exe:stream','\\server\share\java.exe','\\?\C:\Program Files\Java\jdk-21.0.10\bin\java.exe')){
        $badIndex=[array]::IndexOf(@('C:\wrong\java.exe','C:\Program Files\Java\jdk-21.0.10\bin\wrong.exe','C:\Program Files\Java\jdk-21.0.10\bin\java.exe\extra','C:java.exe','C:\Program Files\Java\jdk-21.0.10\bin\java.exe:stream','\\server\share\java.exe','\\?\C:\Program Files\Java\jdk-21.0.10\bin\java.exe'),$badExe)
        Case ('D3_executable_rejects_nonidentity_'+$badIndex) {
            $ctx=NewFixture;Write-P6ChainedResourceReceipt $ctx 'PG' (Record $ctx 'PG' 424241)
            $record=Record $ctx 'API' 424242;$record.ExecutablePath=$badExe
            Reject {Write-P6ChainedResourceReceipt $ctx 'API' $record} 'REHEARSAL_RECEIPT_CHAIN_UNPROVEN'
        }
    }
    Case 'D3_lifetime_exact_thirty_minutes_and_clock_rollback' {
        $tick=@{Value=[long]0};$clock={$tick.Value}.GetNewClosure()
        $life=New-P6ResourceLifetime $clock
        Assert-P6ResourceLifetime $life
        $tick.Value=1799999;Assert-P6ResourceLifetime $life
        $tick.Value=1800000;Reject {Assert-P6ResourceLifetime $life} 'REHEARSAL_TOTAL_DEADLINE'
        $tick.Value=100;$life=New-P6ResourceLifetime $clock;$tick.Value=99
        Reject {Assert-P6ResourceLifetime $life} 'REHEARSAL_CLOCK_INVALID'
    }
    Case 'D3_child_health_rechecked_during_actual_wait' {
        $held=New-Object 'Collections.Generic.List[object]';$counter=@{Value=0}
        $prepared=[Diagnostics.Process]::new()
        # Deliberately consume the budget in a bounded test-factory seam; never start here.
        $factory={param($info)$prepared.StartInfo=$info;$held.Add($prepared);[Threading.Thread]::Sleep(150);$prepared}.GetNewClosure()
        $probe={$counter.Value++;if($counter.Value -eq 2){[Threading.Thread]::Sleep(350)};$true}.GetNewClosure()
        Initialize-P6StreamDrain;$spec=ChildSpec
        $hasLegacyCallback=(Get-Command Invoke-P6BoundedChild).Parameters.ContainsKey('HealthProbe')
        try{
            $timer=[Diagnostics.Stopwatch]::StartNew()
            if($hasLegacyCallback){$result=Invoke-P6BoundedChild $spec 100 $factory $probe}
            else{$result=Invoke-P6BoundedChild $spec 100 $factory}
            $timer.Stop()
            [Console]::Out.WriteLine(('TEST_DIAG BUDGET_MS=100 ELAPSED_MS={0} FUNCTION_MS={1} LAUNCH_MS={2} DRAIN_MS={3} CODE={4} CLEANUP={5}' -f $timer.ElapsedMilliseconds,(Get-P6Field $result 'ElapsedMilliseconds'),(Get-P6Field $result 'LaunchElapsedMilliseconds'),(Get-P6Field $result 'DrainElapsedMilliseconds'),$result.Code,$result.Cleanup))
            $associated=$false;try{$unused=$prepared.GetType().GetProperty('Id').GetValue($prepared,$null);$associated=$true}catch{}
            Check ($counter.Value -eq 0 -and -not $hasLegacyCallback) 'SYNCHRONOUS_CALLBACK_REINTRODUCED'
            Check ($result.Code -ceq 'REHEARSAL_NATIVE_TIMEOUT' -and $timer.ElapsedMilliseconds -le 1000 -and -not $associated -and $result.LaunchElapsedMilliseconds -eq -1 -and $result.DrainElapsedMilliseconds -eq -1 -and $result.Cleanup -ceq 'NOT_STARTED' -and $result.CleanupWaitMilliseconds -eq 0) 'SYNCHRONOUS_PROBE_BROKE_DEADLINE'
            # A real synchronous-callback mutation must fail the interface/call-count contract,
            # even though its 350ms body fits the Windows/JIT scheduling tolerance of 1000ms.
            $body=(Get-Command Invoke-P6BoundedChild).ScriptBlock.ToString()
            $body=$body.Replace('$QuickState=$null)','$QuickState=$null,[scriptblock]$HealthProbe=$null)')
            $body=$body.Replace('$timer=[Diagnostics.Stopwatch]::StartNew()','$timer=[Diagnostics.Stopwatch]::StartNew(); if($null -ne $HealthProbe){&$HealthProbe|Out-Null}')
            $mutant=[scriptblock]::Create($body);$mutationCalls=@{Count=0}
            $slow={$mutationCalls.Count++;[Threading.Thread]::Sleep(350)}.GetNewClosure()
            $changed=&$mutant $spec 100 $null $null $slow
            Check ($mutationCalls.Count -eq 1 -and $changed.Cleanup -ceq 'NOT_STARTED') 'SYNC_CALLBACK_MUTATION_NOT_EXERCISED'
            Test-Fix1QuickGuard
            Test-Fix3DrainFailureRetainsProcess
        }finally{foreach($p in $held){if($null -ne $result -and $result.Cleanup -ceq 'NOT_STARTED'){$p.Dispose()}else{Close-TestOwnedProcess $p}}}
    }
    Case 'D3_child_unknown_stop_retains_original_handle' {
        $held=New-Object 'Collections.Generic.List[object]'
        Initialize-P6StreamDrain
        if($null -eq ('P6LateStartTestProcess' -as [type])){
            Add-Type -TypeDefinition @'
public sealed class P6LateStartTestProcess : System.Diagnostics.Process {
    public new bool Start() {
        bool started = base.Start();
        System.Threading.Thread.Sleep(1300);
        return started;
    }
}
'@ | Out-Null
        }
        $prepared=New-Object P6LateStartTestProcess
        # Fixed synthetic slow initialization: this case must reach an actually started child.
        $factory={param($info)$prepared.StartInfo=$info;$held.Add($prepared);[Threading.Thread]::Sleep(750);$prepared}.GetNewClosure()
        try{
            $timer=[Diagnostics.Stopwatch]::StartNew();$result=Invoke-P6BoundedChild (ChildSpec) 2000 $factory;$timer.Stop()
            $associated=$false;try{$unused=$prepared.GetType().GetProperty('Id').GetValue($prepared,$null);$associated=$true}catch{}
            $exited=if($associated){[string]$prepared.HasExited}else{'UNKNOWN'}
            [Console]::Out.WriteLine(('TEST_DIAG UNKNOWN_STOP CODE={0} CLEANUP={1} RETAINED={2} LAUNCH_MS={3} DRAIN_MS={4} CHILD_ASSOCIATED={5} HAS_EXITED={6} ELAPSED_MS={7}' -f $result.Code,$result.Cleanup,$result.Retained,$result.LaunchElapsedMilliseconds,$result.DrainElapsedMilliseconds,$associated,$exited,$timer.ElapsedMilliseconds))
            Check ($associated -and $result.LaunchElapsedMilliseconds -ge 0 -and $result.DrainElapsedMilliseconds -ge 0) 'TARGET_STARTED_BRANCH_NOT_REACHED'
            Check ((Get-P6Field $result 'Retained') -eq $true -and -not $held[0].HasExited) 'UNKNOWN_STOP_WAS_DISCARDED'
            Check ($result.Cleanup -ceq 'RETAINED') 'UNKNOWN_STOP_CLEANUP_STATE_WRONG'
            Check ([object]::ReferenceEquals($result.HeldTicket.Process,$held[0])) 'ORIGINAL_HANDLE_LOST'
            Check ($result.Code -ceq 'REHEARSAL_NATIVE_TIMEOUT' -and $result.LaunchElapsedMilliseconds -ge 0 -and $result.DrainElapsedMilliseconds -ge 0 -and $result.CleanupWaitMilliseconds -eq 0 -and $timer.ElapsedMilliseconds -le 3500) 'DEADLINE_ADDED_CLEANUP_WAIT'
            Test-Fix1RetainedBeforePostProof
        }finally{foreach($p in $held){Close-TestOwnedProcess $p}}
    }
    Case 'D3_child_rejects_deadline_above_sixty_seconds_before_start' {
        $started=@{Value=0};$factory={param($info)$started.Value++;throw 'SYNTHETIC_SECRET'}.GetNewClosure()
        $result=Invoke-P6BoundedChild (ChildSpec) 60001 $factory
        Check ($result.Status -ceq 'FAILED' -and $started.Value -eq 0) 'OVERLONG_CHILD_STARTED'
    }
    Case 'D3_receipt_chain_zero_pg_api_gw_preserves_predecessors' {
        $ctx=NewFixture;$prior=$ctx.ReceiptHash
        foreach($role in @('PG','API','GW')){
            $count=@($ctx.Receipt.Processes).Count
            Write-P6ChainedResourceReceipt $ctx $role (Record $ctx $role (424241+$count))
            $disk=[IO.File]::ReadAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.json'))|ConvertFrom-Json
            Check ($disk.Sequence -eq ($count+1) -and $disk.PreviousSha256 -ceq $prior -and $disk.Processes.Count -eq ($count+1)) 'CHAIN_TRANSITION_MISSING'
            $backup=Join-Path $ctx.Receipt.RunDirectory ('receipt.'+$count.ToString('000')+'.json')
            Check ((Get-P6Sha256 ([IO.File]::ReadAllBytes($backup))) -ceq $prior) 'PREDECESSOR_NOT_PRESERVED'
            $prior=$ctx.ReceiptHash
        }
    }
    foreach($shape in @('disk_drift','backup_exists','role_order','duplicate_pid','wrong_exe','wrong_nonce','modified_prefix')){
        Case ('D3_receipt_refuses_'+$shape) {
            $ctx=NewFixture
            Write-P6ChainedResourceReceipt $ctx 'PG' (Record $ctx 'PG' 424241)
            $next=Record $ctx 'API' 424242;$role='API'
            switch($shape){
                'disk_drift' {[IO.File]::AppendAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.json'),' ')}
                'backup_exists' {[IO.File]::WriteAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.001.json'),'KEEP')}
                'role_order' {$role='GW';$next.Role='GW'}
                'duplicate_pid' {$next.Pid=424241}
                'wrong_exe' {$next.ExecutablePath='D:\synthetic-unowned.exe'}
                'wrong_nonce' {$next.OwnerNonce='b'*64}
                'modified_prefix' {$ctx.Receipt.Processes[0].Pid=424249}
            }
            $before=[IO.File]::ReadAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.json'))
            Reject {Write-P6ChainedResourceReceipt $ctx $role $next} 'REHEARSAL_RECEIPT_CHAIN_UNPROVEN'
            Check ([IO.File]::ReadAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.json')) -ceq $before) 'FAILED_CHAIN_REPLACED_RECEIPT'
            if($shape -ceq 'backup_exists'){Check ([IO.File]::ReadAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.001.json')) -ceq 'KEEP') 'BACKUP_OVERWRITTEN'}
        }
    }
    Case 'D3_current_receipt_rejects_predecessor_tamper' {
        $ctx=NewFixture;Write-P6ChainedResourceReceipt $ctx 'PG' (Record $ctx 'PG' 424241)
        Assert-P6CurrentResourceReceipt $ctx
        [IO.File]::AppendAllText((Join-Path $ctx.Receipt.RunDirectory 'receipt.000.json'),' ')
        Reject {Assert-P6CurrentResourceReceipt $ctx} 'REHEARSAL_RECEIPT_CHAIN_UNPROVEN'
    }
    foreach($shape in @('success','listener','inventory','query_failure','short_ticket','marker_drift','wrong_root')){
        Case ('D3_removal_'+$shape) {
            $parent=Get-P6NativeRunParent $testRoot;$ctx=NewFixture $parent
            $ctx|Add-Member RepositoryRoot $testRoot;$ctx|Add-Member Tickets ([ordered]@{});$ctx|Add-Member ShortTickets (New-Object 'Collections.Generic.List[object]')
            $ctx|Add-Member ToolProcessIds @()
            $observe={param($kind,$id)
                if($shape -ceq 'query_failure'){throw 'SYNTHETIC_SECRET'}
                if($kind -ceq 'LISTENERS'){
                    $items=@(if($shape -ceq 'listener'){[pscustomobject]@{ObservationSucceeded=$true;LocalAddress='127.0.0.1';LocalPort=45431;OwningProcess=424245}})
                    return [pscustomobject]@{Succeeded=$true;Items=$items}
                }
                if($kind -ceq 'RUN_PROCESSES'){return [pscustomobject]@{Succeeded=$true;Items=@(if($shape -ceq 'inventory'){424245})}}
                throw 'TEST_UNEXPECTED_PROCESS_QUERY'
            }.GetNewClosure()
            if($shape -ceq 'short_ticket'){$ctx.ShortTickets.Add([pscustomobject]@{Unproven=$true})}
            if($shape -ceq 'marker_drift'){[IO.File]::AppendAllText((Join-Path $ctx.Receipt.RunDirectory 'owner.properties'),"Unknown=synthetic`n")}
            if($shape -ceq 'wrong_root'){$ctx.RepositoryRoot=$repo}
            if($shape -ceq 'success'){
                Test-Fix1MissingRemovalEvidence
                $sibling=Join-Path $parent 'synthetic-keep.txt';[IO.File]::WriteAllText($sibling,'KEEP')
                Check ((Assert-P6ResourceRemoval $ctx $observe) -eq $true) 'REMOVAL_PROOF_MISSING'
                Check ((Remove-P6ResourceRun $ctx $observe) -eq $true -and -not [IO.Directory]::Exists($ctx.Receipt.RunDirectory)) 'EXACT_RUN_NOT_REMOVED'
                Check ([IO.File]::ReadAllText($sibling) -ceq 'KEEP') 'SIBLING_DELETED'
            }else{
                Reject {Remove-P6ResourceRun $ctx $observe} 'REHEARSAL_REMOVAL_UNPROVEN'
                Check ([IO.Directory]::Exists($ctx.Receipt.RunDirectory)) 'UNPROVEN_RUN_REMOVED'
            }
        }
    }
}
if($Phase -cin @('All','Resources','ResourceStages','ResourceFailureCleanup')){
    Case 'resource_pipeline_real_stage_order_and_safe_report' {
        $ctx=NewFixture;$f=NewStatefulBoundary $ctx
        $result=Invoke-P6ResourceStages $ctx $f.Boundary
        Check ($result.Status -ceq 'PASS' -and -not $result.Retained -and $f.State.Deleted -and $f.State.Open.Count -eq 0) 'RESOURCE_PIPELINE_MISSING'
        $events=@($f.State.Events|Where-Object{$_ -cne 'SNAPSHOT'}) -join ','
        Check ($events -ceq 'PG_START,PG_READY,PG_TOOL,API_START,API_READY,API_TOOL,GW_START,GW_READY,GW_TOOL,GW_STOP,API_STOP,PG_STOP,REMOVAL,DELETE,REPORT') 'RESOURCE_ORDER_WRONG'
        Check (($f.State.Effects -join ',') -ceq 'PG,API,GW' -and $ctx.Receipt.Sequence -eq 3) 'RESOURCE_EFFECTS_NOT_REAL'
        Check ($result.Steps.Count -eq 9 -and @($result.Steps|Where-Object{$_.Status -cne 'PASS'}).Count -eq 0) 'RESOURCE_STEPS_MISSING'
    }
    foreach($failure in @('SNAPSHOT','PG_START','PG_READY','PG_TOOL','API_START','API_READY','API_TOOL','GW_START','GW_READY','GW_TOOL','DEADLINE_API','RETAIN_API','GW_STOP','API_STOP','PG_STOP','REMOVAL','DELETE','REPORT','GUARD_API_TOOL_AFTER')){
        Case ('resource_pipeline_failure_'+$failure) {
            $ctx=NewFixture;$f=NewStatefulBoundary $ctx $failure
            $result=Invoke-P6ResourceStages $ctx $f.Boundary
            Check ($result.Status -ceq 'FAIL') 'RESOURCE_FAILURE_PASSED'
            $starts=@($f.State.Events|Where-Object{$_ -cmatch '^(PG|API|GW)_START$'}|ForEach-Object{$_.Replace('_START','')})
            $stops=@($f.State.Events|Where-Object{$_ -cmatch '^(PG|API|GW)_STOP$'}|ForEach-Object{$_.Replace('_STOP','')})
            [array]::Reverse($starts)
            Check (($starts -join ',') -ceq ($stops -join ',')) 'RESOURCE_REVERSE_STOP_MISSING'
            if($failure -cin @('PG_START','PG_READY','PG_TOOL')){Check (-not $f.State.Events.Contains('API_START')) 'FAILURE_CONTINUED_BUSINESS'}
            if($failure -cin @('API_START','API_READY','API_TOOL','DEADLINE_API','RETAIN_API','GUARD_API_TOOL_AFTER')){Check (-not $f.State.Events.Contains('GW_START')) 'FAILURE_CONTINUED_BUSINESS'}
            if($failure -cin @('RETAIN_API','GW_STOP','API_STOP','PG_STOP','REMOVAL','DELETE')){
                Check ($result.Retained -and -not $f.State.Deleted) 'UNPROVEN_CLEANUP_DELETED'
            }
            Check ($null -ne $f.State.Report -and $f.State.Report.Status -ceq 'FAIL') 'FAILURE_REPORT_MISSING'
            if($failure -ceq 'PG_START'){Check (@($result.Steps|Where-Object{$_.Status -ceq 'SKIP'}).Count -eq 8) 'UNEXECUTED_NOT_SKIP'}
        }
    }
}
# 仅删除本宿主创建的随机测试目录；失败/停止不明时保留，不清理别轮证据。
if($script:cleanupProven -and $script:passed -eq $script:total){
    try{
        $expectedParent=[IO.Path]::GetFullPath((Join-Path $repo '.tmp'))
        $resolved=[IO.Path]::GetFullPath($testRoot)
        Check ([IO.Path]::GetDirectoryName($resolved) -ceq $expectedParent -and [IO.Path]::GetFileName($resolved) -cmatch '^p6iso-tests-[a-f0-9]{8}$') 'TEST_CLEANUP_ROOT_INVALID'
        Assert-P6ChildPath $expectedParent $resolved -MustExist|Out-Null
        $pending=New-Object 'Collections.Generic.Stack[string]';$pending.Push($resolved)
        while($pending.Count -gt 0){foreach($item in @(Get-ChildItem -LiteralPath $pending.Pop() -Force)){
            Check (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) 'TEST_CLEANUP_REPARSE'
            if($item.PSIsContainer){$pending.Push($item.FullName)}
        }}
        Remove-Item -LiteralPath $resolved -Recurse -Force
        Check (-not (Test-Path -LiteralPath $resolved)) 'TEST_CLEANUP_RETAINED'
        [Console]::Out.WriteLine('P6_RESOURCE_CLEANUP CHILDREN=0 ARTIFACTS=0')
    }catch{$script:cleanupProven=$false}
}
[Console]::Out.WriteLine(('P6_RESOURCE_TESTS TOTAL={0} PASSED={1} FAILED={2}' -f $script:total,$script:passed,($script:total-$script:passed)))
foreach($failure in $script:failed){[Console]::Out.WriteLine('FAIL='+$failure)}
if($script:total -eq 0 -or $script:passed -ne $script:total -or -not $script:cleanupProven){exit 1}
exit 0
