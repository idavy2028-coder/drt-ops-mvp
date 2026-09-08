# C2b business orchestration seam. External systems are injected through Adapter; real Execute remains gated elsewhere.
Set-StrictMode -Version Latest

function Get-P6CompositeBusinessPlan {
    [pscustomobject]@{Stages=@('SNAPSHOT','BUILD','PG','EXTERNAL59','HELPER','LIVE_MIGRATION','API_AUTH','DATA_PREP','PREVIEW','APPLY','GATEWAY','WIRE','TASK12','LEASE_RELEASE','CLEANUP');ExecuteEnabled=$false;Boundary='STATEFUL_FAKE_ONLY'}
}
function Assert-P6CompositeBusinessAdapterContract {
    param($Adapter)
    if($null -eq $Adapter -or -not ($Adapter.PSObject.Methods.Name -contains 'Invoke') -or -not ($Adapter.PSObject.Methods.Name -contains 'Stop') -or -not ($Adapter.PSObject.Methods.Name -contains 'Summary') -or -not ($Adapter.PSObject.Methods.Name -contains 'Receipt') -or -not ($Adapter.PSObject.Methods.Name -contains 'Deadline')){throw 'C2B_ADAPTER_CONTRACT_INVALID'}
    foreach($property in @('Endpoint','Pid','Secret','Path','Skip')){if($Adapter.PSObject.Properties.Name -contains $property){throw 'C2B_CALLER_CONTROL_REJECTED'}}
    if($Adapter.PSObject.Properties.Name -contains 'BoundaryDigest'){
        $current=(([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$Adapter.Root+'|'+(@($Adapter.Ports)-join ',')+'|'+($Adapter.Environment|ConvertTo-Json -Compress))))|ForEach-Object ToString x2)-join '')
        if($current -cne [string]$Adapter.BoundaryDigest){throw 'C2B_CALLER_CONTROL_REJECTED'}
    }
    $true
}
function New-P6CompositeRealAdapter {
    param([Parameter(Mandatory=$true)]$Context)
    if($null -eq $Context -or [string]$Context.Root -notmatch '^[A-Za-z]:\\' -or @($Context.Ports).Count -ne 4){throw 'C2B_REAL_ADAPTER_CONTEXT_INVALID'}
    $root=[IO.Path]::GetFullPath([string]$Context.Root)
    $env=[ordered]@{
        SERVER_ADDRESS='127.0.0.1';SERVER_PORT=[string]$Context.Ports[1]
        DRT_OPS_DATASOURCE_URL=('jdbc:postgresql://127.0.0.1:{0}/composite_live' -f $Context.Ports[0])
        DRT_OPS_DATASOURCE_PASSWORD=[string]$Context.Secrets.Db
        P6_REHEARSAL_RUN_ID=[string]$Context.RunId
    }
    $adapter=[pscustomobject]@{Boundary='RUNNER_OWNED_LOOPBACK_ONLY';RunId=[string]$Context.RunId;Root=$root;Ports=@($Context.Ports);Environment=$env;FixedTools=[ordered]@{Jdk='C:\Program Files\Java\jdk-21.0.10\bin\java.exe';Maven='C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3\bin\mvn.cmd';Postgres='C:\Program Files\PostgreSQL\17\bin\postgres.exe';Flyway='RUNNER_COMPILED_HELPER';Wire='RUNNER_COMPILED_HARNESS'} }
    $adapter|Add-Member NoteProperty BoundaryDigest (([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(($root+'|'+(@($Context.Ports)-join ',')+'|'+($env|ConvertTo-Json -Compress))))|ForEach-Object ToString x2)-join '')
    $adapter | Add-Member ScriptMethod Invoke { param([string]$Stage,[hashtable]$Input) throw 'C2B_REAL_ADAPTER_NOT_READY' } -Force
    $adapter | Add-Member ScriptMethod Stop { param([string]$Stage) throw 'C2B_REAL_ADAPTER_NOT_READY' } -Force
    $adapter | Add-Member ScriptMethod Summary { throw 'C2B_REAL_ADAPTER_NOT_READY' } -Force
    $adapter | Add-Member ScriptMethod Receipt { param([string]$Stage) throw 'C2B_REAL_ADAPTER_NOT_READY' } -Force
    $adapter | Add-Member ScriptMethod Deadline { return 1800000 } -Force
    return $adapter
}
function New-P6BuildPgFlywayAdapter {
    param([Parameter(Mandatory=$true)]$Context)
    if($null -eq $Context -or $null -eq $Context.Root -or $null -eq $Context.Marker -or $null -eq $Context.Receipt -or $null -eq $Context.RunParent -or [string]$Context.Root -notmatch '^[A-Za-z]:\\' -or @($Context.Ports).Count -ne 4){throw 'C2B_BOUNDARY_INVALID'}
    $guards=[pscustomobject]@{}
    $guards|Add-Member ScriptMethod PathCheck { Assert-P6NativePathLength ([string]$this.Owner.Root); if(Test-Path -LiteralPath $this.Owner.Root){Assert-P6ChildPath ([IO.Path]::GetDirectoryName($this.Owner.Root)) $this.Owner.Root -MustExist|Out-Null} } -Force
    $guards|Add-Member ScriptMethod PortsCheck { Assert-P6LoopbackPorts $this.Owner.Ports @() } -Force
    $guards|Add-Member ScriptMethod OwnerCheck { Read-P6OwnerMarker $this.Owner.Receipt $this.Owner.RunParent|Out-Null;Assert-P6PrivateAcl $this.Owner.Root } -Force
    $guards|Add-Member NoteProperty Owner $null -Force
    $a=[pscustomobject]@{Boundary='RUNNER_OWNED_LOOPBACK_ONLY';Root=[IO.Path]::GetFullPath([string]$Context.Root);Ports=@($Context.Ports);OwnedRoot=[IO.Path]::GetFullPath([string]$Context.Root);OwnedPorts=@($Context.Ports);RunParent=[string]$Context.RunParent;Marker=$Context.Marker;Receipt=$Context.Receipt;Guards=$guards;LastGuard='INIT';RunId=[string]$Context.RunId;SecretsEnv=@{P6_REHEARSAL_DB_PASSWORD=[string]$Context.Secrets.Db};ExternalMigrationCount=59;DatabaseNames=@('composite_onboard','composite_live');SideEffectCount=0;DemoIdentities=@('33333333-3333-3333-3333-333333333331','33333333-3333-3333-3333-333333333332');PrepareUpdateCount=2;DemoRetained=$true}
    $guards.Owner=$a
    $a|Add-Member ScriptMethod DryRun {
        if([IO.Path]::GetFullPath([string]$this.Root) -cne [string]$this.OwnedRoot -or @($this.Ports).Count -ne 4 -or (@($this.Ports)|Sort-Object -Unique).Count -ne 4 -or (@($this.Ports)-join ',') -cne (@($this.OwnedPorts)-join ',')){throw 'C2B_BOUNDARY_INVALID'}
        try { $this.LastGuard='PATH';$this.Guards.PathCheck();$this.LastGuard='PORTS';$this.Guards.PortsCheck();$this.LastGuard='OWNER';$this.Guards.OwnerCheck();$this.LastGuard='PASS' } catch { throw 'C2B_BOUNDARY_INVALID' }
        if(@($this.DemoIdentities) -join ',' -cne '33333333-3333-3333-3333-333333333331,33333333-3333-3333-3333-333333333332' -or $this.PrepareUpdateCount -ne 2 -or -not $this.DemoRetained){throw 'C2B_V20_FIXTURE_INVALID'}
        [pscustomobject]@{Stages=@('BUILD','PG_CREATE','EXTERNAL59','LIVE_V19','PREPARE_V20','LIVE_V20','LIVE_V21','VALIDATE');BuildBeforePg=$true;DatabaseCount=2;BindAddress='127.0.0.1';ExternalMigrationCount=59;LiveMigration='V19,PREPARE_V20,V20,V21,VALIDATE';PrepareUpdateCount=$this.PrepareUpdateCount;DemoRetained=$this.DemoRetained;SideEffectCount=0;Stdout='BUILD_PG_DRYRUN_PASS'}
    } -Force
    $a|Add-Member ScriptMethod Invoke { param([string]$Stage) throw 'C2B_REAL_ADAPTER_NOT_READY' } -Force
    $a|Add-Member ScriptMethod Stop { param([string]$Stage) throw 'C2B_REAL_ADAPTER_NOT_READY' } -Force
    $a|Add-Member ScriptMethod Deadline { return 1800000 } -Force
    return $a
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
