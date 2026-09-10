# C2a resource safety only. CLI Execute remains permanently closed until C2b review.
Set-StrictMode -Version Latest

function New-P6ResourceLifetime {
    param([scriptblock]$Clock=$null)
    if($null -eq $Clock){$timer=[Diagnostics.Stopwatch]::StartNew();$Clock={$timer.ElapsedMilliseconds}.GetNewClosure()}
    $now=&$Clock
    if(($now -isnot [long] -and $now -isnot [int]) -or $now -lt 0){throw 'REHEARSAL_CLOCK_INVALID'}
    return [pscustomobject]@{Clock=$Clock;Start=[long]$now;Last=[long]$now}
}
function Assert-P6ResourceLifetime {
    param($Lifetime)
    try{
        if((Get-P6Field $Lifetime 'Clock') -isnot [scriptblock]){throw 'invalid'}
        $now=&$Lifetime.Clock
        if(($now -isnot [long] -and $now -isnot [int]) -or $now -lt $Lifetime.Last -or $Lifetime.Start -lt 0){throw 'invalid'}
        $Lifetime.Last=[long]$now
    }catch{throw 'REHEARSAL_CLOCK_INVALID'}
    if(($now-$Lifetime.Start) -ge 1800000){throw 'REHEARSAL_TOTAL_DEADLINE'}
}
function Write-P6ChainedResourceReceipt {
    param($Context,[string]$Role,$Recorded)
    try{
        $marker=Read-P6OwnerMarker $Context.Receipt $Context.RunParent
        $root=Assert-P6Receipt $Context.Receipt $Context.RunParent $marker
        $target=Assert-P6ChildPath $root (Join-Path $root 'receipt.json') -MustExist
        $bytes=[IO.File]::ReadAllBytes($target)
        if($bytes.Length -gt 65536){throw 'invalid'}
        $digest=Get-P6Sha256 $bytes
        if((Get-P6Field $Context 'ReceiptHash') -cne $digest){throw 'invalid'}
        $old=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
        Assert-P6Receipt $old $Context.RunParent $marker|Out-Null
        # The caller may not alter any previous receipt field or reorder prior processes.
        if(($Context.Receipt|ConvertTo-Json -Depth 20 -Compress) -cne ($old|ConvertTo-Json -Depth 20 -Compress)){throw 'invalid'}
        $count=@($old.Processes).Count
        if($count -gt 2 -or $Role -cne @('PG','API','GW')[$count]){throw 'invalid'}
        if($count -gt 0 -and ((Get-P6Field $old 'Sequence') -ne $count -or (Get-P6Field $old 'PreviousSha256') -cnotmatch '^[a-f0-9]{64}$')){throw 'invalid'}
        if($Recorded.Role -cne $Role -or $Recorded.Kind -cne $(if($Role -ceq 'PG'){'Postgres'}else{'Java'})){throw 'invalid'}
        $exe=if($Role -ceq 'PG'){'C:\Program Files\PostgreSQL\17\bin\postgres.exe'}else{'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'}
        $argument=if($Role -ceq 'PG'){$old.PgData}else{'-Dp6.rehearsal.run='+$old.RunId}
        if(-not (Test-P6ExecutablePath $Recorded.ExecutablePath $exe) -or $Recorded.ArgumentMarker -cne $argument){throw 'invalid'}
        $started=[DateTimeOffset]::ParseExact($Recorded.StartTimeUtc,'o',[Globalization.CultureInfo]::InvariantCulture)
        if($started -lt [DateTimeOffset]::Parse($old.CreatedAt) -or $started -gt [DateTimeOffset]::UtcNow){throw 'invalid'}
        if($Recorded.Pid -isnot [int] -or $Recorded.Pid -le 0 -or @($old.Processes|Where-Object{$_.Pid -eq $Recorded.Pid}).Count -ne 0){throw 'invalid'}
        foreach($field in @('RunId','OwnerNonce','WorkingDirectory')){
            $expected=if($field -ceq 'WorkingDirectory'){$root}else{$old.$field}
            if($Recorded.$field -cne $expected){throw 'invalid'}
        }
        $next=$old|ConvertTo-Json -Depth 20|ConvertFrom-Json
        $next.Processes=@($old.Processes)+@($Recorded)
        $next|Add-Member Sequence ($count+1) -Force
        $next|Add-Member PreviousSha256 $digest -Force
        $newBytes=[Text.Encoding]::UTF8.GetBytes(($next|ConvertTo-Json -Depth 20 -Compress))
        $backup=Assert-P6ChildPath $root (Join-Path $root ('receipt.'+$count.ToString('000')+'.json'))
        Assert-P6NativePathLength $backup
        if(Test-Path -LiteralPath $backup){throw 'exists'}
        $stage=Join-Path $root ([guid]::NewGuid().ToString('N').Substring(0,8)+'.tmp')
        Write-P6AtomicNewFile $root $stage $newBytes
        # Revalidate immediately before the atomic replace. Existing backups are never overwritten.
        Read-P6OwnerMarker $Context.Receipt $Context.RunParent|Out-Null
        if((Get-P6Sha256 ([IO.File]::ReadAllBytes($target))) -cne $digest -or (Test-Path -LiteralPath $backup)){throw 'changed'}
        [IO.File]::Replace($stage,$target,$backup)
        $Context.Receipt=$next;$Context.ReceiptHash=Get-P6Sha256 $newBytes
    }catch{throw 'REHEARSAL_RECEIPT_CHAIN_UNPROVEN'}
}
function Assert-P6CurrentResourceReceipt {
    param($Context)
    try{
        $root=Assert-P6Receipt $Context.Receipt $Context.RunParent (Read-P6OwnerMarker $Context.Receipt $Context.RunParent)
        $bytes=[IO.File]::ReadAllBytes((Assert-P6ChildPath $root (Join-Path $root 'receipt.json') -MustExist))
        if($bytes.Length -gt 65536 -or (Get-P6Sha256 $bytes) -cne $Context.ReceiptHash){throw 'invalid'}
        $current=[Text.Encoding]::UTF8.GetString($bytes)|ConvertFrom-Json
        if(($current|ConvertTo-Json -Depth 20 -Compress) -cne ($Context.Receipt|ConvertTo-Json -Depth 20 -Compress)){throw 'invalid'}
        for($n=@($current.Processes).Count;$n -gt 0;$n--){
            if($n -gt 3 -or $current.Sequence -ne $n -or $current.PreviousSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'invalid'}
            $path=Assert-P6ChildPath $root (Join-Path $root ('receipt.'+($n-1).ToString('000')+'.json')) -MustExist
            $priorBytes=[IO.File]::ReadAllBytes($path)
            if($priorBytes.Length -gt 65536 -or (Get-P6Sha256 $priorBytes) -cne $current.PreviousSha256){throw 'invalid'}
            $prior=[Text.Encoding]::UTF8.GetString($priorBytes)|ConvertFrom-Json
            Assert-P6Receipt $prior $Context.RunParent $Context.Marker|Out-Null
            if(@($prior.Processes).Count -ne ($n-1)){throw 'invalid'}
            for($i=0;$i -lt ($n-1);$i++){
                if(($prior.Processes[$i]|ConvertTo-Json -Compress) -cne ($current.Processes[$i]|ConvertTo-Json -Compress)){throw 'invalid'}
            }
            $current=$prior
        }
    }catch{throw 'REHEARSAL_RECEIPT_CHAIN_UNPROVEN'}
}
function Invoke-P6ResourceStages {
    param($Context,[hashtable]$Boundary)
    # Internal resource choreography only. There is no default adapter and no business Execute path.
    $steps=@(foreach($role in @('PG','API','GW')){foreach($step in @('START','READY','TOOL')){[pscustomobject]@{Phase=($role+'_'+$step);Status='SKIP'}}})
    $phase='INIT';$code='REHEARSAL_RESOURCE_STAGE_FAILED';$ok=$false;$retained=$true;$canCleanup=$false
    try{
        foreach($key in @('Snapshot','Guard','Start','Ready','Tool','Stop','Removal','Delete','Report')){if($Boundary[$key] -isnot [scriptblock]){throw 'invalid'}}
        if((Get-P6Field $Context 'Tickets') -isnot [Collections.IDictionary] -or $Context.Tickets.Count -ne 0 -or $Context.ShortTickets.Count -ne 0){throw 'invalid'}
        foreach($step in $steps){
            $phase=$step.Phase;$step.Status='FAIL'
            $snapshot=&$Boundary.Snapshot $Context;if($snapshot -isnot [bool] -or -not $snapshot){throw 'snapshot'}
            Assert-P6ResourceLifetime $Context.Lifetime
            $guard=&$Boundary.Guard $Context ($phase+'_BEFORE');if($guard -isnot [bool] -or -not $guard){throw 'guard'}
            $canCleanup=$true
            $parts=$phase.Split('_');$role=$parts[0]
            switch($parts[1]){
                'START' {$value=&$Boundary.Start $role $Context;if((Get-P6Field $value 'Status') -cne 'STARTED' -or -not $Context.Tickets.Contains($role)){throw 'start'}}
                'READY' {$value=&$Boundary.Ready $role $Context;if($value -isnot [bool] -or -not $value){throw 'ready'}}
                'TOOL' {
                    $probe={
                        Assert-P6ResourceLifetime $Context.Lifetime
                        $healthy=&$Boundary.Guard $Context ($phase+'_WAIT')
                        if($healthy -isnot [bool] -or -not $healthy){throw 'REHEARSAL_HEALTH_UNPROVEN'}
                        return $true
                    }.GetNewClosure()
                    $value=&$Boundary.Tool $role $Context $probe
                    if((Get-P6Field $value 'Retained') -eq $true){$Context.ShortTickets.Add((Get-P6Field $value 'HeldTicket'))}
                    if((Get-P6Field $value 'Status') -cne 'EXITED' -or (Get-P6Field $value 'Retained') -isnot [bool] -or $value.Retained){throw 'tool'}
                }
            }
            Assert-P6ResourceLifetime $Context.Lifetime
            $guard=&$Boundary.Guard $Context ($phase+'_AFTER');if($guard -isnot [bool] -or -not $guard){throw 'guard'}
            $step.Status='PASS'
        }
        $ok=$true;$phase='COMPLETE';$code='REHEARSAL_RESOURCES_CONFIRMED'
    }catch{
        if($_.Exception.Message -cin @('REHEARSAL_TOTAL_DEADLINE','REHEARSAL_CLOCK_INVALID','REHEARSAL_RECEIPT_CHAIN_UNPROVEN','REHEARSAL_HEALTH_UNPROVEN')){$code=$_.Exception.Message}
    }finally{
        $retained= -not $canCleanup
        # Deadline exhaustion forbids more work, but not independently proven, bounded cleanup.
        foreach($role in @('GW','API','PG')){
            if($null -ne (Get-P6Field $Context 'Tickets') -and $Context.Tickets.Contains($role)){
                try{
                    $snapshot=&$Boundary.Snapshot $Context;if($snapshot -isnot [bool] -or -not $snapshot){throw 'snapshot'}
                    $stopped=&$Boundary.Stop $role $Context
                    if((Get-P6Field $stopped 'Status') -cne 'STOPPED'){throw 'stop'}
                }catch{$retained=$true;if($ok){$phase=$role+'_STOP';$code='REHEARSAL_STOP_UNPROVEN'};$ok=$false}
            }
        }
        if($null -ne (Get-P6Field $Context 'ShortTickets') -and $Context.ShortTickets.Count -gt 0){$retained=$true;$ok=$false}
        if(-not $retained){
            try{
                $proof=&$Boundary.Removal $Context;if($proof -isnot [bool] -or -not $proof){throw 'proof'}
                $snapshot=&$Boundary.Snapshot $Context;if($snapshot -isnot [bool] -or -not $snapshot){throw 'snapshot'}
                $deleted=&$Boundary.Delete $Context;if($deleted -isnot [bool] -or -not $deleted){throw 'delete'}
            }catch{$retained=$true;if($ok){$phase='CLEANUP';$code='REHEARSAL_REMOVAL_UNPROVEN'};$ok=$false}
        }
    }
    $result=[pscustomobject]@{Status=$(if($ok){'PASS'}else{'FAIL'});Phase=$phase;Code=$code;Retained=[bool]$retained;Steps=$steps}
    try{$reported=&$Boundary.Report $Context $result;if($reported -isnot [bool] -or -not $reported){throw 'report'}}catch{$result.Status='FAIL';$result.Phase='REPORT';$result.Code='REHEARSAL_REPORT_FAILED'}
    return $result
}
function Get-P6JavaResourceSpec {
    param($Context,[string]$Role)
    try{
        if($Role -cnotin @('API','GW')){throw 'invalid'}
        Assert-P6CurrentResourceReceipt $Context
        $root=$Context.Receipt.RunDirectory
        $jar=Assert-P6ChildPath $root (Join-Path $root $(if($Role -ceq 'API'){'api.jar'}else{'gateway.jar'})) -MustExist
        Assert-P6NativePathLength $jar
        if($Context.ArtifactHashes[$Role] -cnotmatch '^[a-f0-9]{64}$' -or (Get-P6Sha256 ([IO.File]::ReadAllBytes($jar))) -cne $Context.ArtifactHashes[$Role]){throw 'invalid'}
        foreach($key in @('DbPassword','JwtSecret','BootstrapPassword','GatewayCredential','H2Password')){
            if($Context.Secrets[$key] -isnot [string] -or $Context.Secrets[$key] -cnotmatch '^[A-Za-z0-9_-]{32,100}$'){throw 'invalid'}
        }
        $env=Get-P6ChildEnvironment $root;$ports=$Context.Receipt.Ports
        $env.JAVA_TOOL_OPTIONS='-Xmx512m';$env.LOGGING_LEVEL_ROOT='WARN'
        if($Role -ceq 'API'){
            $env.SERVER_ADDRESS='127.0.0.1';$env.SERVER_PORT=[string]$ports[1]
            $env.DRT_OPS_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:'+$ports[0]+'/composite_live'
            $env.DRT_OPS_DATASOURCE_USERNAME='composite';$env.DRT_OPS_DATASOURCE_PASSWORD=$Context.Secrets.DbPassword
            $env.DRT_AUTH_JWT_SECRET=$Context.Secrets.JwtSecret;$env.DRT_AUTH_BOOTSTRAP_ADMIN_USERNAME='rehearsal-admin'
            $env.DRT_AUTH_BOOTSTRAP_ADMIN_PASSWORD=$Context.Secrets.BootstrapPassword;$env.DRT_AUTH_REFRESH_COOKIE_SECURE='false';$env.DRT_AMAP_ENABLED='false'
            $env.JT_GATEWAY_SERVICE_CREDENTIALS_CURRENT_VERSION='1'
            $env.JT_GATEWAY_SERVICE_CREDENTIALS_CURRENT_HASH=Get-P6Sha256 ([Text.Encoding]::UTF8.GetBytes($Context.Secrets.GatewayCredential))
        }else{
            $env.JT_GATEWAY_INSTANCE='rehearsal-'+$Context.Receipt.RunId
            $env.JT_GATEWAY_MANAGEMENT_ADDRESS='127.0.0.1';$env.JT_GATEWAY_MANAGEMENT_PORT=[string]$ports[2]
            $env.JT_GATEWAY_TCP_ENABLED='true';$env.JT_GATEWAY_TCP_BIND_ADDRESS='127.0.0.1';$env.JT_GATEWAY_TCP_PORT=[string]$ports[3];$env.JT_GATEWAY_MAX_CONNECTIONS_PER_IP='4'
            $env.JT_GATEWAY_OPERATIONS_API_BASE_URL='http://127.0.0.1:'+$ports[1]
            $env.JT_GATEWAY_SERVICE_CREDENTIAL_VERSION='1';$env.JT_GATEWAY_SERVICE_CREDENTIAL_PLAINTEXT=$Context.Secrets.GatewayCredential
            $env.JT_GATEWAY_DATASOURCE_URL='jdbc:h2:file:'+(Join-Path $root 'gateway-outbox').Replace('\','/')+';MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE'
            $env.JT_GATEWAY_DATASOURCE_USERNAME='sa';$env.JT_GATEWAY_DATASOURCE_PASSWORD=$Context.Secrets.H2Password
        }
        return [pscustomobject]@{FileName='C:\Program Files\Java\jdk-21.0.10\bin\java.exe';Arguments=@(('-Dp6.rehearsal.run='+$Context.Receipt.RunId),'-jar',$jar);WorkingDirectory=$root;Environment=$env}
    }catch{throw 'REHEARSAL_RESOURCE_SPEC_INVALID'}
}
function New-P6ResourceLaunchRecord {
    param($Receipt,[string]$Role,$Spec,$Process)
    # LaunchExecutablePath records a request held by the original StartInfo, never an OS observation.
    # This projection must not consume MainModule: runtime identity is proved after launch, before use/stop.
    try{
        $expected=if($Role -ceq 'PG'){'C:\Program Files\PostgreSQL\17\bin\postgres.exe'}elseif($Role -cin @('API','GW')){'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'}else{throw 'invalid'}
        if($Process.HasExited -or -not (Test-P6ExecutablePath $Spec.FileName $expected) -or -not (Test-P6ExecutablePath $Process.StartInfo.FileName $Spec.FileName) -or $Process.StartInfo.WorkingDirectory -cne $Receipt.RunDirectory){throw 'invalid'}
        $ticks=$Process.StartTime.ToUniversalTime().Ticks
        $start=(New-Object DateTimeOffset(($ticks-($ticks%10)),[TimeSpan]::Zero)).ToString('o')
        return [pscustomobject]@{Pid=$Process.Id;StartTimeUtc=$start;LaunchExecutablePath=$Spec.FileName;ExecutablePath=$Spec.FileName;WorkingDirectory=$Process.StartInfo.WorkingDirectory;RunId=$Receipt.RunId;OwnerNonce=$Receipt.OwnerNonce;Kind=$(if($Role -ceq 'PG'){'Postgres'}else{'Java'});Role=$Role;ArgumentMarker=$(if($Role -ceq 'PG'){$Receipt.PgData}else{'-Dp6.rehearsal.run='+$Receipt.RunId})}
    }catch{throw 'REHEARSAL_RESOURCE_LAUNCH_RECORD_UNPROVEN'}
}
function Start-P6HeldResource {
    param($Context,[string]$Role,[scriptblock]$ProcessFactory=$null)
    $ticket=$null
    try{
        Assert-P6CurrentResourceReceipt $Context
        Assert-P6ResourceLifetime $Context.Lifetime
        if($Role -cnotin @('PG','API','GW') -or $Context.Tickets.Contains($Role) -or @($Context.Receipt.Processes).Count -ne [array]::IndexOf(@('PG','API','GW'),$Role)){throw 'invalid'}
        $spec=if($Role -ceq 'PG'){Get-P6PostgresStartSpec $Context.Receipt $Context.RunParent $Context.Marker}else{Get-P6JavaResourceSpec $Context $Role}
        $info=New-P6ProcessStartInfo $spec;Initialize-P6StreamDrain
        if($null -eq $ProcessFactory){$p=New-Object Diagnostics.Process;$p.StartInfo=$info}else{$p=&$ProcessFactory $info}
        if($p -isnot [Diagnostics.Process] -or -not $p.Start()){throw 'invalid'}
        $ticket=[pscustomobject]@{Process=$p;Recorded=$null;LaunchEvidence=$null;Drain=$null;State='STARTING';Role=$Role}
        $Context.Tickets[$Role]=$ticket
        $ticket.Drain=New-Object P6NativeStreamDrain($p.StandardOutput,$p.StandardError)
        $ticket.Recorded=New-P6ResourceLaunchRecord $Context.Receipt $Role $spec $p
        $ticket.LaunchEvidence=[pscustomobject]@{Process=$p;StartTicks=$p.StartTime.ToUniversalTime().Ticks;WorkingDirectory=$p.StartInfo.WorkingDirectory;LaunchExecutablePath=$ticket.Recorded.LaunchExecutablePath}
        if($p.HasExited){throw 'invalid'}
        Write-P6ChainedResourceReceipt $Context $Role $ticket.Recorded
        if($p.HasExited){throw 'invalid'}
        $ticket.State='RUNNING'
        return [pscustomobject]@{Status='STARTED';Code='REHEARSAL_RESOURCE_STARTED';Retained=$false}
    }catch{if($null -ne $ticket){$ticket.State='FAILED'};return [pscustomobject]@{Status='FAILED';Code='REHEARSAL_RESOURCE_START_FAILED';Retained=($null -ne $ticket)}}
}
function Read-P6HeldResourceOwnership {
    param($Context,[string]$Role,[scriptblock]$Observe=$null)
    try{
        Assert-P6CurrentResourceReceipt $Context
        if($Role -cnotin @('PG','API','GW') -or -not $Context.Tickets.Contains($Role)){throw 'invalid'}
        $ticket=$Context.Tickets[$Role]
        if($ticket.Process -isnot [Diagnostics.Process] -or $null -eq $ticket.Recorded -or $ticket.Recorded.Role -cne $Role -or $ticket.Process.Id -ne $ticket.Recorded.Pid){throw 'invalid'}
        if(-not (Test-P6ExecutablePath (Get-P6Field $ticket.Recorded 'LaunchExecutablePath') $ticket.Recorded.ExecutablePath) -or -not (Test-P6ExecutablePath (Get-P6Field $ticket.LaunchEvidence 'LaunchExecutablePath') $ticket.Recorded.LaunchExecutablePath)){throw 'invalid'}
        if($null -eq $Observe){$Observe={param($kind,$id)Read-P6SystemObservation $kind $id}}
        $observation=&$Observe 'PROCESS' $ticket.Recorded.Pid
        if((Get-P6Field $observation 'ObservationSucceeded') -isnot [bool] -or -not $observation.ObservationSucceeded -or (Get-P6Field $observation 'Exists') -isnot [bool] -or $observation.Pid -ne $ticket.Recorded.Pid){throw 'invalid'}
        $listeners=Assert-P6ListenerEvidence (&$Observe 'LISTENERS' 0)
        if($observation.Exists){
            Assert-P6ProcessIdentity $Context.Receipt $ticket.Recorded $observation.Process $ticket.LaunchEvidence|Out-Null
            if($Role -cne 'PG'){
                $tokens=ConvertFrom-P6FixedCommandLine $observation.Process.CommandLine
                $jar=Join-Path $Context.Receipt.RunDirectory $(if($Role -ceq 'API'){'api.jar'}else{'gateway.jar'})
                if($tokens.Count -ne 4 -or -not (Test-P6ExecutablePath $tokens[0] $ticket.Recorded.ExecutablePath) -or $tokens[1] -cne $ticket.Recorded.ArgumentMarker -or $tokens[2] -cne '-jar' -or $tokens[3] -cne $jar){throw 'invalid'}
            }
        }elseif($null -ne $observation.Process -or -not $ticket.Process.HasExited){throw 'invalid'}
        $lines=@()
        if($Role -ceq 'PG'){
            $path=Assert-P6ChildPath $Context.Receipt.RunDirectory (Join-Path $Context.Receipt.PgData 'postmaster.pid')
            if(Test-Path -LiteralPath $path){if((Get-Item -LiteralPath $path).Length -gt 8192){throw 'invalid'};$lines=@([IO.File]::ReadAllLines($path))}
        }
        return [pscustomobject]@{Succeeded=$true;Marker=(Read-P6OwnerMarker $Context.Receipt $Context.RunParent);ObservedProcess=$observation.Process;Listeners=$listeners;PidFileLines=$lines;LaunchEvidence=$ticket.LaunchEvidence}
    }catch{throw 'REHEARSAL_RESOURCE_OWNERSHIP_UNPROVEN'}
}
function Stop-P6HeldResource {
    param($Context,[string]$Role,[scriptblock]$Observe=$null,[scriptblock]$StopProcess=$null)
    try{
        $ticket=$Context.Tickets[$Role]
        $read={param($record,$timeout)Read-P6HeldResourceOwnership $Context $Role $Observe}.GetNewClosure()
        $first=&$read
        $ports=if($Role -ceq 'PG'){@($Context.Receipt.Ports[0])}elseif($Role -ceq 'API'){@($Context.Receipt.Ports[1])}else{@($Context.Receipt.Ports[2],$Context.Receipt.Ports[3])}
        if($ticket.Process.HasExited -and $null -eq $first.ObservedProcess){
            if(@($first.Listeners.Items|Where-Object{$_.OwningProcess -eq $ticket.Recorded.Pid -or $_.LocalPort -in $ports}).Count -ne 0){throw 'invalid'}
        }else{
            $stop={param($record)
                if($Role -ceq 'PG'){
                    $spec=Get-P6NativeToolSpec 'PG_STOP' $Context.Receipt $Context.RunParent (Read-P6OwnerMarker $Context.Receipt $Context.RunParent) $Context.Secrets
                    Assert-P6CurrentResourceReceipt $Context
                    $quick=New-Object P6QuickGuardState
                    # PG is expected to exit during this command; still monitor its output drain.
                    $quick.Drains=@($ticket.Drain)
                    $result=Invoke-P6BoundedChild $spec 40000 $null $quick
                    if($result.Retained){$Context.ShortTickets.Add($result.HeldTicket)}
                    if($null -ne (Get-P6Field $Context 'Evidence')){
                        $Context.Evidence.Add([pscustomobject]@{Phase='PG_STOP';Status=$result.Status;Code=$result.Code;ExitCode=$result.ExitCode;ElapsedMilliseconds=$result.ElapsedMilliseconds;Retained=$result.Retained})
                    }
                    Assert-P6CurrentResourceReceipt $Context
                    if($result.Status -cne 'EXITED'){throw 'invalid'}
                }elseif($null -eq $StopProcess){$ticket.Process.Kill()}else{&$StopProcess $ticket.Process|Out-Null}
            }.GetNewClosure()
            $wait={param($record,$timeout)$ticket.Process.WaitForExit($timeout)}.GetNewClosure()
            $result=if($Role -ceq 'PG'){Stop-P6OwnedPostgres $Context.Receipt $Context.RunParent $ticket.Recorded $read $stop $wait}else{Invoke-P6OwnedStop $Context.Receipt $Context.RunParent $ticket.Recorded $read $stop $wait}
            if($result.Status -cne 'STOPPED'){return $result}
        }
        $after=&$read
        if($null -ne $after.ObservedProcess -or @($after.Listeners.Items|Where-Object{$_.OwningProcess -eq $ticket.Recorded.Pid -or $_.LocalPort -in $ports}).Count -ne 0 -or -not $ticket.Drain.Wait(1000)){throw 'invalid'}
        $ticket.State='STOPPED'
        # Keep the original handle until removal proof, not just until Kill returns.
        return [pscustomobject]@{Status='STOPPED';Code='REHEARSAL_STOP_CONFIRMED'}
    }catch{return [pscustomobject]@{Status='RETAINED';Code='REHEARSAL_RESOURCE_OWNERSHIP_UNPROVEN'}}
}
function Assert-P6ResourceHealth {
    param($Context,[string]$PendingRole='',[scriptblock]$Observe=$null)
    Assert-P6ResourceLifetime $Context.Lifetime
    try{
        Assert-P6CurrentResourceReceipt $Context
        if($PendingRole -cnotin @('','PG','API','GW') -or $Context.Tickets.Count -ne @($Context.Receipt.Processes).Count){throw 'invalid'}
        foreach($record in $Context.Receipt.Processes){
            if(-not $Context.Tickets.Contains($record.Role)){throw 'invalid'}
            Assert-P6HeldResourceHealth $Context $record.Role ($record.Role -ceq $PendingRole) $Observe
        }
    }catch{throw 'REHEARSAL_HEALTH_UNPROVEN'}
    Assert-P6ResourceLifetime $Context.Lifetime
}
function Assert-P6HeldResourceHealth {
    param($Context,[string]$Role,[bool]$Pending=$false,[scriptblock]$Observe=$null)
    try{
        $evidence=Read-P6HeldResourceOwnership $Context $Role $Observe
        $ticket=$Context.Tickets[$Role]
        if($ticket.State -cne 'RUNNING' -or $ticket.Process.HasExited -or $null -eq $evidence.ObservedProcess -or $null -eq $ticket.Drain -or $ticket.Drain.Failed -or $ticket.Drain.Count -gt 65536){throw 'invalid'}
        $ports=if($Role -ceq 'PG'){@($Context.Receipt.Ports[0])}elseif($Role -ceq 'API'){@($Context.Receipt.Ports[1])}else{@($Context.Receipt.Ports[2],$Context.Receipt.Ports[3])}
        $own=@($evidence.Listeners.Items|Where-Object{$_.OwningProcess -eq $ticket.Recorded.Pid -or $_.LocalPort -in $ports})
        foreach($item in $own){if($item.OwningProcess -ne $ticket.Recorded.Pid -or $item.LocalAddress -cne '127.0.0.1' -or $item.LocalPort -notin $ports){throw 'invalid'}}
        if(@($own|Select-Object -ExpandProperty LocalPort -Unique).Count -ne $own.Count){throw 'invalid'}
        if(-not $Pending){
            if($own.Count -ne @($ports).Count){throw 'invalid'}
            if($Role -ceq 'PG'){Assert-P6PgIdentity $Context.Receipt $ticket.Recorded $evidence.ObservedProcess $evidence.PidFileLines $evidence.Listeners.Items $ticket.LaunchEvidence|Out-Null}
        }
    }catch{throw 'REHEARSAL_HEALTH_UNPROVEN'}
}
function Read-P6ResourceProcessInventory {
    param($Context,[scriptblock]$Query=$null)
    try{
        if($null -eq $Query){$Query={Get-CimInstance -Namespace 'root/cimv2' -ClassName 'Win32_Process' -Filter 'ProcessId > 0' -OperationTimeoutSec 3 -ErrorAction Stop}}
        $rows=@(&$Query)
        if($rows.Count -eq 0 -or $rows.Count -gt 65536){throw 'invalid'}
        $owned=New-Object 'Collections.Generic.HashSet[int]'
        foreach($id in @($Context.Receipt.Processes|ForEach-Object{$_.Pid})+@($Context.ToolProcessIds)){
            if($id -isnot [int] -or $id -le 0){throw 'invalid'};[void]$owned.Add($id)
        }
        $root=$Context.Receipt.RunDirectory;$marker='-Dp6.rehearsal.run='+$Context.Receipt.RunId
        foreach($row in $rows){
            foreach($key in @('ProcessId','ParentProcessId')){if((Get-P6Field $row $key) -isnot [int] -and (Get-P6Field $row $key) -isnot [uint32]){throw 'invalid'}}
            if($row.ProcessId -le 0 -or $row.ParentProcessId -lt 0){throw 'invalid'}
            $command=Get-P6Field $row 'CommandLine'
            if($null -ne $command -and $command -isnot [string]){throw 'invalid'}
            if($command -is [string] -and ($command.IndexOf($root,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $command.Contains($marker))){[void]$owned.Add([int]$row.ProcessId)}
        }
        do{$before=$owned.Count;foreach($row in $rows){if($owned.Contains([int]$row.ParentProcessId)){[void]$owned.Add([int]$row.ProcessId)}}}while($before -ne $owned.Count)
        return [pscustomobject]@{Succeeded=$true;Items=@($rows|Where-Object{$owned.Contains([int]$_.ProcessId)}|ForEach-Object{[int]$_.ProcessId})}
    }catch{throw 'REHEARSAL_PROCESS_INVENTORY_UNPROVEN'}
}
function Assert-P6ResourceRemoval {
    param($Context,[scriptblock]$Observe=$null)
    try{
        Assert-P6CurrentResourceReceipt $Context
        $root=$Context.Receipt.RunDirectory
        if($Context.RunParent -cne (Get-P6NativeRunParent $Context.RepositoryRoot) -or $Context.ShortTickets.Count -ne 0 -or $Context.Tickets.Count -ne @($Context.Receipt.Processes).Count){throw 'invalid'}
        Assert-P6ChildPath $Context.RunParent $root -MustExist|Out-Null;Assert-P6NativePathLength $root;Assert-P6PrivateAcl $root
        if($null -eq $Observe){$Observe={param($kind,$id)if($kind -ceq 'RUN_PROCESSES'){Read-P6ResourceProcessInventory $Context}else{Read-P6SystemObservation $kind $id}}.GetNewClosure()}
        $observations=@(foreach($record in $Context.Receipt.Processes){
            $ticket=$Context.Tickets[$record.Role]
            if($ticket.Process -isnot [Diagnostics.Process] -or $ticket.State -cne 'STOPPED' -or -not $ticket.Process.HasExited -or $ticket.Process.Id -ne $record.Pid -or $ticket.LaunchEvidence.StartTicks -ne $ticket.Process.StartTime.ToUniversalTime().Ticks){throw 'invalid'}
            &$Observe 'PROCESS' $record.Pid
        })
        $listeners=Assert-P6ListenerEvidence (&$Observe 'LISTENERS' 0)
        $inventory=&$Observe 'RUN_PROCESSES' 0
        if((Get-P6Field $inventory 'Succeeded') -isnot [bool] -or -not $inventory.Succeeded -or $null -eq $inventory.PSObject.Properties['Items'] -or $inventory.Items -isnot [array] -or $inventory.Items.Count -ne 0){throw 'invalid'}
        # Always consume every independent PROCESS observation in the Task1A proof.
        # Its fixed child target may be absent; root/marker/receipt/listeners/reparse are still proved.
        Assert-P6RemovalProof $Context.Receipt $Context.RunParent $Context.Marker (Join-Path $root 'pgdata') $observations $listeners|Out-Null
        if(@($listeners.Items|Where-Object{$_.LocalPort -in $Context.Receipt.Ports}).Count -ne 0){throw 'invalid'}
        $pending=New-Object 'Collections.Generic.Stack[string]';$pending.Push($root)
        while($pending.Count -gt 0){foreach($item in @(Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop)){
            if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'invalid'}
            if($item.PSIsContainer){$pending.Push($item.FullName)}
        }}
        return $true
    }catch{throw 'REHEARSAL_REMOVAL_UNPROVEN'}
}
function Remove-P6ResourceRun {
    param($Context,[scriptblock]$Observe=$null)
    try{
        # The destructive entry point repeats the full proof; a caller's earlier PROVEN is not authority.
        if(-not (Assert-P6ResourceRemoval $Context $Observe)){throw 'invalid'}
        $target=Assert-P6ChildPath $Context.RunParent $Context.Receipt.RunDirectory -MustExist
        Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
        if(Test-Path -LiteralPath $target){throw 'invalid'}
        foreach($ticket in $Context.Tickets.Values){$ticket.Process.Dispose()}
        return $true
    }catch{throw 'REHEARSAL_REMOVAL_UNPROVEN'}
}
