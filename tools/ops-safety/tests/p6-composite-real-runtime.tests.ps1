$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
$runtime=Join-Path $ops 'p6-composite-real-runtime.ps1'
if(Test-Path $runtime){. $runtime}
if(-not (Get-Command Invoke-P6RuntimeTool -ErrorAction SilentlyContinue)){throw 'RED_REAL_PROCESS_CONSUMER_MISSING'}
$ctx=[pscustomobject]@{Lifetime=(New-P6ResourceLifetime);Tickets=@{};ToolProcessIds=(New-Object 'Collections.Generic.List[int]');ShortTickets=(New-Object 'Collections.Generic.List[object]');Plan=$null;Token='';Evidence=(New-Object 'Collections.Generic.List[object]')}
$spec=[pscustomobject]@{FileName='C:\Program Files\Java\jdk-21.0.10\bin\java.exe';Arguments=@('-version');WorkingDirectory=$ops;Environment=(Get-P6ChildEnvironment $ops)}
$result=Invoke-P6RuntimeTool $ctx $spec 'JAVA_VERSION' 10000
if($result.ExitCode -ne 0 -or $ctx.ToolProcessIds.Count -ne 1 -or $ctx.ShortTickets.Count -ne 0 -or $result.OutputCharacters -le 0){throw 'REAL_PROCESS_EXECUTION_FAILED'}
$spec.Arguments=@('--p6-invalid-option')
$failed=$false
try{Invoke-P6RuntimeTool $ctx $spec 'JAVA_BAD_OPTION' 10000|Out-Null}catch{$failed=$_.Exception.Message -eq 'REHEARSAL_TOOL_FAILED'}
if(-not $failed){throw 'NONZERO_EXIT_NOT_REJECTED'}
foreach($id in $ctx.ToolProcessIds){if(Get-Process -Id $id -ErrorAction SilentlyContinue){throw 'REAL_TOOL_PROCESS_LEAK'}}
$spec=[pscustomobject]@{FileName=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');Arguments=@('-NoProfile','-Command',"[Console]::Out.WriteLine('ACTUAL_OK')");WorkingDirectory=$ops;Environment=(Get-P6ChildEnvironment $ops);ExpectedOutput='WRONG'}
$failed=$false
try{Invoke-P6RuntimeTool $ctx $spec 'EXACT_OUTPUT' 10000|Out-Null}catch{$failed=$_.Exception.Message -eq 'REHEARSAL_TOOL_OUTPUT_INVALID'}
if(-not $failed){throw 'RED_FIXED_OUTPUT_NOT_CONSUMED'}
$spec.ExpectedOutput='ACTUAL_OK';Invoke-P6RuntimeTool $ctx $spec 'EXACT_OUTPUT' 10000|Out-Null
$spec=[pscustomobject]@{FileName=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');Arguments=@('-NoProfile','-Command','Start-Sleep -Seconds 30');WorkingDirectory=$ops;Environment=(Get-P6ChildEnvironment $ops)}
$failed=$false
try{Invoke-P6RuntimeTool $ctx $spec 'TIMEOUT' 1000|Out-Null}catch{$failed=$true}
if($ctx.ShortTickets.Count -ne 0){foreach($t in $ctx.ShortTickets){if(-not $t.Process.HasExited){$t.Process.Kill();[void]$t.Process.WaitForExit(5000)}};throw 'RED_TIMEOUT_ORIGINAL_PROCESS_SURVIVES'}
if(-not $failed){throw 'TIMEOUT_NOT_REJECTED'}
$hostState=Get-P6PostgresHostState
$actualAdmin=(New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if($hostState.IsAdministrator -ne $actualAdmin -or $hostState.CanStartPostgres -eq $actualAdmin){throw 'HOST_STATE_INVALID'}
if($actualAdmin){
 $plan=Get-P6IsolationPlan ([IO.Path]::GetFullPath((Join-Path $ops '../..')))
 $before=@(Get-ChildItem -LiteralPath (Join-Path $plan.RepositoryRoot '.tmp/p6iso') -Directory -ErrorAction SilentlyContinue|ForEach-Object Name)-join ','
 $spec=[pscustomobject]@{FileName=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');Arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $ops 'Invoke-P6CompositeIsolationRehearsal.ps1'),'-Mode','Execute','-ConfirmationToken',$plan.Fingerprint);WorkingDirectory=$ops;Environment=(Get-P6ChildEnvironment $ops);ExpectedOutput='P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_NONADMIN_HOST_REQUIRED ACTIONS=0'}
 # This child must exit nonzero, so directly consume its actual bounded stdout.
 $spec.Environment.PATH+=';'+[IO.Path]::GetDirectoryName((Get-Command git.exe -CommandType Application|Select-Object -First 1).Source)
 $p=New-Object Diagnostics.Process;$p.StartInfo=New-P6ProcessStartInfo $spec;[void]$p.Start();$o=$p.StandardOutput.ReadToEndAsync();$e=$p.StandardError.ReadToEndAsync();if(-not $p.WaitForExit(10000)){ $p.Kill();throw 'HOST_PREFLIGHT_TIMEOUT'}
 if($p.ExitCode -ne 1 -or $o.Result.Trim() -cne $spec.ExpectedOutput -or $e.Result -ne ''){throw 'RED_ADMIN_EXECUTE_NOT_REJECTED'};$p.Dispose()
 $after=@(Get-ChildItem -LiteralPath (Join-Path $plan.RepositoryRoot '.tmp/p6iso') -Directory -ErrorAction SilentlyContinue|ForEach-Object Name)-join ','
 if($before -cne $after -or $plan.Executable -or $plan.HostCanStartPostgres){throw 'ADMIN_EXECUTE_SIDE_EFFECT'}
 $result=Invoke-P6RealIsolationExecution $plan $plan.Fingerprint
 if($null -eq $result.PSObject.Properties['Steps'] -or $null -eq $result.PSObject.Properties['Cleanup']){throw 'RED_EXECUTION_STAGE_CLEANUP_MISSING'}
 if(@($result.Steps|Where-Object Status -eq 'FAIL').Count -ne 1 -or $result.Steps[0].Phase -cne 'SNAPSHOT' -or @($result.Cleanup|Where-Object Status -ne 'SKIP').Count -ne 0){throw 'STAGE_CLEANUP_NOT_ACTUAL'}
}
'REAL_RUNTIME_TESTS=PASS COUNT=7 ACTUAL_PROCESS_START=true'
