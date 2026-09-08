# C2b business orchestration seam. External systems are injected through Adapter; real Execute remains gated elsewhere.
Set-StrictMode -Version Latest
$script:P6RuntimePlanRegistry=@{}

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
function New-P6ApiGatewayWireTask12Adapter {
    param([Parameter(Mandatory=$true)]$Context)
    if($null -eq $Context -or $null -eq $Context.Root -or $null -eq $Context.Marker -or $null -eq $Context.Receipt -or $null -eq $Context.RunParent){throw 'C2B_BOUNDARY_INVALID'}
    $a=[pscustomobject]@{Boundary='RUNNER_OWNED_LOOPBACK_ONLY';Root=$Context.Root;RunParent=$Context.RunParent;Marker=$Context.Marker;Receipt=$Context.Receipt;Ports=@($Context.Ports);Environment=@{SERVER_ADDRESS='127.0.0.1';GATEWAY_MANAGEMENT_ADDRESS='127.0.0.1';GATEWAY_TCP_BIND_ADDRESS='127.0.0.1'};AttachmentFields=0;Failure='';State=[ordered]@{InDryRun=$false;Events=New-Object 'Collections.Generic.List[string]';AuthenticatedUser=$false;PasswordVersion=1;Rotated=$false;Relogin=$false;Vehicles=0;Terminals=0;Capabilities=4;Bindings=4;WriteCount=0;PreviewBefore=$null;PreviewAfter=$null;Systems=0;ActiveSystems=0;Memberships=4;Distribution=@{A=2;B=1;C=1};Leases=0;Connections=New-Object 'Collections.Generic.HashSet[string]';UpstreamEvidence=$false;AcceptanceStatus=$null;Receipts=New-Object 'Collections.Generic.List[object]';Deadline=1800000}}
    $a|Add-Member ScriptMethod DryRun {
        $this.State.InDryRun=$true;foreach($s in @('API_HEALTH','LOGIN','ROTATE','RELOGIN','DATA_PREP','PREVIEW','APPLY','GATEWAY','WIRE','TASK12','LEASE_RELEASE')){$this.Invoke($s)|Out-Null};$this.State.InDryRun=$false
        [pscustomobject]@{Stages=@('API_HEALTH','LOGIN','ROTATE','RELOGIN','DATA_PREP','PREVIEW','APPLY','GATEWAY','WIRE','TASK12','LEASE_RELEASE');Vehicles=$this.State.Vehicles;Terminals=$this.State.Terminals;Systems=$this.State.Systems;SystemA=2;SystemB=1;SystemC=1;PreviewStable=$true;Leases=$this.State.Leases;ConnectionIds=$this.State.Connections.Count;Task12='ACCEPTED4';AttachmentFields=$this.AttachmentFields;SafeOutput='API_GATEWAY_WIRE_TASK12_DRYRUN_PASS'}
    } -Force
    $a|Add-Member ScriptMethod Invoke { param([string]$Stage)
        if(-not $this.State.InDryRun){throw 'C2B_REAL_ADAPTER_NOT_READY'}
        if($this.Failure -ceq 'TASK12_STATUS' -and $Stage -eq 'TASK12'){$this.State.AcceptanceStatus='REJECTED';throw 'C2B_TASK12_NOT_ACCEPTED'}
        if($this.Failure -ceq 'LOGIN' -and $Stage -eq 'LOGIN'){throw 'C2B_AUTH_LOGIN_FAILED'}
        if($this.Failure -ceq 'ROTATE' -and $Stage -eq 'ROTATE'){throw 'C2B_AUTH_ROTATION_FAILED'}
        if($this.Failure -ceq 'PREVIEW_MUTATION' -and $Stage -eq 'PREVIEW'){throw 'C2B_PREVIEW_MUTATED'}
        if($this.Failure -ceq 'MISSING_UPSTREAM' -and $Stage -eq 'TASK12'){throw 'C2B_UPSTREAM_EVIDENCE_MISSING'}
        if($this.Failure -ceq 'ATTACHMENT' -and $Stage -eq 'WIRE'){throw 'C2B_ATTACHMENT_FIELD_FORBIDDEN'}
        if($this.Failure -ceq 'DUPLICATE_CONNECTION' -and $Stage -eq 'WIRE'){throw 'C2B_CONNECTION_DUPLICATE'}
        $this.State.Events.Add($Stage)
        switch($Stage){'LOGIN'{if($this.State.AuthenticatedUser){throw 'C2B_AUTH_STATE_INVALID'};$this.State.AuthenticatedUser=$true};'ROTATE'{if(-not $this.State.AuthenticatedUser){throw 'C2B_AUTH_ROTATION_STATE_INVALID'};$this.State.PasswordVersion++;$this.State.Rotated=$true};'RELOGIN'{if(-not $this.State.Rotated){throw 'C2B_AUTH_RELOGIN_STATE_INVALID'};$this.State.Relogin=$true};'DATA_PREP'{$this.State.Vehicles=3;$this.State.Terminals=4;$this.State.Systems=3};'PREVIEW_BEFORE'{$this.State.PreviewBefore=[pscustomobject]@{Hash='h';Rows=4;Version=1;WriteCount=$this.State.WriteCount};return $this.State.PreviewBefore};'PREVIEW_AFTER'{$this.State.PreviewAfter=[pscustomobject]@{Hash='h';Rows=4;Version=1;WriteCount=$this.State.WriteCount};return $this.State.PreviewAfter};'PREVIEW'{};'APPLY'{$this.State.ActiveSystems=3;$this.State.WriteCount++};'WIRE'{$this.State.Leases=4;1..4|%{$this.State.Connections.Add(('c'+$_))|Out-Null};$this.State.UpstreamEvidence=$true};'TASK12'{if(-not $this.State.UpstreamEvidence -or $this.State.Leases -ne 4 -or $this.State.Connections.Count -ne 4 -or $this.AttachmentFields -ne 0){throw 'C2B_UPSTREAM_EVIDENCE_MISSING'};$this.State.AcceptanceStatus='ACCEPTED4'}}
        return $true
    } -Force
    $a|Add-Member ScriptMethod Summary { return [pscustomobject]@{AuthenticatedUser=$this.State.AuthenticatedUser;PasswordVersion=$this.State.PasswordVersion;Rotated=$this.State.Rotated;Relogin=$this.State.Relogin;Vehicles=$this.State.Vehicles;Terminals=$this.State.Terminals;Capabilities=$this.State.Capabilities;Bindings=$this.State.Bindings;Systems=$this.State.Systems;SystemA=$this.State.Distribution.A;SystemB=$this.State.Distribution.B;SystemC=$this.State.Distribution.C;Distribution=$this.State.Distribution;Memberships=4;Leases=$this.State.Leases;ConnectionIds=$this.State.Connections.Count;UpstreamEvidence=$this.State.UpstreamEvidence;AcceptanceStatus=$this.State.AcceptanceStatus;AttachmentFields=$this.AttachmentFields;PreviewBefore=$this.State.PreviewBefore;PreviewAfter=$this.State.PreviewAfter} } -Force
    $a|Add-Member ScriptMethod Receipt { param([string]$Stage) $seq=$this.State.Receipts.Count+1;$prev=if($seq -eq 1){'0'*64}else{$this.State.Receipts[$seq-2].Sha256};$bytes=[Text.Encoding]::UTF8.GetBytes(('{0}|{1}' -f $Stage,$seq));$hash=(([Security.Cryptography.SHA256]::Create().ComputeHash($bytes)|ForEach-Object ToString x2)-join '');$rec=[pscustomobject]@{Stage=$Stage;Sequence=$seq;PreviousSha256=$prev;Sha256=$hash};$this.State.Receipts.Add($rec);return $rec } -Force
    $a|Add-Member ScriptMethod Deadline { return $this.State.Deadline } -Force
    $a|Add-Member ScriptMethod Stop { param([string]$Stage) return [pscustomobject]@{Status='STOPPED'} } -Force
    return $a
}
function Get-P6RuntimeCommandPlan {
    param([Parameter(Mandatory=$true)]$Context)
    if($null -eq $Context.Root -or $null -eq $Context.Marker -or $null -eq $Context.Receipt -or $null -eq $Context.RunParent -or $null -eq $Context.Ports -or $null -eq $Context.BoundaryDigest -or @($Context.Ports).Count -ne 4){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    $inputDigest=(([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$Context.Root+'|'+(@($Context.Ports)-join ',')+'|'+(($Context.Marker|ConvertTo-Json -Compress))+'|'+(($Context.Receipt|ConvertTo-Json -Compress))+'|'+[string]$Context.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    if([string]$Context.BoundaryDigest -cne $inputDigest){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    try{Assert-P6NativePathLength ([string]$Context.Root);Assert-P6ChildPath ([IO.Path]::GetDirectoryName([string]$Context.Root)) ([string]$Context.Root) -MustExist|Out-Null;Assert-P6LoopbackPorts $Context.Ports @();Read-P6OwnerMarker $Context.Receipt $Context.RunParent|Out-Null;Assert-P6PrivateAcl ([string]$Context.Root)}catch{throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    $java='C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
    $plan=[pscustomobject]@{
        Api=[pscustomobject]@{FileName=$java;Arguments=@('-jar',(Join-Path $Context.Root 'api.jar'));Environment=@{SERVER_ADDRESS='127.0.0.1';SERVER_PORT=[string]$Context.Ports[1];P6_RUNTIME_MODE='SYNTHETIC_ONLY'}}
        Gateway=[pscustomobject]@{FileName=$java;Arguments=@('-jar',(Join-Path $Context.Root 'gateway.jar'));Environment=@{JT_GATEWAY_MANAGEMENT_ADDRESS='127.0.0.1';JT_GATEWAY_TCP_BIND_ADDRESS='127.0.0.1';P6_RUNTIME_MODE='SYNTHETIC_ONLY'}}
        Wire=[pscustomobject]@{FileName=$java;Arguments=@('-cp',(Join-Path $Context.Root 'wire-harness.jar'),'P6CompositeWireHarness');Environment=@{P6_WIRE_MODE='SYNTHETIC_ONLY';P6_WIRE_TERMINALS='4'};TerminalCount=4;AttachmentFields=0}
        Task12=[pscustomobject]@{Mode='VerifyAcceptance';Source='UPSTREAM_EVIDENCE_ONLY'};ExecuteCount=0
    }
    $plan|Add-Member NoteProperty BoundaryDigest $inputDigest
    $plan|Add-Member NoteProperty PlanKind 'RUNTIME_COMMAND_PLAN'
    $plan|Add-Member NoteProperty Root ([string]$Context.Root)
    $plan|Add-Member NoteProperty Ports @($Context.Ports)
    $plan|Add-Member NoteProperty Marker $Context.Marker
    $plan|Add-Member NoteProperty Receipt $Context.Receipt
    $plan|Add-Member NoteProperty RunParent ([string]$Context.RunParent)
    $script:P6RuntimePlanRegistry[[string]$Context.RunId]=[pscustomobject]@{Plan=$plan;Digest=$inputDigest}
    return $plan
}
function Invoke-P6ControlledRuntimeAction {
    param([Parameter(Mandatory=$true)]$Plan,[ValidateSet('BUILD','PG','API','GW','WIRE','TASK12')][string]$Role,[switch]$SyntheticOnly)
    if(-not $SyntheticOnly){throw 'C2B_REAL_ADAPTER_NOT_READY'}
    if($null -eq $Plan -or $Plan.ExecuteCount -ne 0 -or $Plan.PlanKind -cne 'RUNTIME_COMMAND_PLAN' -or $Plan.BoundaryDigest -notmatch '^[a-f0-9]{64}$' -or $null -eq $Plan.Root -or $null -eq $Plan.Ports -or $null -eq $Plan.Marker -or $null -eq $Plan.Receipt -or $null -eq $Plan.RunParent -or $Plan.Ports.Count -ne 4){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    $planDigest=(([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes(([string]$Plan.Root+'|'+(@($Plan.Ports)-join ',')+'|'+(($Plan.Marker|ConvertTo-Json -Compress))+'|'+(($Plan.Receipt|ConvertTo-Json -Compress))+'|'+[string]$Plan.RunParent+'|SYNTHETIC_ONLY')))|ForEach-Object ToString x2)-join '')
    if($planDigest -cne [string]$Plan.BoundaryDigest){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    $registered=$script:P6RuntimePlanRegistry[[string]$Plan.Marker.RunId]
    if($null -eq $registered -or -not [object]::ReferenceEquals($registered.Plan,$Plan) -or $registered.Digest -cne $planDigest){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    try{Assert-P6NativePathLength ([string]$Plan.Root);Assert-P6ChildPath ([IO.Path]::GetDirectoryName([string]$Plan.Root)) ([string]$Plan.Root) -MustExist|Out-Null;Assert-P6LoopbackPorts $Plan.Ports @();Read-P6OwnerMarker $Plan.Receipt $Plan.RunParent|Out-Null;Assert-P6PrivateAcl ([string]$Plan.Root)}catch{throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    [pscustomobject]@{Status='PLANNED';Role=$Role;Started=$false;Deadline=1800000;ReceiptPredecessor='REQUIRED';StreamDrain='REQUIRED';ProcessFactory='REQUIRED';SafeOutput='CONTROLLED_RUNTIME_PLAN_ONLY'}
}
function Get-P6SyntheticProcessSpec {
    param([Parameter(Mandatory=$true)]$Plan,[ValidateSet('BUILD','PG','API','GW','WIRE','TASK12')][string]$Role)
    if($null -eq $Plan -or $Plan.PlanKind -cne 'RUNTIME_COMMAND_PLAN' -or $Plan.BoundaryDigest -notmatch '^[a-f0-9]{64}$'){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    $registered=$script:P6RuntimePlanRegistry[[string]$Plan.Marker.RunId];if($null -eq $registered -or -not [object]::ReferenceEquals($registered.Plan,$Plan) -or $registered.Digest -cne [string]$Plan.BoundaryDigest){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    try{Assert-P6NativePathLength ([string]$Plan.Root);Assert-P6ChildPath ([IO.Path]::GetDirectoryName([string]$Plan.Root)) ([string]$Plan.Root) -MustExist|Out-Null;Assert-P6LoopbackPorts $Plan.Ports @();Read-P6OwnerMarker $Plan.Receipt $Plan.RunParent|Out-Null;Assert-P6PrivateAcl ([string]$Plan.Root)}catch{throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}
    $exe='C:\Program Files\Java\jdk-21.0.10\bin\java.exe';if($Role -eq 'PG'){$exe='C:\Program Files\PostgreSQL\17\bin\postgres.exe'}
    [pscustomobject]@{Role=$Role;FileName=$exe;Arguments=@('-Dp6.synthetic.only=true');Environment=@{P6_SYNTHETIC_ONLY='true';P6_STREAM_DRAIN='required';P6_DEADLINE_MS='1800000'};WorkingDirectory=$Plan.Root;StartAllowed=$false;ReceiptRequired=$true;RetainedOnUnknownStop=$true}
}
function Get-P6RealCommandSpec {
    param([Parameter(Mandatory=$true)]$Plan,[ValidateSet('BUILD','PG','FLYWAY','API','GW','WIRE','TASK12')][string]$Role)
    foreach($bad in @('Endpoint','Path','Secret','Pid','Skip','Executable','Arguments','WorkingDirectory','Environment')){if($Plan.PSObject.Properties.Name -contains $bad){throw 'C2B_RUNTIME_PLAN_BOUNDARY_INVALID'}}
    if($Role -in @('FLYWAY','TASK12')){ $base='C:\Program Files\Java\jdk-21.0.10\bin\java.exe' } elseif($Role -eq 'PG'){$base='C:\Program Files\PostgreSQL\17\bin\pg_ctl.exe'} elseif($Role -eq 'BUILD'){$base='C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3\bin\mvn.cmd'} else {$base='C:\Program Files\Java\jdk-21.0.10\bin\java.exe'}
    $s=Get-P6SyntheticProcessSpec -Plan $Plan -Role $(if($Role -in @('FLYWAY','TASK12')){'WIRE'}else{$Role})
    $args=@();switch($Role){'BUILD'{$args=@('-q','-DskipTests','package')};'PG'{$args=@('start','-D',(Join-Path $Plan.Root 'pgdata'),'-o','-h 127.0.0.1')};'FLYWAY'{$args=@('-cp',(Join-Path $Plan.Root 'flyway-helper.jar'),'P6CompositeFlywayTool')};'API'{$args=@('-jar',(Join-Path $Plan.Root 'api.jar'))};'GW'{$args=@('-jar',(Join-Path $Plan.Root 'gateway.jar'))};'WIRE'{$args=@('-cp',(Join-Path $Plan.Root 'wire-harness.jar'),'P6CompositeWireHarness')};'TASK12'{$args=@('-cp',(Join-Path $Plan.Root 'task12-helper.jar'),'InvokeTask12Acceptance')}}
    $s|Add-Member NoteProperty FileName $base -Force;$s|Add-Member NoteProperty Arguments $args -Force;$s|Add-Member NoteProperty WorkingDirectory $Plan.Root -Force;$s|Add-Member NoteProperty Environment @{P6_SYNTHETIC_ONLY='true';P6_BIND_ADDRESS='127.0.0.1';P6_STREAM_DRAIN='required'} -Force;$s|Add-Member NoteProperty RealActionEnabled $false -Force;$s|Add-Member NoteProperty HealthProbe 'REQUIRED' -Force;$s|Add-Member NoteProperty StopContract 'HELD_PROCESS_ONLY' -Force;$s|Add-Member NoteProperty ReceiptPredecessor 'REQUIRED' -Force;$s|Add-Member NoteProperty Deadline 1800000 -Force
    return $s
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
            try { $null=$Adapter.Stop($resource);$stopped.Add($resource) } catch { $retained=$true;$failed='C2B_STOP_UNPROVEN' }
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
    if($Adapter.PSObject.Methods.Name -contains 'Summary'){$business=$Adapter.Summary()}
    return [pscustomobject]@{Status='PASS';Code='C2B_PIPELINE_COMPLETE';Phase='COMPLETE';Retained=$retained;StartedStages=@($started);StoppedStages=@($stopped);Stages=$stages;Steps=$steps;PreviewStable=$previewStable;Business=$business;PreviewBeforeWrites=$previewBeforeWrites;PreviewAfterWrites=$previewAfterWrites;SafeOutput=('PASS Phase=COMPLETE Retained={0}' -f $retained)}
}
