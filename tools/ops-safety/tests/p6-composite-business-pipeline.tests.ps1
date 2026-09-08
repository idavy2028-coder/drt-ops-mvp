Set-StrictMode -Version Latest
$pipeline = Join-Path $PSScriptRoot '..\p6-composite-business-pipeline.ps1'
if (-not (Test-Path -LiteralPath $pipeline)) { throw 'C2B_PIPELINE_MISSING' }
. $pipeline

function Assert-C2B($condition,[string]$message) { if (-not $condition) { throw $message } }
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
Write-Output ('C2B_TESTS TOTAL=20 FAILED={0}' -f $script:Failures)
if ($script:Failures -ne 0) { exit 1 }
