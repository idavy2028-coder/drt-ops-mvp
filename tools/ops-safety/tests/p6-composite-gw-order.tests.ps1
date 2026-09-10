$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
. (Join-Path $ops 'p6-composite-real-runtime.ps1')
# Execute the actual orchestration statements; replace only external launches and dependency waits.
$source=(Get-Command Invoke-P6RealIsolationExecution).Definition
$begin=$source.IndexOf('$phase=''GW'';')
$end=$source.IndexOf('$phase=''TASK12'';')
if($begin -lt 0 -or $end -le $begin){throw 'ORCHESTRATION_BOUNDARY_MISSING'}
$flow=[scriptblock]::Create($source.Substring($begin,$end-$begin)+'; $reachedTask12=$true')
$root=Join-Path $env:TEMP ('p6-gw-order-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory((Join-Path $root 'secrets'))
[IO.File]::WriteAllText((Join-Path $root 'secrets/api-token.txt'),'SYNTHETIC')
[IO.File]::WriteAllLines((Join-Path $root 'wire.properties'),@('VehicleAId=A','VehicleBId=B','VehicleCId=C'))
$wireHash=Get-P6Sha256 ([IO.File]::ReadAllBytes((Join-Path $root 'wire.properties')))
$context=[pscustomobject]@{Receipt=@{Ports=@(1,2,3,4);RunId='synthetic'}}
foreach($scenario in @('pass','liveness_fail','wire_fail','readiness_fail')){
    $trace=New-Object 'Collections.Generic.List[string]';$reachedTask12=$false;$wireComplete=$false
    function Assert-P6RuntimeGuard($Context){}
    function Start-P6HeldResource($Context,$Role){$trace.Add('START_'+$Role);return @{Status='STARTED'}}
    function Wait-P6RuntimeReady($Context,$Role,$Probe='Readiness'){
        $trace.Add($Role+'_'+$Probe)
        if($Probe -ceq 'Readiness' -and -not $wireComplete){throw 'EARLY_READINESS'}
        if(($Probe -ceq 'Liveness' -and $scenario -eq 'liveness_fail') -or ($Probe -ceq 'Readiness' -and $scenario -eq 'readiness_fail')){throw 'EXPECTED_GATE_FAILURE'}
    }
    function Get-P6RuntimeHelperEnvironment($Context){return @{}}
    function Invoke-P6RuntimeJava($Context,$Class,$Environment,$Phase,$Timeout){
        if($Class -cne 'P6CompositeWireHarness' -or $Phase -cne 'WIRE' -or $Timeout -ne 120000){throw 'WIRE_CONTRACT_CHANGED'}
        $trace.Add('WIRE');if($scenario -eq 'wire_fail'){throw 'EXPECTED_GATE_FAILURE'}
        Set-Variable -Name wireComplete -Value $true -Scope 1
    }
    try{. $flow}catch{if($_.Exception.Message -cnotin @('EXPECTED_GATE_FAILURE','EARLY_READINESS')){throw}}
    $expected=switch($scenario){'pass'{'START_GW,GW_Liveness,WIRE,GW_Readiness'};'liveness_fail'{'START_GW,GW_Liveness'};'wire_fail'{'START_GW,GW_Liveness,WIRE'};'readiness_fail'{'START_GW,GW_Liveness,WIRE,GW_Readiness'}}
    if(($trace -join ',') -cne $expected -or $reachedTask12 -ne ($scenario -eq 'pass')){throw ('GW_ORDER_WRONG_'+$scenario+'_'+($trace -join ','))}
}
'GW_ORDER_TESTS=PASS COUNT=4 REAL_ORCHESTRATION=true'
