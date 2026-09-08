Set-StrictMode -Version Latest
$pipeline = Join-Path $PSScriptRoot '..\p6-composite-business-pipeline.ps1'
if (-not (Test-Path -LiteralPath $pipeline)) { throw 'C2B_PIPELINE_MISSING' }
. (Join-Path $PSScriptRoot '..\p6-composite-isolation-lib.ps1')
. $pipeline

function Assert-C2B($condition,[string]$message) { if (-not $condition) { throw $message } }
$script:C2BFixtures=New-Object 'Collections.Generic.List[string]'
function New-C2BProtectedFixture {
    $repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'));[IO.Directory]::CreateDirectory((Join-Path $repo '.tmp'))|Out-Null;[IO.Directory]::CreateDirectory((Join-Path $repo '.tmp/p6iso'))|Out-Null
    $parent=Get-P6NativeRunParent $repo
    $runId=([guid]::NewGuid().ToString('N'));$run=Join-Path $parent ('native-'+$runId);[IO.Directory]::CreateDirectory((Join-Path $run 'pgdata'))|Out-Null
    Protect-P6RunAcl $run
    $marker=[pscustomobject]@{SchemaVersion=1;RunId=$runId;OwnerNonce=('b'*64);RunDirectory=$run;CreatedAt=([DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o'));PgPort=45101}
    $receipt=[pscustomobject]@{SchemaVersion=1;RunId=$marker.RunId;OwnerNonce=$marker.OwnerNonce;RunDirectory=$run;CreatedAt=$marker.CreatedAt;PgData=(Join-Path $run 'pgdata');PgDatabase='composite_live';MigrationDatabase='composite_onboard';Ports=@(45101,45102,45103,45104);Processes=@()}
    $secrets=@{DbPassword=('Q'*40)}
    Write-P6OwnedStorage $receipt $parent $marker $secrets|Out-Null
    $script:C2BFixtures.Add($run)
    return [pscustomobject]@{RunId=$marker.RunId;Root=$run;RunParent=$parent;Marker=$marker;Receipt=$receipt;Ports=$receipt.Ports;Secrets=@{Db=$secrets.DbPassword}}
}
function New-C2BFakeAdapter {
    param([string]$Failure='')
    $state=[ordered]@{Events=New-Object 'Collections.Generic.List[string]';Writes=0;Stopped=New-Object 'Collections.Generic.List[string]';Retained=$false;PreviewBefore='rows=4;hash=synthetic;version=7';PreviewAfter='rows=4;hash=synthetic;version=7';Secrets=@('SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa');Output='';ReceiptCalls=0;DeadlineCalls=0;BadReceipt=$false;BadPrevious=$false;BadSequence=$false;Expired=$false;Business=[ordered]@{Vehicles=3;Terminals=4;Systems=3;Memberships=4;SystemA=2;SystemB=1;SystemC=1}}
    $adapter=[pscustomobject]@{State=$state;Failure=$Failure}
    $adapter | Add-Member ScriptMethod Invoke {
        param([string]$Stage,[hashtable]$Input)
        $this.State.Events.Add($Stage)
        if ($this.Failure -ceq $Stage) { throw $(if($Stage -ceq 'WIRE'){'secret=SYNTHETIC_SECRET uuid=SYNTHETIC_UUID phone=SYNTHETIC_PHONE path=C:\synthetic\private'}else{'C2B_SYNTHETIC_FAILURE_'+$Stage}) }
        if ($Input.Mode -ceq 'Apply' -and $Stage -match 'APPLY|CREATE|PRESET|BIND|CONFIGURE') { $this.State.Writes++ }
        if ($Stage -match '^PREVIEW_') { return [pscustomobject]@{Rows=4;Hash='synthetic';Version=7;WriteCount=$this.State.Writes} }
        return [pscustomobject]@{Status='PASS';Rows=4;Hash='synthetic';Version=7}
    } -Force
    $adapter | Add-Member ScriptMethod Stop {
        param([string]$Name)
        if ($this.Failure -ceq ('STOP_'+$Name)) { $this.State.Retained=$true; throw 'C2B_STOP_UNKNOWN' }
        $this.State.Stopped.Add($Name)
    } -Force
    $adapter | Add-Member ScriptMethod Summary { return [pscustomobject]$this.State.Business } -Force
    $adapter | Add-Member ScriptMethod Receipt { param([string]$stage) $this.State.ReceiptCalls++; if($this.State.BadReceipt){throw 'bad-chain'}; return [pscustomobject]@{Stage=$stage;PreviousSha256=$(if($this.State.BadPrevious){'c'*64}else{if($this.State.ReceiptCalls -eq 1){'0'*64}else{'b'*64}});Sha256=('b'*64);Sequence=$(if($this.State.BadSequence){9}else{$this.State.ReceiptCalls})} } -Force
    $adapter | Add-Member ScriptMethod Deadline { $this.State.DeadlineCalls++; if($this.State.Expired){return 0}; return 1800000 } -Force
    return $adapter
}

function Invoke-C2BTest([string]$Name,[scriptblock]$Body) {
    try { & $Body; Write-Output "PASS=$Name" } catch { Write-Output "FAIL=$Name CODE=$($_.Exception.Message)"; $script:Failures++ }
}
$script:Failures=0

Invoke-C2BTest 'stateful_success_order_preview_and_redaction' {
    $fake=New-C2BFakeAdapter
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'PASS') 'SUCCESS_NOT_PASS'
    Assert-C2B (($r.Stages -join ',') -ceq 'SNAPSHOT,BUILD,PG,EXTERNAL59,HELPER,LIVE_MIGRATION,API_AUTH,DATA_PREP,PREVIEW,APPLY,GATEWAY,WIRE,TASK12,LEASE_RELEASE,CLEANUP') 'ORDER_WRONG'
    Assert-C2B ($fake.State.Writes -eq 0) 'DRYRUN_WROTE'
    Assert-C2B ($r.SafeOutput -notmatch 'SYNTHETIC_SECRET|[0-9a-f]{32,}|[A-Z]{2}[0-9]{4,}') 'REDACTION_FAILED'
}
Invoke-C2BTest 'fail_fast_marks_unexecuted_skip_and_reverse_stop' {
    $fake=New-C2BFakeAdapter 'API_AUTH'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_SYNTHETIC_FAILURE_API_AUTH') 'FAIL_FAST_MISSING'
    Assert-C2B ((@($r.Steps|Where-Object Status -eq 'SKIP').Count) -ge 5) 'SKIP_MISSING'
    Assert-C2B (($fake.State.Stopped -join ',') -eq 'LIVE_MIGRATION,HELPER,EXTERNAL59,PG,BUILD') 'REVERSE_STOP_MISSING'
}
Invoke-C2BTest 'preview_hash_rowcount_version_unchanged' {
    $fake=New-C2BFakeAdapter
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.PreviewStable -eq $true) 'PREVIEW_CHANGED'
}
Invoke-C2BTest 'unknown_stop_retains' {
    $fake=New-C2BFakeAdapter 'STOP_PG'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Retained -eq $true) 'UNKNOWN_STOP_NOT_RETAINED'
}
Invoke-C2BTest 'business_cardinality_and_distribution' {
    $fake=New-C2BFakeAdapter
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Business.Vehicles -eq 3 -and $r.Business.Terminals -eq 4 -and $r.Business.Systems -eq 3 -and $r.Business.Memberships -eq 4) 'BUSINESS_CARDINALITY_NOT_PROVEN'
    Assert-C2B ($r.Business.SystemA -eq 2 -and $r.Business.SystemB -eq 1 -and $r.Business.SystemC -eq 1) 'SYSTEM_DISTRIBUTION_NOT_PROVEN'
}
Invoke-C2BTest 'apply_writes_only_after_stable_preview' {
    $fake=New-C2BFakeAdapter
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode Apply
    Assert-C2B ($r.Status -ceq 'PASS' -and $r.PreviewStable -and $r.PreviewBeforeWrites -eq 0 -and $r.PreviewAfterWrites -eq 0 -and $fake.State.Writes -gt 0) 'APPLY_PREVIEW_WRITE_BOUNDARY_INVALID'
}
Invoke-C2BTest 'business_cardinality_failure_fails_fast' {
    $fake=New-C2BFakeAdapter
    $fake.State.Business.SystemB=2
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_SYSTEM_DISTRIBUTION_INVALID' -and (@($r.Steps|Where-Object Status -eq 'SKIP').Count -ge 6)) 'BUSINESS_FAILURE_NOT_FAIL_FAST'
}
Invoke-C2BTest 'adapter_contract_requires_stateful_boundaries' {
    $fake=New-C2BFakeAdapter
    Assert-C2B (Assert-P6CompositeBusinessAdapterContract $fake) 'ADAPTER_CONTRACT_NOT_ACCEPTED'
}
Invoke-C2BTest 'adapter_contract_rejects_caller_control' {
    $fake=New-C2BFakeAdapter
    $fake|Add-Member NoteProperty Secret 'SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa'
    try { Assert-P6CompositeBusinessAdapterContract $fake; throw 'CONTRACT_ACCEPTED_UNSAFE_PROPERTY' } catch { Assert-C2B ($_.Exception.Message -ceq 'C2B_CALLER_CONTROL_REJECTED') 'UNSAFE_PROPERTY_NOT_REJECTED' }
}
Invoke-C2BTest 'contract_failure_has_fixed_safe_result' {
    $fake=New-C2BFakeAdapter
    $fake|Add-Member NoteProperty Secret 'SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Phase -ceq 'CONTRACT' -and $r.Code -ceq 'C2B_ADAPTER_CONTRACT_INVALID' -and $r.SafeOutput -notmatch 'SYNTHETIC_SECRET') 'CONTRACT_FAILURE_UNSAFE'
}
Invoke-C2BTest 'stop_failure_is_final_and_retained' {
    $fake=New-C2BFakeAdapter 'STOP_PG'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_STOP_UNPROVEN' -and $r.Retained -and $r.SafeOutput -match 'Retained=True') 'STOP_FAILURE_NOT_FINAL'
}
Invoke-C2BTest 'all_resource_stages_enter_owned_ledger' {
    $fake=New-C2BFakeAdapter
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B (@($r.StartedStages).Count -eq 10) 'RESOURCE_LEDGER_INCOMPLETE'
    Assert-C2B (($r.StoppedStages -join ',') -eq 'LEASE_RELEASE,TASK12,WIRE,GATEWAY,API_AUTH,LIVE_MIGRATION,HELPER,EXTERNAL59,PG,BUILD') 'RESOURCE_LEDGER_ORDER_WRONG'
}
Invoke-C2BTest 'sensitive_adapter_error_is_fixed_code' {
    $fake=New-C2BFakeAdapter 'WIRE'
    $fake|Add-Member NoteProperty Secret 'SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Code -notmatch 'SYNTHETIC_SECRET|Exception|System\.|[0-9a-f]{32,}' -and $r.SafeOutput -notmatch 'SYNTHETIC_SECRET') 'SENSITIVE_ERROR_LEAKED'
}
Invoke-C2BTest 'fake_contract_has_deadline_and_receipt_chain' {
    $fake=New-C2BFakeAdapter
    Assert-C2B (Assert-P6CompositeBusinessAdapterContract $fake) 'FAKE_CONTRACT_REJECTED'
    Assert-C2B ($fake.PSObject.Methods.Name -contains 'Receipt' -and $fake.PSObject.Methods.Name -contains 'Deadline') 'DEADLINE_RECEIPT_CONTRACT_MISSING'
}
Invoke-C2BTest 'pipeline_uses_deadline_and_receipt_chain' {
    $fake=New-C2BFakeAdapter
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'PASS' -and $fake.State.DeadlineCalls -gt 0 -and $fake.State.ReceiptCalls -gt 0) 'PIPELINE_DID_NOT_USE_SAFETY_INTERFACES'
}
Invoke-C2BTest 'broken_receipt_chain_fails_fast' {
    $fake=New-C2BFakeAdapter;$fake.State.BadReceipt=$true
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_RECEIPT_CHAIN_INVALID') 'BROKEN_RECEIPT_ACCEPTED'
}
Invoke-C2BTest 'expired_deadline_fails_fast' {
    $fake=New-C2BFakeAdapter;$fake.State.Expired=$true
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_DEADLINE_EXCEEDED') 'EXPIRED_DEADLINE_ACCEPTED'
}
Invoke-C2BTest 'wire_sensitive_exception_is_normalized' {
    $fake=New-C2BFakeAdapter 'WIRE'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_STAGE_FAILED' -and $r.SafeOutput -notmatch 'SYNTHETIC_SECRET|SYNTHETIC_UUID|SYNTHETIC_PHONE|synthetic\\private') 'WIRE_EXCEPTION_LEAKED_OR_NOT_REACHED'
}
Invoke-C2BTest 'receipt_predecessor_mismatch_fails_fast' {
    $fake=New-C2BFakeAdapter;$fake.State.BadPrevious=$true
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_RECEIPT_CHAIN_INVALID') 'RECEIPT_PREDECESSOR_ACCEPTED'
}
Invoke-C2BTest 'receipt_sequence_jump_fails_fast' {
    $fake=New-C2BFakeAdapter;$fake.State.BadSequence=$true
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $fake -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_RECEIPT_CHAIN_INVALID') 'RECEIPT_SEQUENCE_JUMP_ACCEPTED'
}
Invoke-C2BTest 'real_adapter_seam_is_explicitly_not_ready' {
    Assert-C2B ($null -ne (Get-Command New-P6CompositeRealAdapter -ErrorAction SilentlyContinue)) 'REAL_ADAPTER_FACTORY_MISSING'
    $ctx=[pscustomobject]@{RunId='synthetic-run';Root='C:\synthetic\run';Ports=@(45101,45102,45103,45104);Secrets=@{Db='SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa'}}
    $adapter=New-P6CompositeRealAdapter -Context $ctx
    try { $adapter.Invoke('BUILD',@{Mode='DryRun'}); throw 'REAL_ADAPTER_FALLBACK_ACCEPTED' } catch { $m=$_.Exception.Message; if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message}; Assert-C2B ($m -ceq 'C2B_REAL_ADAPTER_NOT_READY') 'REAL_ADAPTER_NOT_READY_CODE_MISSING' }
}
Invoke-C2BTest 'real_adapter_owns_boundary_inputs' {
    $ctx=[pscustomobject]@{RunId='synthetic-run';Root='C:\synthetic\run';Ports=@(45101,45102,45103,45104);Secrets=@{Db='SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa'}}
    $adapter=New-P6CompositeRealAdapter -Context $ctx
    Assert-C2B ($adapter.Boundary -ceq 'RUNNER_OWNED_LOOPBACK_ONLY' -and $adapter.Environment.SERVER_ADDRESS -ceq '127.0.0.1') 'REAL_ADAPTER_BOUNDARY_INVALID'
    Assert-C2B ($adapter.Environment.DRT_OPS_DATASOURCE_PASSWORD -ceq $ctx.Secrets.Db) 'REAL_ADAPTER_SECRET_ENV_INVALID'
    Assert-C2B (-not ($adapter.PSObject.Properties.Name -contains 'Endpoint') -and -not ($adapter.PSObject.Properties.Name -contains 'Pid')) 'REAL_ADAPTER_CALLER_CONTROL_EXPOSED'
}
Invoke-C2BTest 'real_adapter_rejects_mutated_owned_boundary' {
    $ctx=[pscustomobject]@{RunId='synthetic-run';Root='C:\synthetic\run';Ports=@(45101,45102,45103,45104);Secrets=@{Db='SYNTHETIC_SECRET_aaaaaaaaaaaaaaaaaaaaaaaa'}}
    foreach($mutation in @('Root','Ports','Environment')){
        $adapter=New-P6CompositeRealAdapter -Context $ctx
        if($mutation -ceq 'Root'){$adapter.Root='C:\attacker'}
        elseif($mutation -ceq 'Ports'){$adapter.Ports[0]=45999}
        else{$adapter.Environment.SERVER_ADDRESS='0.0.0.0'}
        try { Assert-P6CompositeBusinessAdapterContract $adapter; throw 'MUTATION_ACCEPTED' } catch { Assert-C2B ($_.Exception.Message -ceq 'C2B_CALLER_CONTROL_REJECTED') "MUTATION_NOT_REJECTED_$mutation" }
    }
}
Invoke-C2BTest 'build_pg_flyway_seam_is_safe_and_ordered' {
    Assert-C2B ($null -ne (Get-Command New-P6BuildPgFlywayAdapter -ErrorAction SilentlyContinue)) 'BUILD_PG_ADAPTER_MISSING'
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx
    $r=$a.DryRun()
    Assert-C2B (($r.Stages -join ',') -eq 'BUILD,PG_CREATE,EXTERNAL59,LIVE_V19,PREPARE_V20,LIVE_V20,LIVE_V21,VALIDATE') 'BUILD_PG_ORDER_WRONG'
    Assert-C2B ($r.BuildBeforePg -and $r.DatabaseCount -eq 2 -and $r.BindAddress -eq '127.0.0.1' -and $r.ExternalMigrationCount -eq 59 -and $r.LiveMigration -eq 'V19,PREPARE_V20,V20,V21,VALIDATE') 'BUILD_PG_CONTRACT_INVALID'
    Assert-C2B ($r.SideEffectCount -eq 0 -and $r.Stdout -notmatch 'SYNTHETIC_SECRET' -and $r.PrepareUpdateCount -eq 2 -and $r.DemoRetained) 'BUILD_PG_DRYRUN_SIDE_EFFECT_OR_LEAK'
}
Invoke-C2BTest 'v20_fixture_identity_mismatch_is_rejected' {
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx;$a.DemoIdentities=@('bad','33333333-3333-3333-3333-333333333332')
    try {$a.DryRun();throw 'V20_FIXTURE_ACCEPTED'} catch {$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_V20_FIXTURE_INVALID') 'V20_IDENTITY_NOT_REJECTED'}
}
Invoke-C2BTest 'v20_fixture_update_count_is_rejected' {
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx;$a.PrepareUpdateCount=1
    try {$a.DryRun();throw 'V20_COUNT_ACCEPTED'} catch {$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_V20_FIXTURE_INVALID') 'V20_COUNT_NOT_REJECTED'}
}
Invoke-C2BTest 'v20_fixture_delete_is_rejected' {
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx;$a.DemoRetained=$false
    try {$a.DryRun();throw 'V20_DEMO_DELETED_ACCEPTED'} catch {$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_V20_FIXTURE_INVALID') 'V20_DELETE_NOT_REJECTED'}
}
Invoke-C2BTest 'build_pg_root_and_ports_are_revalidated' {
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx;$a.Root='C:\attacker';try{$a.DryRun();throw 'ROOT_ACCEPTED'}catch{$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_BOUNDARY_INVALID') 'ROOT_NOT_REJECTED'}
    $a=New-P6BuildPgFlywayAdapter -Context $ctx;$a.Ports[0]=45102;try{$a.DryRun();throw 'PORT_ACCEPTED'}catch{$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_BOUNDARY_INVALID') 'PORT_NOT_REJECTED'}
}
Invoke-C2BTest 'build_pg_reuses_c2a_boundary_guards' {
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx
    Assert-C2B ($a.Guards.PSObject.Methods.Name -contains 'PathCheck' -and $a.Guards.PSObject.Methods.Name -contains 'PortsCheck' -and $a.Guards.PSObject.Methods.Name -contains 'OwnerCheck') 'C2A_GUARDS_NOT_REUSED'
    foreach($bad in @('C:\attacker\native-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','C:\'+('x'*250))){$a.Root=$bad;try{$a.DryRun();throw 'BAD_ROOT_ACCEPTED'}catch{$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -in @('C2B_BOUNDARY_INVALID','REHEARSAL_PATH_INVALID','REHEARSAL_PATH_TOO_LONG')) 'BAD_ROOT_NOT_REJECTED'}}
    $a=New-P6BuildPgFlywayAdapter -Context $ctx;$a.Ports[0]=1023;try{$a.DryRun();throw 'BAD_PORT_ACCEPTED'}catch{$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -eq 'C2B_BOUNDARY_INVALID') 'BAD_PORT_NOT_REJECTED'}
}
Invoke-C2BTest 'build_pg_real_actions_are_not_ready' {
    $ctx=New-C2BProtectedFixture
    $a=New-P6BuildPgFlywayAdapter -Context $ctx
    try {$a.Invoke('BUILD');throw 'REAL_BUILD_STARTED'} catch {$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_REAL_ADAPTER_NOT_READY') 'REAL_BUILD_NOT_READY_CODE_MISSING'}
}
Invoke-C2BTest 'api_gateway_wire_task12_seam_contract' {
    Assert-C2B ($null -ne (Get-Command New-P6ApiGatewayWireTask12Adapter -ErrorAction SilentlyContinue)) 'API_GATEWAY_ADAPTER_MISSING'
    $a=New-P6ApiGatewayWireTask12Adapter -Context (New-C2BProtectedFixture)
    $r=$a.DryRun()
    Assert-C2B (($r.Stages -join ',') -eq 'API_HEALTH,LOGIN,ROTATE,RELOGIN,DATA_PREP,PREVIEW,APPLY,GATEWAY,WIRE,TASK12,LEASE_RELEASE') 'API_GATEWAY_ORDER_WRONG'
    Assert-C2B ($r.Vehicles -eq 3 -and $r.Terminals -eq 4 -and $r.Systems -eq 3 -and $r.SystemA -eq 2 -and $r.SystemB -eq 1 -and $r.SystemC -eq 1 -and $r.PreviewStable -and $r.Leases -eq 4 -and $r.ConnectionIds -eq 4 -and $r.Task12 -eq 'ACCEPTED4' -and $r.AttachmentFields -eq 0) 'API_GATEWAY_CONTRACT_INVALID'
    Assert-C2B ($r.SafeOutput -notmatch 'SYNTHETIC_SECRET|SYNTHETIC_UUID|SYNTHETIC_PHONE|Bearer') 'API_GATEWAY_REDACTION_FAILED'
}
Invoke-C2BTest 'api_gateway_real_actions_not_ready' {
    $a=New-P6ApiGatewayWireTask12Adapter -Context (New-C2BProtectedFixture)
    try{$a.Invoke('API_HEALTH');throw 'REAL_API_STARTED'}catch{$m=$_.Exception.Message;if($null -ne $_.Exception.InnerException){$m=$_.Exception.InnerException.Message};Assert-C2B ($m -ceq 'C2B_REAL_ADAPTER_NOT_READY') 'REAL_API_NOT_READY_MISSING'}
}
Invoke-C2BTest 'api_gateway_stateful_failure_paths' {
    foreach($case in @('LOGIN','ROTATE','PREVIEW_MUTATION','DUPLICATE_CONNECTION','MISSING_UPSTREAM','ATTACHMENT','TASK12_STATUS')){
        $a=New-P6ApiGatewayWireTask12Adapter -Context (New-C2BProtectedFixture);$a.Failure=$case
        try{$a.DryRun();throw 'STATEFUL_FAILURE_ACCEPTED'}catch{$m=$_.Exception.Message;$e=$_.Exception;while($null -ne $e.InnerException){$e=$e.InnerException;$m=$e.Message};Assert-C2B ($m -match '^C2B_(AUTH|PREVIEW|CONNECTION|UPSTREAM|ATTACHMENT|TASK12)_[A-Z_]+$') "STATEFUL_${case}_NOT_NORMALIZED"}
    }
}
Invoke-C2BTest 'api_gateway_adapter_integrates_with_business_pipeline' {
    $a=New-P6ApiGatewayWireTask12Adapter -Context (New-C2BProtectedFixture);$a.State.InDryRun=$true
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $a -Mode DryRun
    if(-not ($r.Status -ceq 'PASS' -and $r.Business.Systems -eq 3 -and $r.Business.SystemA -eq 2 -and $r.Business.SystemB -eq 1 -and $r.Business.SystemC -eq 1 -and $a.State.Receipts.Count -eq 15)){Write-Output ('INTEGRATION_FIELDS S='+$r.Status+' CODE='+$r.Code+' SYS='+$r.Business.Systems+' A='+$r.Business.SystemA+' B='+$r.Business.SystemB+' C='+$r.Business.SystemC+' R='+$a.State.Receipts.Count)}
    Assert-C2B ($r.Status -ceq 'PASS' -and $r.Business.AcceptanceStatus -ceq 'ACCEPTED4' -and $r.Business.Systems -eq 3 -and $r.Business.SystemA -eq 2 -and $r.Business.SystemB -eq 1 -and $r.Business.SystemC -eq 1 -and $a.State.Receipts.Count -eq 15) 'API_ADAPTER_PIPELINE_INTEGRATION_FAILED'
}
Invoke-C2BTest 'api_gateway_task12_status_fails_pipeline' {
    $a=New-P6ApiGatewayWireTask12Adapter -Context (New-C2BProtectedFixture);$a.State.InDryRun=$true;$a.Failure='TASK12_STATUS'
    $r=Invoke-P6CompositeBusinessPipeline -Adapter $a -Mode DryRun
    Assert-C2B ($r.Status -ceq 'FAIL' -and $r.Code -ceq 'C2B_TASK12_NOT_ACCEPTED' -and (@($r.Steps|Where-Object {$_.Stage -eq 'TASK12' -and $_.Status -eq 'FAIL'}).Count -eq 1) -and $a.State.Events -notcontains 'LEASE_RELEASE' -and $a.State.Events -notcontains 'CLEANUP') 'TASK12_STATUS_PIPELINE_GATE_FAILED'
}
Invoke-C2BTest 'runtime_command_plan_is_fixed_and_non_executing' {
    Assert-C2B ($null -ne (Get-Command Get-P6RuntimeCommandPlan -ErrorAction SilentlyContinue)) 'RUNTIME_COMMAND_PLAN_MISSING'
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    Assert-C2B ($p.Api.FileName -match 'java.exe$' -and $p.Gateway.FileName -match 'java.exe$' -and $p.Wire.FileName -match 'java.exe$') 'RUNTIME_EXE_INVALID'
    Assert-C2B (($p.Api.Environment.SERVER_ADDRESS -eq '127.0.0.1') -and ($p.Gateway.Environment.JT_GATEWAY_TCP_BIND_ADDRESS -eq '127.0.0.1') -and ($p.Wire.Environment.P6_WIRE_MODE -eq 'SYNTHETIC_ONLY')) 'RUNTIME_LOOPBACK_INVALID'
    Assert-C2B (-not ($p.Api.Environment.Keys | Where-Object {$_ -match 'PASSWORD|SECRET|TOKEN'})) 'RUNTIME_SECRET_ENV_EXPOSED'
    Assert-C2B ($p.ExecuteCount -eq 0 -and $p.Wire.AttachmentFields -eq 0 -and $p.Wire.TerminalCount -eq 4) 'RUNTIME_PLAN_EXECUTED_OR_INVALID'
}
Invoke-C2BTest 'runtime_plan_rejects_owned_input_tamper' {
    foreach($kind in @('Root','Ports','Marker','Receipt','RunParent','BoundaryDigest')){
        $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
        if($kind -ceq 'Root'){$ctx.Root='C:\tampered'}elseif($kind -ceq 'Ports'){$ctx.Ports[0]=45999}elseif($kind -ceq 'Marker'){$ctx.Marker.RunId='c'*32}elseif($kind -ceq 'Receipt'){$ctx.Receipt.RunId='c'*32}elseif($kind -ceq 'RunParent'){$ctx.RunParent='C:\escape'}else{$ctx.BoundaryDigest='0'*64}
        try{Get-P6RuntimeCommandPlan -Context $ctx|Out-Null;throw 'RUNTIME_TAMPER_ACCEPTED'}catch{Assert-C2B ($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID') "RUNTIME_TAMPER_${kind}_NOT_REJECTED"}
    }
}
Invoke-C2BTest 'controlled_runtime_action_never_starts_by_default' {
    Assert-C2B ($null -ne (Get-Command Invoke-P6ControlledRuntimeAction -ErrorAction SilentlyContinue)) 'CONTROLLED_RUNTIME_ENTRY_MISSING'
    $p=[pscustomobject]@{ExecuteCount=0}
    try{Invoke-P6ControlledRuntimeAction -Plan $p -Role API;throw 'RUNTIME_STARTED'}catch{Assert-C2B ($_.Exception.Message -ceq 'C2B_REAL_ADAPTER_NOT_READY') 'RUNTIME_DEFAULT_NOT_READY_MISSING'}
}
Invoke-C2BTest 'controlled_runtime_synthetic_roles_require_verified_plan' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($role in @('BUILD','PG','API','GW','WIRE','TASK12')){$r=Invoke-P6ControlledRuntimeAction -Plan $p -Role $role -SyntheticOnly;Assert-C2B ($r.Status -ceq 'PLANNED' -and -not $r.Started) "ROLE_${role}_INVALID"}
    $p.BoundaryDigest='0'*64;$rejected=$false;try{Invoke-P6ControlledRuntimeAction -Plan $p -Role API -SyntheticOnly|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected 'PLAN_TAMPER_NOT_REJECTED'
}
Invoke-C2BTest 'controlled_runtime_rejects_plan_clones_for_all_roles' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($role in @('BUILD','PG','API','GW','WIRE','TASK12')){foreach($clone in @($p.PSObject.Copy(),($p|ConvertTo-Json -Depth 8|ConvertFrom-Json),[pscustomobject]@{PlanKind=$p.PlanKind;ExecuteCount=0;Root=$p.Root;Ports=$p.Ports;Marker=$p.Marker;Receipt=$p.Receipt;RunParent=$p.RunParent;BoundaryDigest=$p.BoundaryDigest})){ $rejected=$false;try{Invoke-P6ControlledRuntimeAction -Plan $clone -Role $role -SyntheticOnly|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected "CLONE_${role}_ACCEPTED"}}
}
Invoke-C2BTest 'synthetic_process_specs_are_fixed_and_nonstarting' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($role in @('BUILD','PG','API','GW','WIRE','TASK12')){$s=Get-P6SyntheticProcessSpec -Plan $p -Role $role;Assert-C2B (-not $s.StartAllowed -and $s.Environment.P6_SYNTHETIC_ONLY -eq 'true' -and $s.Environment.P6_STREAM_DRAIN -eq 'required' -and $s.ReceiptRequired -and $s.RetainedOnUnknownStop) "SPEC_${role}_INVALID"}
}
Invoke-C2BTest 'synthetic_process_spec_rejects_plan_clones_all_roles' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($role in @('BUILD','PG','API','GW','WIRE','TASK12')){$clone=$p.PSObject.Copy();$rejected=$false;try{Get-P6SyntheticProcessSpec -Plan $clone -Role $role|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected "SPEC_CLONE_${role}_ACCEPTED"}
}
Invoke-C2BTest 'synthetic_process_spec_rejects_deep_and_field_tamper' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($field in @('Root','Ports','Marker','Receipt','RunParent','BoundaryDigest')){$clone=$p|ConvertTo-Json -Depth 8|ConvertFrom-Json;if($field -eq 'Root'){$clone.Root='C:\tampered'}elseif($field -eq 'Ports'){$clone.Ports[0]=45999}elseif($field -eq 'Marker'){$clone.Marker.RunId='c'*32}elseif($field -eq 'Receipt'){$clone.Receipt.RunId='c'*32}elseif($field -eq 'RunParent'){$clone.RunParent='C:\escape'}else{$clone.BoundaryDigest='0'*64};$rejected=$false;try{Get-P6SyntheticProcessSpec -Plan $clone -Role API|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected "SPEC_${field}_TAMPER_ACCEPTED"}
}
Invoke-C2BTest 'synthetic_process_spec_deep_tamper_all_roles' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($role in @('BUILD','PG','API','GW','WIRE','TASK12')){foreach($field in @('Root','Ports','Marker','Receipt','RunParent','BoundaryDigest')){$clone=$p|ConvertTo-Json -Depth 8|ConvertFrom-Json;if($field -eq 'Root'){$clone.Root='C:\tampered'}elseif($field -eq 'Ports'){$clone.Ports[0]=45999}elseif($field -eq 'Marker'){$clone.Marker.RunId='c'*32}elseif($field -eq 'Receipt'){$clone.Receipt.RunId='c'*32}elseif($field -eq 'RunParent'){$clone.RunParent='C:\escape'}else{$clone.BoundaryDigest='0'*64};$rejected=$false;try{Get-P6SyntheticProcessSpec -Plan $clone -Role $role|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected "SPEC_${role}_${field}_ACCEPTED"}}
}
Invoke-C2BTest 'synthetic_process_spec_rejects_unregistered_valid_plans_all_roles' {
    $ctx=New-C2BProtectedFixture;$digest=(([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    foreach($role in @('BUILD','PG','API','GW','WIRE','TASK12')){$p=[pscustomobject]@{PlanKind='RUNTIME_COMMAND_PLAN';ExecuteCount=0;Root=$ctx.Root;Ports=$ctx.Ports;Marker=$ctx.Marker;Receipt=$ctx.Receipt;RunParent=$ctx.RunParent;BoundaryDigest=$digest};$rejected=$false;try{Get-P6SyntheticProcessSpec -Plan $p -Role $role|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected "UNREGISTERED_${role}_ACCEPTED"}
}
Invoke-C2BTest 'real_command_specs_seven_roles_fixed' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($role in @('BUILD','PG','FLYWAY','API','GW','WIRE','TASK12')){$s=Get-P6RealCommandSpec -Plan $p -Role $role;Assert-C2B (-not $s.RealActionEnabled -and $s.Environment.P6_BIND_ADDRESS -eq '127.0.0.1' -and $s.Environment.P6_STREAM_DRAIN -eq 'required' -and $s.ReceiptPredecessor -eq 'REQUIRED' -and $s.Deadline -eq 1800000) "REAL_SPEC_${role}_INVALID";switch($role){'BUILD'{Assert-C2B ($s.FileName -match 'mvn.cmd$' -and ($s.Arguments -join ' ') -match 'package') 'BUILD_ARGS_INVALID'};'PG'{Assert-C2B ($s.FileName -match 'pg_ctl.exe$' -and $s.Arguments[0] -eq 'start') 'PG_ARGS_INVALID'};'FLYWAY'{Assert-C2B (($s.Arguments -join ' ') -match 'P6CompositeFlywayTool') 'FLYWAY_ARGS_INVALID'};'TASK12'{Assert-C2B (($s.Arguments -join ' ') -match 'InvokeTask12Acceptance') 'TASK12_ARGS_INVALID'};default{Assert-C2B (($s.Arguments -join ' ') -match 'jar|P6CompositeWireHarness') "${role}_ARGS_INVALID"}}}
    $p2=$p.PSObject.Copy();$p2.Root='C:\tampered';try{Get-P6RealCommandSpec -Plan $p2 -Role API|Out-Null;throw 'REAL_SPEC_PROVENANCE_ACCEPTED'}catch{Assert-C2B ($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID') 'REAL_SPEC_PROVENANCE_NOT_REJECTED'}
}
Invoke-C2BTest 'real_command_spec_rejects_injected_properties' {
    $ctx=New-C2BProtectedFixture;$ctx|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$ctx.Root+'|'+(@($ctx.Ports)-join ',')+'|'+(($ctx.Marker|ConvertTo-Json -Compress))+'|'+(($ctx.Receipt|ConvertTo-Json -Compress))+'|'+[string]$ctx.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    $p=Get-P6RuntimeCommandPlan -Context $ctx
    foreach($bad in @('Endpoint','Path','Secret','Pid','Skip','Executable','Arguments','WorkingDirectory','Environment')){$clone=$p.PSObject.Copy();$clone|Add-Member NoteProperty $bad 'injected';$rejected=$false;try{Get-P6RealCommandSpec -Plan $clone -Role API|Out-Null}catch{if($_.Exception.Message -ceq 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'){$rejected=$true}};Assert-C2B $rejected "REAL_SPEC_${bad}_INJECTION_ACCEPTED"}
}
foreach($fixture in @($script:C2BFixtures)){ if(Test-Path -LiteralPath $fixture){Remove-Item -LiteralPath $fixture -Recurse -Force}; Assert-C2B (-not (Test-Path -LiteralPath $fixture)) 'FIXTURE_NOT_REMOVED' }
Write-Output ('C2B_TESTS TOTAL=47 FAILED={0}' -f $script:Failures)
if ($script:Failures -ne 0) { exit 1 }
