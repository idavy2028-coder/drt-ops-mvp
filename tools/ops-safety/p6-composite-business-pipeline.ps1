# C2b business orchestration seam. External systems are injected through Adapter; real Execute remains gated elsewhere.
Set-StrictMode -Version Latest

function Get-P6CompositeBusinessPlan {
    [pscustomobject]@{Stages=@('SNAPSHOT','BUILD','PG','EXTERNAL59','HELPER','LIVE_MIGRATION','API_AUTH','DATA_PREP','PREVIEW','APPLY','GATEWAY','WIRE','TASK12','LEASE_RELEASE','CLEANUP');ExecuteEnabled=$false;Boundary='STATEFUL_FAKE_ONLY'}
}
function Assert-P6CompositeBusinessAdapterContract {
    param($Adapter)
    if($null -eq $Adapter -or -not ($Adapter.PSObject.Methods.Name -contains 'Invoke') -or -not ($Adapter.PSObject.Methods.Name -contains 'Stop') -or -not ($Adapter.PSObject.Methods.Name -contains 'Summary') -or -not ($Adapter.PSObject.Methods.Name -contains 'Receipt') -or -not ($Adapter.PSObject.Methods.Name -contains 'Deadline')){throw 'C2B_ADAPTER_CONTRACT_INVALID'}
    foreach($property in @('Endpoint','Pid','Secret','Path','Skip')){if($Adapter.PSObject.Properties.Name -contains $property){throw 'C2B_CALLER_CONTROL_REJECTED'}}
    $true
}

function Invoke-P6CompositeBusinessPipeline {
    param(
        [Parameter(Mandatory=$true)]$Adapter,
        [ValidateSet('DryRun','Apply')][string]$Mode='DryRun'
    )
    $stages=@('SNAPSHOT','BUILD','PG','EXTERNAL59','HELPER','LIVE_MIGRATION','API_AUTH','DATA_PREP','PREVIEW','APPLY','GATEWAY','WIRE','TASK12','LEASE_RELEASE','CLEANUP')
    $steps=@($stages|ForEach-Object{[pscustomobject]@{Stage=$_;Status='SKIP';Code=$null}})
    $started=New-Object 'Collections.Generic.List[string]';$stopped=New-Object 'Collections.Generic.List[string]'
    $retained=$false;$failed=$null;$previewStable=$false;$business=$null;$previewBeforeWrites=0;$previewAfterWrites=0
    $previousSha256='0'*64;$previousSequence=0
    try { Assert-P6CompositeBusinessAdapterContract $Adapter | Out-Null } catch {
        return [pscustomobject]@{Status='FAIL';Code='C2B_ADAPTER_CONTRACT_INVALID';Phase='CONTRACT';Retained=$true;StartedStages=@();StoppedStages=@();Stages=$stages;Steps=$steps;PreviewStable=$false;SafeOutput='FAIL Phase=CONTRACT Code=C2B_ADAPTER_CONTRACT_INVALID Retained=True'}
    }
    try {
        foreach($step in $steps){
            try {
                $deadline=$Adapter.Deadline()
                if(($deadline -isnot [int] -and $deadline -isnot [long]) -or $deadline -le 0){throw 'C2B_DEADLINE_EXCEEDED'}
                $receipt=$Adapter.Receipt($step.Stage)
                if($null -eq $receipt -or [string]$receipt.Sha256 -notmatch '^[a-f0-9]{64}$' -or [string]$receipt.PreviousSha256 -notmatch '^[a-f0-9]{64}$' -or $receipt.Sequence -ne ($previousSequence+1) -or $receipt.PreviousSha256 -cne $previousSha256){throw 'C2B_RECEIPT_CHAIN_INVALID'}
                $previousSha256=[string]$receipt.Sha256;$previousSequence=[int]$receipt.Sequence
                $response=$Adapter.Invoke($step.Stage,@{Mode=$Mode})
                $step.Status='PASS'
                if($step.Stage -in @('BUILD','PG','EXTERNAL59','HELPER','LIVE_MIGRATION','API_AUTH','GATEWAY','WIRE','TASK12','LEASE_RELEASE')){$started.Add($step.Stage)}
                if($step.Stage -eq 'PREVIEW'){
                    if($Adapter.PSObject.Methods.Name -contains 'Summary'){
                        $business=$Adapter.Summary()
                        if($business.Vehicles -ne 3 -or $business.Terminals -ne 4 -or $business.Systems -ne 3 -or $business.Memberships -ne 4){throw 'C2B_BUSINESS_CARDINALITY_INVALID'}
                        if($business.SystemA -ne 2 -or $business.SystemB -ne 1 -or $business.SystemC -ne 1){throw 'C2B_SYSTEM_DISTRIBUTION_INVALID'}
                    }
                    $before=$Adapter.Invoke('PREVIEW_BEFORE',@{Mode=$Mode})
                    $after=$Adapter.Invoke('PREVIEW_AFTER',@{Mode=$Mode})
                    $previewBeforeWrites=$before.WriteCount;$previewAfterWrites=$after.WriteCount
                    $previewStable=($before.Rows -eq $after.Rows -and $before.Hash -ceq $after.Hash -and $before.Version -eq $after.Version -and $before.WriteCount -eq $after.WriteCount)
                    if(-not $previewStable){throw 'C2B_PREVIEW_MUTATED'}
                }
            } catch {
                $failed=$_.Exception.Message
                if($null -ne $_.Exception.InnerException -and $_.Exception.InnerException.Message){$failed=$_.Exception.InnerException.Message}
                $step.Status='FAIL';$step.Code=$failed;break
            }
        }
    } finally {
        $stopOrder=@($started);[array]::Reverse($stopOrder)
        foreach($resource in $stopOrder){
            try { $Adapter.Stop($resource);$stopped.Add($resource) } catch { $retained=$true;$failed='C2B_STOP_UNPROVEN' }
        }
    }
    if($null -ne $failed){
        $failedStepRecord=$steps|Where-Object Status -eq 'FAIL'|Select-Object -First 1
        $failedStep=if($null -eq $failedStepRecord){'CLEANUP'}else{$failedStepRecord.Stage}
        $index=[array]::IndexOf($stages,$failedStep)
        $failedCode=if($failed -ceq 'C2B_STOP_UNPROVEN'){'C2B_STOP_UNPROVEN'}elseif($failed -match 'DEADLINE_EXCEEDED'){'C2B_DEADLINE_EXCEEDED'}elseif($failed -match 'RECEIPT_CHAIN|bad-chain'){'C2B_RECEIPT_CHAIN_INVALID'}elseif($failed -match '^C2B_[A-Z0-9_]+$'){$failed}else{'C2B_STAGE_FAILED'}
        if($failedCode -ceq 'C2B_STOP_UNPROVEN'){$failedStep='CLEANUP'}
        $safe=('FAIL Phase={0} Code={1} Retained={2}' -f $failedStep,$failedCode,$true)
        return [pscustomobject]@{Status='FAIL';Code=$failedCode;Phase=$failedStep;Retained=$true;StartedStages=@($started);StoppedStages=@($stopped);Stages=$stages;Steps=$steps;PreviewStable=$previewStable;Business=$business;PreviewBeforeWrites=$previewBeforeWrites;PreviewAfterWrites=$previewAfterWrites;SafeOutput=$safe}
    }
    return [pscustomobject]@{Status='PASS';Code='C2B_PIPELINE_COMPLETE';Phase='COMPLETE';Retained=$retained;StartedStages=@($started);StoppedStages=@($stopped);Stages=$stages;Steps=$steps;PreviewStable=$previewStable;Business=$business;PreviewBeforeWrites=$previewBeforeWrites;PreviewAfterWrites=$previewAfterWrites;SafeOutput=('PASS Phase=COMPLETE Retained={0}' -f $retained)}
}
