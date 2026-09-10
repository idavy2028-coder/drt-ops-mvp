$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
. (Join-Path $ops 'p6-composite-real-runtime.ps1')
$valid='{"SchemaVersion":1,"Status":"FAIL","Code":"REHEARSAL_BUSINESS_ASSERTION_FAILED","Step":"LOGIN","ExceptionKind":"ASSERTION","HttpStatus":403,"SqlState":"NONE","AssertionId":"B010"}'
$sql='{"SchemaVersion":1,"Status":"FAIL","Code":"REHEARSAL_BUSINESS_FAILED","Step":"PREVIEW_BEFORE","ExceptionKind":"SQL","HttpStatus":0,"SqlState":"23505","AssertionId":"NONE"}'
$secret='DO_NOT_EXPORT_password_token_url_phone'
$invalid='REHEARSAL_BUSINESS_DIAGNOSTIC_INVALID'
$cases=@(
 @{Name='valid';Text=$valid;Exit=1;Expected='REHEARSAL_BUSINESS_ASSERTION_FAILED'},
 @{Name='sql_valid';Text=$sql;Exit=1;Expected='REHEARSAL_BUSINESS_FAILED'},
 @{Name='sql_other';Text=$sql.Replace('23505','OTHER');Exit=1;Expected='REHEARSAL_BUSINESS_FAILED'},
 @{Name='inconsistent_kind';Text=$sql.Replace('"SQL"','"IO"');Exit=1;Expected=$invalid},
 @{Name='invalid_http';Text=$valid.Replace(':403',':999');Exit=1;Expected=$invalid},
 @{Name='limit_1024';Text=$valid+(' '*1024);Exit=1;Expected=$invalid},
 @{Name='multiple_records';Text=($valid+"`n"+$valid);Exit=1;Expected=$invalid},
 @{Name='arbitrary_code';Text=$valid.Replace('REHEARSAL_BUSINESS_ASSERTION_FAILED',$secret);Exit=1;Expected=$invalid},
 @{Name='extra_field';Text=$valid.Replace('"Step":','"Message":"'+$secret+'","Step":');Exit=1;Expected=$invalid},
 @{Name='duplicate';Text=$valid.Replace('"Step":','"Step":"OWNER_READ","Step":');Exit=1;Expected=$invalid},
 @{Name='unknown_step';Text=$valid.Replace('"LOGIN"','"'+$secret+'"');Exit=1;Expected=$invalid},
 @{Name='unknown_sql';Text=$valid.Replace('"SqlState":"NONE"','"SqlState":"TOKEN"');Exit=1;Expected=$invalid},
 @{Name='unknown_class';Text=$valid.Replace('"ExceptionKind":"ASSERTION"','"ExceptionKind":"'+$secret+'"');Exit=1;Expected=$invalid},
 @{Name='unknown_assertion';Text=$valid.Replace('"B010"','"B999"');Exit=1;Expected=$invalid},
 @{Name='oversized';Text=($secret*200);Exit=1;Expected=$invalid},
 @{Name='truncated';Text=$valid.Substring(0,$valid.Length-3);Exit=1;Expected=$invalid},
 @{Name='failure_exit_zero';Text=$valid;Exit=0;Expected=$invalid},
 @{Name='pass_exit_one';Text='P6_BUSINESS_STATUS=PASS';Exit=1;Expected=$invalid},
 @{Name='success';Text='P6_BUSINESS_STATUS=PASS';Exit=0;Expected='PASS'},
 @{Name='stderr_secret';Text=$valid;Exit=1;Expected='REHEARSAL_BUSINESS_ASSERTION_FAILED';Stderr=$secret}
)
foreach($case in $cases){
 $ctx=[pscustomobject]@{Lifetime=(New-P6ResourceLifetime);Tickets=@{};ShortTickets=(New-Object 'Collections.Generic.List[object]');ToolProcessIds=(New-Object 'Collections.Generic.List[int]');Plan=$null;Evidence=(New-Object 'Collections.Generic.List[object]')}
 $bytes=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($case.Text))
 $command="[Console]::Out.WriteLine([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$bytes')));"
 if($case.ContainsKey('Stderr')){$command+="[Console]::Error.WriteLine('$secret');"}
 $command+='exit '+$case.Exit
 $spec=[pscustomobject]@{FileName=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');WorkingDirectory=$ops;Environment=(Get-P6ChildEnvironment $ops);Arguments=@('-NoProfile','-NonInteractive','-EncodedCommand',[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command)));BusinessResult=$true}
 $actual='PASS'
 try{Invoke-P6RuntimeTool $ctx $spec BUSINESS 10000|Out-Null}catch{$actual=$_.Exception.Message}
 if($actual -cne $case.Expected){throw ('BUSINESS_RESULT_WRONG_CODE_'+$case.Name)}
 $saved=$ctx.Evidence|ConvertTo-Json -Depth 8 -Compress
 if($saved.Contains($secret)-or $ctx.ShortTickets.Count -ne 0){throw 'BUSINESS_RESULT_LEAK_OR_RETAINED'}
 if($case.Expected -ceq 'REHEARSAL_BUSINESS_ASSERTION_FAILED'){
  $result=@($ctx.Evidence|Where-Object {$null -ne $_.PSObject.Properties['BusinessFailure']})
  if($result.Count -ne 1 -or $result[0].BusinessFailure.Step -cne 'LOGIN' -or $result[0].BusinessFailure.HttpStatus -ne 403 -or $result[0].BusinessFailure.AssertionId -cne 'B010'){throw 'BUSINESS_EVIDENCE_NOT_SAVED'}
 }
 if($case.Expected -ceq 'REHEARSAL_BUSINESS_FAILED'){
  $result=@($ctx.Evidence|Where-Object {$null -ne $_.PSObject.Properties['BusinessFailure']})
  $expectedState=if($case.Name -ceq 'sql_valid'){'23505'}else{'OTHER'}
  if($result.Count -ne 1 -or $result[0].BusinessFailure.SqlState -cne $expectedState -or $result[0].Code -cne $case.Expected){throw 'BUSINESS_SQL_EVIDENCE_NOT_SAVED'}
 }
 if($case.Expected -ceq $invalid -and -not $saved.Contains($invalid)){throw 'BUSINESS_REJECTION_NOT_SAVED'}
 foreach($id in $ctx.ToolProcessIds){if(Get-Process -Id $id -ErrorAction SilentlyContinue){throw 'BUSINESS_CHILD_LEAK'}}
}
function Test-BusinessFailureSurvivesGuard($Spec){
 function Assert-P6RuntimeGuard($Context,[string]$Pending=''){if($Context.Evidence.Count -gt 0){throw 'REHEARSAL_TEST_GUARD_FAILED'}}
 $ctx=[pscustomobject]@{Lifetime=(New-P6ResourceLifetime);Tickets=@{};ShortTickets=(New-Object 'Collections.Generic.List[object]');ToolProcessIds=(New-Object 'Collections.Generic.List[int]');Plan=$null;Evidence=(New-Object 'Collections.Generic.List[object]')}
 $caught='';try{Invoke-P6RuntimeTool $ctx $Spec BUSINESS 10000|Out-Null}catch{$caught=$_.Exception.Message}
 if($caught -cne 'REHEARSAL_TEST_GUARD_FAILED' -or $ctx.Evidence.Count -ne 1 -or $null -eq $ctx.Evidence[0].PSObject.Properties['BusinessFailure']){throw 'BUSINESS_FAILURE_LOST_AT_GUARD'}
 if($ctx.Evidence[0].BusinessFailure.Code -cne 'REHEARSAL_BUSINESS_ASSERTION_FAILED'){throw 'ORIGINAL_BUSINESS_CODE_LOST'}
}
Test-BusinessFailureSurvivesGuard $spec
('BUSINESS_RESULT_TESTS=PASS COUNT='+($cases.Count+1)+' ACTUAL_CHILD=true')
