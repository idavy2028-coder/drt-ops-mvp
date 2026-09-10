# 本地合成演练的实际消费者；仅CLI负责签发当前快照并创建Context。
Set-StrictMode -Version Latest
function Initialize-P6FixedOutputDrain {
    if($null -ne ('P6FixedOutputDrain' -as [type])){return}
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
public sealed class P6FixedOutputDrain {
 private long count; private int failed; private readonly Task[] tasks;
 private readonly StringBuilder text = new StringBuilder();
 public long Count { get { return Interlocked.Read(ref count); } }
 public bool Failed { get { return Volatile.Read(ref failed)!=0; } }
 public P6FixedOutputDrain(StreamReader output,StreamReader error){tasks=new[]{Drain(output,true),Drain(error,false)};}
 private Task Drain(StreamReader reader,bool capture){return Task.Run(async()=>{try{char[] b=new char[1024];int n;while((n=await reader.ReadAsync(b,0,b.Length))>0){Interlocked.Add(ref count,n);if(capture){lock(text){if(text.Length+n<=4096)text.Append(b,0,n);else Interlocked.Exchange(ref failed,1);}}}}catch{Interlocked.Exchange(ref failed,1);}});}
 public bool Wait(int ms){try{return Task.WaitAll(tasks,ms);}catch{return false;}}
 public bool Matches(string expected){lock(text){return !Failed && text.ToString().Trim()==expected;}}
}
'@ | Out-Null
}
function Assert-P6RuntimeGuard($Context,[string]$Pending='') {
    Assert-P6ResourceLifetime $Context.Lifetime
    if($null -ne $Context.Plan){Assert-P6ExecutionSnapshot $Context.Plan $Context.Token|Out-Null}
    if($Context.Tickets.Count -gt 0){Assert-P6ResourceHealth $Context $Pending}
    if($null -ne (Get-P6Field $Context 'Receipt')){Assert-P6CurrentResourceReceipt $Context}
}
function Invoke-P6RuntimeTool($Context,$Spec,[string]$Phase,[int]$Timeout=60000) {
    # Maven需要超过60秒，但每次等待最多250ms并受统一30分钟预算保护。
    # 超时仅停止原始句柄并有界等待；不按名称/端口杀进程，不明则落盘精确receipt并保留。
    if($Timeout -lt 100 -or $Timeout -gt 1200000){throw 'REHEARSAL_TOOL_TIMEOUT_INVALID'}
    Assert-P6RuntimeGuard $Context
    Initialize-P6StreamDrain
    if($null -ne (Get-P6Field $Spec 'ExpectedOutput')){Initialize-P6FixedOutputDrain}
    $p=New-Object Diagnostics.Process;$p.StartInfo=New-P6ProcessStartInfo $Spec
    $drain=$null;$timer=[Diagnostics.Stopwatch]::StartNew();$started=$false;$done=$false
    try {
        if(-not $p.Start()){throw 'REHEARSAL_TOOL_FAILED'};$started=$true
        if($null -ne (Get-P6Field $Spec 'ExpectedOutput')){Initialize-P6FixedOutputDrain;$drain=New-Object P6FixedOutputDrain($p.StandardOutput,$p.StandardError)}else{$drain=New-Object P6NativeStreamDrain($p.StandardOutput,$p.StandardError)}
        $Context.ToolProcessIds.Add($p.Id)
        $nextGuard=0L
        while(-not ($p.HasExited -and $drain.Wait(0))){
            Assert-P6ResourceLifetime $Context.Lifetime
            # drain从不保存文本；Maven测试输出允许至4Mi字符，其他工具仍64Ki。
            $limit=if($Phase -cin @('BUILD','EXTERNAL59')){4194304}else{65536}
            if($timer.ElapsedMilliseconds -ge $Timeout -or $drain.Failed -or $drain.Count -gt $limit){throw 'REHEARSAL_TOOL_FAILED'}
            if($timer.ElapsedMilliseconds -ge $nextGuard){Assert-P6RuntimeGuard $Context;$nextGuard=$timer.ElapsedMilliseconds+3000}
            [void]$p.WaitForExit(250)
        }
        $done=$true
        $result=[pscustomobject]@{Phase=$Phase;ExitCode=$p.ExitCode;OutputCharacters=$drain.Count;ElapsedMilliseconds=$timer.ElapsedMilliseconds}
        $Context.Evidence.Add($result)
        Assert-P6RuntimeGuard $Context
        if($p.ExitCode -ne 0){throw 'REHEARSAL_TOOL_FAILED'}
        if($null -ne (Get-P6Field $Spec 'ExpectedOutput') -and -not $drain.Matches($Spec.ExpectedOutput)){throw 'REHEARSAL_TOOL_OUTPUT_INVALID'}
        return $result
    } finally {
        if($started -and -not $done){
            $stopped=$false
            try{if(-not $p.HasExited){$p.Kill()};$stopped=$p.WaitForExit(5000) -and $null -ne $drain -and $drain.Wait(1000)}catch{}
            if($stopped){$Context.Evidence.Add([pscustomobject]@{Phase=$Phase;Cleanup='ORIGINAL_HANDLE_STOPPED'});$p.Dispose()}
            else{
                $ticket=[pscustomobject]@{Process=$p;Drain=$drain;Phase=$Phase};$Context.ShortTickets.Add($ticket)
                if($null -ne (Get-P6Field $Context 'Receipt')){
                    $record=@{Phase=$Phase;Pid=$p.Id;StartTimeUtc=$p.StartTime.ToUniversalTime().ToString('o');ExecutablePath=$Spec.FileName;RunId=$Context.Receipt.RunId;OwnerNonce=$Context.Receipt.OwnerNonce;Status='STOP_UNPROVEN'}
                    Write-P6AtomicNewFile $Context.Receipt.RunDirectory (Join-Path $Context.Receipt.RunDirectory ('retained-tool-'+$p.Id+'.json')) ([Text.Encoding]::UTF8.GetBytes(($record|ConvertTo-Json -Compress)))
                }
            }
        } else{$p.Dispose()}
    }
}
function New-P6RuntimeContext($Plan,[string]$Token) {
    if(-not (Get-P6PostgresHostState).CanStartPostgres){throw 'REHEARSAL_NONADMIN_HOST_REQUIRED'}
    Assert-P6ExecutionSnapshot $Plan $Token|Out-Null
    $parent=Get-P6NativeRunParent $Plan.RepositoryRoot
    Assert-P6ChildPath $Plan.RepositoryRoot $parent|Out-Null
    [void][IO.Directory]::CreateDirectory($parent)
    $run=[guid]::NewGuid().ToString('N');$root=Join-Path $parent ('native-'+$run)
    Assert-P6NewRunDirectory $parent $root|Out-Null
    [void][IO.Directory]::CreateDirectory($root);Protect-P6RunAcl $root
    $ports=@();$listeners=@()
    try{1..4|ForEach-Object{$listener=New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback,0);$listener.Start();$listeners+=,$listener;$ports+=,[int]$listener.LocalEndpoint.Port}}finally{foreach($listener in $listeners){$listener.Stop()}}
    $nonce=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N');$created=[DateTimeOffset]::UtcNow.ToString('o')
    $marker=[pscustomobject]@{SchemaVersion=1;RunId=$run;OwnerNonce=$nonce;RunDirectory=$root;CreatedAt=$created;PgPort=$ports[0]}
    $receipt=[pscustomobject]@{SchemaVersion=1;RunId=$run;OwnerNonce=$nonce;RunDirectory=$root;CreatedAt=$created;PgData=(Join-Path $root 'pgdata');PgDatabase='composite_live';MigrationDatabase='composite_onboard';Ports=$ports;Processes=@()}
    $secrets=@{};foreach($key in @('DbPassword','JwtSecret','BootstrapPassword','RotatedPassword','GatewayCredential','H2Password')){$secrets[$key]=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')}
    Write-P6OwnedStorage $receipt $parent $marker $secrets|Out-Null
    [pscustomobject]@{Plan=$Plan;Token=$Token;RepositoryRoot=$Plan.RepositoryRoot;RunParent=$parent;Receipt=$receipt;Marker=$marker;Secrets=$secrets;ReceiptHash=(Get-P6Sha256 ([IO.File]::ReadAllBytes((Join-Path $root 'receipt.json'))));Lifetime=(New-P6ResourceLifetime);Tickets=@{};ShortTickets=(New-Object 'Collections.Generic.List[object]');ToolProcessIds=(New-Object 'Collections.Generic.List[int]');ArtifactHashes=@{};Evidence=(New-Object 'Collections.Generic.List[object]');ClassPath=''}
}
function Get-P6RuntimeMavenSpec($Context,[string[]]$Goals,[hashtable]$Extra=@{}) {
    $maven='C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.4\plugins\maven\lib\maven3'
    $environment=Get-P6ChildEnvironment $Context.Receipt.RunDirectory
    $environment.JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
    foreach($key in $Extra.Keys){$environment[$key]=$Extra[$key]}
    # 直接运行固定Maven Java launcher，避开cmd持有句柄但Java后代失联的问题。
    [pscustomobject]@{FileName='C:\Program Files\Java\jdk-21.0.10\bin\java.exe';WorkingDirectory=$Context.RepositoryRoot;Environment=$environment;Arguments=@('-Xmx768m',('-Dmaven.home='+$maven),('-Dclassworlds.conf='+(Join-Path $maven 'bin/m2.conf')),('-Dmaven.multiModuleProjectDirectory='+$Context.RepositoryRoot),'-cp',(Join-Path $maven 'boot/plexus-classworlds-2.9.0.jar'),'org.codehaus.plexus.classworlds.launcher.Launcher','-q','-B','-ntp')+$Goals}
}
function Wait-P6RuntimeReady($Context,[string]$Role) {
    $timer=[Diagnostics.Stopwatch]::StartNew()
    while($timer.ElapsedMilliseconds -lt 120000){
        Assert-P6RuntimeGuard $Context $Role
        try{
            if($Role -ceq 'PG'){
                $ev=Read-P6HeldResourceOwnership $Context 'PG'
                Assert-P6PgIdentity $Context.Receipt $Context.Tickets.PG.Recorded $ev.ObservedProcess $ev.PidFileLines $ev.Listeners.Items $ev.LaunchEvidence|Out-Null
            }else{
                $port=if($Role -ceq 'API'){$Context.Receipt.Ports[1]}else{$Context.Receipt.Ports[2]}
                # API exposes overall health anonymously; gateway retains readiness semantics.
                $healthPath=if($Role -ceq 'API'){'/actuator/health'}else{'/actuator/health/readiness'}
                $req=[Net.HttpWebRequest]::Create(('http://127.0.0.1:{0}{1}' -f $port,$healthPath));$req.Proxy=$null;$req.Timeout=3000;$req.ReadWriteTimeout=1000;$req.AllowAutoRedirect=$false
                $res=$req.GetResponse();try{
                    if([int]$res.StatusCode -ne 200){throw 'not ready'}
                    $stream=$res.GetResponseStream();$buffer=New-Object byte[] 1024;$memory=New-Object IO.MemoryStream;$bodyTimer=[Diagnostics.Stopwatch]::StartNew()
                    try{while($true){Assert-P6ResourceLifetime $Context.Lifetime;if($bodyTimer.ElapsedMilliseconds -ge 3000){throw 'REHEARSAL_READY_BODY_TIMEOUT'};$n=$stream.Read($buffer,0,$buffer.Length);if($n -eq 0){break};if($memory.Length+$n -gt 65536){throw 'REHEARSAL_READY_BODY_LIMIT'};$memory.Write($buffer,0,$n)};$body=[Text.Encoding]::UTF8.GetString($memory.ToArray())|ConvertFrom-Json}finally{$stream.Dispose();$memory.Dispose()}
                    if($body.status -cne 'UP'){throw 'not ready'}
                }finally{$res.Dispose()}
            }
            Assert-P6RuntimeGuard $Context
            return
        }catch{if($Context.Tickets[$Role].Process.HasExited){throw 'REHEARSAL_READY_FAILED'}}
        Start-Sleep -Milliseconds 500
    }
    throw 'REHEARSAL_READY_TIMEOUT'
}
function Get-P6RuntimeHelperEnvironment($Context) {
    $env=Get-P6ChildEnvironment $Context.Receipt.RunDirectory
    $env.P6_REHEARSAL_RUN_ID=$Context.Receipt.RunId;$env.P6_REHEARSAL_OWNER_NONCE=$Context.Receipt.OwnerNonce;$env.P6_REHEARSAL_RUN_DIRECTORY=$Context.Receipt.RunDirectory
    $env.P6_REHEARSAL_DB_USER='composite';$env.P6_REHEARSAL_DB_PASSWORD=$Context.Secrets.DbPassword
    $env.P6_REHEARSAL_JDBC_URL='jdbc:postgresql://127.0.0.1:'+$Context.Receipt.Ports[0]+'/composite_live'
    return $env
}
function Invoke-P6RuntimeJava($Context,[string]$Class,[hashtable]$Environment,[string]$Phase,[int]$Timeout=60000){
    $spec=[pscustomobject]@{FileName='C:\Program Files\Java\jdk-21.0.10\bin\java.exe';WorkingDirectory=$Context.Receipt.RunDirectory;Environment=$Environment;Arguments=@('-Xmx512m',('-Dp6.rehearsal.run='+$Context.Receipt.RunId),'-cp',$Context.ClassPath,$Class)}
    Invoke-P6RuntimeTool $Context $spec $Phase $Timeout|Out-Null
}
function New-P6RuntimeReports($Context) {
    $target=Assert-P6ChildPath $Context.RepositoryRoot (Join-Path $Context.RepositoryRoot 'apps/api/target/surefire-reports')
    if(Test-Path -LiteralPath $target){throw 'REHEARSAL_REPORTS_ALREADY_EXIST'}
    [void][IO.Directory]::CreateDirectory($target)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
    Set-Acl -LiteralPath $target -AclObject $acl -ErrorAction Stop
    Assert-P6PrivateAcl $target
    Write-P6AtomicNewFile $target (Join-Path $target 'p6-owner.txt') ([Text.Encoding]::ASCII.GetBytes($Context.Receipt.OwnerNonce))
    $Context|Add-Member NoteProperty ReportsDirectory $target
    return $target
}
function Remove-P6RuntimeReports($Context) {
    $target=Get-P6Field $Context 'ReportsDirectory';if($null -eq $target){return}
    if($target -cne (Join-Path $Context.RepositoryRoot 'apps/api/target/surefire-reports')){throw 'REHEARSAL_REPORTS_OWNERSHIP_INVALID'}
    Assert-P6ChildPath $Context.RepositoryRoot $target -MustExist|Out-Null;Assert-P6PrivateAcl $target
    if([IO.File]::ReadAllText((Join-Path $target 'p6-owner.txt')) -cne $Context.Receipt.OwnerNonce -or $Context.ShortTickets.Count -ne 0){throw 'REHEARSAL_REPORTS_OWNERSHIP_INVALID'}
    if((Read-P6ResourceProcessInventory $Context).Items.Count -ne 0){throw 'REHEARSAL_REPORTS_PROCESS_UNPROVEN'}
    $stack=New-Object 'Collections.Generic.Stack[string]';$stack.Push($target)
    while($stack.Count -gt 0){foreach($item in @(Get-ChildItem -LiteralPath $stack.Pop() -Force)){
        if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'REHEARSAL_REPORTS_OWNERSHIP_INVALID'}
        if($item.PSIsContainer){$stack.Push($item.FullName)}
    }}
    Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction Stop
    if(Test-Path -LiteralPath $target){throw 'REHEARSAL_REPORTS_REMOVAL_UNPROVEN'}
}
function Invoke-P6RealIsolationExecution($Plan,[string]$ConfirmationToken) {
    $context=$null;$phase='SNAPSHOT';$ok=$false;$businessPassed=$false;$retained=$true;$code='REHEARSAL_EXECUTION_FAILED'
    $stageNames=@('SNAPSHOT','BUILD','PG_INIT','EXTERNAL59','HELPER','MIGRATE_19','PREPARE_V20','MIGRATE_20','MIGRATE_21','VALIDATE','API','BUSINESS','GW','WIRE','TASK12','LEASE_RELEASE','FINAL_PG_PROBE')
    $cleanup=New-Object 'Collections.Generic.List[object]'
    $executionPhase='SNAPSHOT'
    try {
        $context=New-P6RuntimeContext $Plan $ConfirmationToken
        $root=$context.Receipt.RunDirectory
        $phase='BUILD';[Console]::Out.WriteLine('P6_PHASE=BUILD')
        $spec=Get-P6RuntimeMavenSpec $context @('clean','package','-DskipTests')
        Invoke-P6RuntimeTool $context $spec $phase 1200000|Out-Null
        foreach($entry in @(@('API','apps/api/target/drt-ops-api-0.1.0-SNAPSHOT.jar','api.jar'),@('GW','apps/jt-gateway/target/drt-ops-jt-gateway-0.1.0-SNAPSHOT.jar','gateway.jar'))){
            Assert-P6RuntimeGuard $context
            $source=Assert-P6ChildPath $context.RepositoryRoot (Join-Path $context.RepositoryRoot $entry[1]) -MustExist
            $bytes=[IO.File]::ReadAllBytes($source);Write-P6AtomicNewFile $root (Join-Path $root $entry[2]) $bytes
            $context.ArtifactHashes[$entry[0]]=Get-P6Sha256 $bytes
        }
        $phase='PG_INIT';[Console]::Out.WriteLine('P6_PHASE=PG_INIT')
        $spec=Get-P6NativeToolSpec INITDB $context.Receipt $context.RunParent $context.Marker $context.Secrets
        Invoke-P6RuntimeTool $context $spec $phase|Out-Null
        Assert-P6RuntimeGuard $context
        $start=Start-P6HeldResource $context PG;if($start.Status -cne 'STARTED'){throw 'REHEARSAL_RESOURCE_START_FAILED'}
        Wait-P6RuntimeReady $context PG
        foreach($action in @('CREATE_MIGRATION_DB','CREATE_LIVE_DB')){Invoke-P6RuntimeTool $context (Get-P6NativeToolSpec $action $context.Receipt $context.RunParent $context.Marker $context.Secrets) $action|Out-Null}
        foreach($action in @('ENABLE_MIGRATION_POSTGIS','ENABLE_LIVE_POSTGIS')){Invoke-P6RuntimeTool $context (Get-P6NativeToolSpec $action $context.Receipt $context.RunParent $context.Marker $context.Secrets) $action|Out-Null}
        Invoke-P6RuntimeTool $context (Get-P6NativeToolSpec PG_PROBE $context.Receipt $context.RunParent $context.Marker $context.Secrets) PG_PROBE|Out-Null
        $phase='EXTERNAL59';[Console]::Out.WriteLine('P6_PHASE=EXTERNAL59')
        $testStart=[DateTime]::UtcNow
        $testOptions='-Ddrt.integration.composite-onboard=true -Ddrt.integration.composite-onboard.external-ephemeral=true -Ddrt.integration.composite-onboard.jdbc-url=jdbc:postgresql://127.0.0.1:'+$context.Receipt.Ports[0]+'/composite_onboard -Ddrt.integration.composite-onboard.username=composite -Ddrt.integration.composite-onboard.password='+$context.Secrets.DbPassword
        $reports=New-P6RuntimeReports $context
        $spec=Get-P6RuntimeMavenSpec $context @('-pl','apps/api','-am','test','-Dtest=P6CompositeOnboardSystemMigrationTest','-Dsurefire.failIfNoSpecifiedTests=false','-DforkCount=0') @{JAVA_TOOL_OPTIONS=$testOptions}
        Invoke-P6RuntimeTool $context $spec $phase 1200000|Out-Null
        $xmlFiles=@(Get-ChildItem -LiteralPath $reports -Filter '*P6CompositeOnboardSystemMigrationTest.xml')
        if($xmlFiles.Count -ne 1 -or $xmlFiles[0].LastWriteTimeUtc -lt $testStart){throw 'REHEARSAL_EXTERNAL59_INVALID'}
        $suite=([xml][IO.File]::ReadAllText($xmlFiles[0].FullName)).testsuite
        if([int]$suite.tests -ne 59 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0){throw 'REHEARSAL_EXTERNAL59_INVALID'}
        $context.Evidence.Add([pscustomobject]@{Phase='EXTERNAL59_COUNTS';Tests=59;Failures=0;Errors=0;Skipped=0})
        $phase='HELPER';[Console]::Out.WriteLine('P6_PHASE=HELPER')
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        foreach($jar in @('api','gateway')){[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $root ($jar+'.jar')),(Join-Path $root $jar))}
        [void][IO.Directory]::CreateDirectory((Join-Path $root 'classes'))
        $context.ClassPath=@((Join-Path $root 'classes'),(Join-Path $root 'api/BOOT-INF/classes'),(Join-Path $root 'api/BOOT-INF/lib/*'),(Join-Path $root 'gateway/BOOT-INF/lib/*'),(Join-Path $context.RepositoryRoot 'tools/jt-terminal-simulator/target/classes'),(Join-Path $context.RepositoryRoot 'libs/jt-protocol/target/classes')) -join ';'
        $spec=[pscustomobject]@{FileName='C:\Program Files\Java\jdk-21.0.10\bin\javac.exe';WorkingDirectory=$root;Environment=(Get-P6ChildEnvironment $root);Arguments=@('-encoding','UTF-8','-cp',$context.ClassPath,'-d',(Join-Path $root 'classes'))+@('P6CompositeFlywayTool','P6CompositeWireHarness','P6CompositeBusinessTool'|ForEach-Object{Join-Path $context.RepositoryRoot ('tools/ops-safety/fixtures/'+$_+'.java')})}
        Invoke-P6RuntimeTool $context $spec $phase|Out-Null
        foreach($action in @('MIGRATE_19','PREPARE_V20','MIGRATE_20','MIGRATE_21','VALIDATE')){
            $phase=$action;[Console]::Out.WriteLine(('P6_PHASE='+$phase));$env=Get-P6RuntimeHelperEnvironment $context;$env.P6_REHEARSAL_ACTION=$action
            Invoke-P6RuntimeJava $context P6CompositeFlywayTool $env $action
        }
        $phase='API';[Console]::Out.WriteLine('P6_PHASE=API');Assert-P6RuntimeGuard $context
        $start=Start-P6HeldResource $context API;if($start.Status -cne 'STARTED'){throw 'REHEARSAL_RESOURCE_START_FAILED'};Wait-P6RuntimeReady $context API
        $phase='BUSINESS';[Console]::Out.WriteLine('P6_PHASE=BUSINESS')
        $env=Get-P6RuntimeHelperEnvironment $context;$env.P6_REHEARSAL_API_PORT=[string]$context.Receipt.Ports[1];$env.P6_REHEARSAL_GATEWAY_TCP_PORT=[string]$context.Receipt.Ports[3];$env.P6_REHEARSAL_BOOTSTRAP_PASSWORD=$context.Secrets.BootstrapPassword;$env.P6_REHEARSAL_ROTATED_PASSWORD=$context.Secrets.RotatedPassword;$env.P6_REHEARSAL_BUSINESS_ACTION='PREPARE'
        Invoke-P6RuntimeJava $context P6CompositeBusinessTool $env $phase 180000
        $businessEvidence=[IO.File]::ReadAllText((Join-Path $root 'business-evidence.json'))|ConvertFrom-Json
        if($businessEvidence.PreviewComparisons -ne 3 -or -not $businessEvidence.PreviewStable){throw 'REHEARSAL_BUSINESS_EVIDENCE_INVALID'}
        $context.Evidence.Add($businessEvidence)
        $wireHash=Get-P6Sha256 ([IO.File]::ReadAllBytes((Join-Path $root 'wire.properties')))
        Write-P6AtomicNewFile $root (Join-Path $root 'wire-receipt.json') ([Text.Encoding]::UTF8.GetBytes((@{WireSha256=$wireHash;PreviousReceiptSha256=$context.ReceiptHash}|ConvertTo-Json -Compress)))
        $phase='GW';[Console]::Out.WriteLine('P6_PHASE=GW');Assert-P6RuntimeGuard $context
        $start=Start-P6HeldResource $context GW;if($start.Status -cne 'STARTED'){throw 'REHEARSAL_RESOURCE_START_FAILED'};Wait-P6RuntimeReady $context GW
        $phase='WIRE';[Console]::Out.WriteLine('P6_PHASE=WIRE')
        $env=Get-P6RuntimeHelperEnvironment $context;$env.P6_REHEARSAL_API_BASE_URL='http://127.0.0.1:'+$context.Receipt.Ports[1];$env.P6_REHEARSAL_GATEWAY_TCP_PORT=[string]$context.Receipt.Ports[3];$env.P6_REHEARSAL_GATEWAY_INSTANCE='rehearsal-'+$context.Receipt.RunId;$env.P6_REHEARSAL_API_TOKEN=[IO.File]::ReadAllText((Join-Path $root 'secrets/api-token.txt'))
        if((Get-P6Sha256 ([IO.File]::ReadAllBytes((Join-Path $root 'wire.properties')))) -cne $wireHash){throw 'REHEARSAL_WIRE_RECEIPT_CHANGED'}
        $wireLines=[IO.File]::ReadAllLines((Join-Path $root 'wire.properties'))
        foreach($letter in @('A','B','C')){$line=@($wireLines|Where-Object{$_ -cmatch ('^Vehicle'+$letter+'Id=')});if($line.Count -ne 1){throw 'REHEARSAL_WIRE_MAPPING_INVALID'};$env['P6_REHEARSAL_VEHICLE_'+$letter+'_ID']=$line[0].Split('=')[1]}
        Invoke-P6RuntimeJava $context P6CompositeWireHarness $env $phase 120000
        $phase='TASK12';[Console]::Out.WriteLine('P6_PHASE=TASK12')
        $task=Join-Path $context.RepositoryRoot 'tools/ops-safety/Invoke-Task12SafetyGate.ps1';$expected=Join-Path $root 'acceptance/expected.json';$results=Join-Path $root 'acceptance/results.json'
        $spec=[pscustomobject]@{FileName=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe');WorkingDirectory=$root;Environment=(Get-P6ChildEnvironment $root);Arguments=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$task,'-Mode','VerifyAcceptance','-ExpectedPath',$expected,'-ResultsPath',$results);ExpectedOutput='TASK12_SAFETY_STATUS=PASS MODE=VerifyAcceptance ACCEPTED=4'}
        Invoke-P6RuntimeTool $context $spec $phase|Out-Null
        $phase='LEASE_RELEASE';[Console]::Out.WriteLine('P6_PHASE=LEASE_RELEASE');$env=Get-P6RuntimeHelperEnvironment $context;$env.P6_REHEARSAL_API_PORT=[string]$context.Receipt.Ports[1];$env.P6_REHEARSAL_BUSINESS_ACTION='LEASE_RELEASE'
        Invoke-P6RuntimeJava $context P6CompositeBusinessTool $env $phase 210000
        $phase='FINAL_PG_PROBE';Invoke-P6RuntimeTool $context (Get-P6NativeToolSpec PG_PROBE $context.Receipt $context.RunParent $context.Marker $context.Secrets) FINAL_PG_PROBE|Out-Null
        $ok=$true;$businessPassed=$true;$phase='COMPLETE';$code='REHEARSAL_COMPLETE'
    } catch {if($_.Exception.Message -cmatch '^REHEARSAL_[A-Z0-9_]+$'){$code=$_.Exception.Message}}
    finally {
        $executionPhase=$phase
        if($null -ne $context){
            $retained= -not $ok
            foreach($role in @('GW','API','PG')){
                if($context.Tickets.Contains($role)){
                    $stopResult=Stop-P6HeldResource $context $role
                    $cleanup.Add([pscustomobject]@{Resource=$role;Status=[string]$stopResult.Status;Code=[string]$stopResult.Code})
                    if($stopResult.Status -cne 'STOPPED'){$retained=$true;$ok=$false;$code='REHEARSAL_STOP_UNPROVEN';$phase='CLEANUP'}
                }else{$cleanup.Add([pscustomobject]@{Resource=$role;Status='SKIP';Code='NOT_STARTED'})}
            }
            if($context.ShortTickets.Count -gt 0){$retained=$true;$ok=$false;$code='REHEARSAL_TOOL_STOP_UNPROVEN';$phase='CLEANUP'}
            $cleanup.Add([pscustomobject]@{Resource='TOOLS';Status=$(if($context.ShortTickets.Count -gt 0){'RETAINED'}elseif($context.ToolProcessIds.Count -gt 0){'STOPPED'}else{'SKIP'});Code=$(if($context.ShortTickets.Count -gt 0){'REHEARSAL_TOOL_STOP_UNPROVEN'}else{'NO_HELD_TOOL_REMAINS'})})
            foreach($toolEvidence in $context.Evidence){
                if($null -ne $toolEvidence.PSObject.Properties['ExitCode']){$cleanup.Add([pscustomobject]@{Resource='TOOL';Phase=$toolEvidence.Phase;Status='EXITED';ExitCode=$toolEvidence.ExitCode})}
                elseif((Get-P6Field $toolEvidence 'Cleanup') -ceq 'ORIGINAL_HANDLE_STOPPED'){$cleanup.Add([pscustomobject]@{Resource='TOOL';Phase=$toolEvidence.Phase;Status='STOPPED';Code='ORIGINAL_HANDLE_STOPPED'})}
            }
            foreach($ticket in $context.ShortTickets){$cleanup.Add([pscustomobject]@{Resource='TOOL';Phase=$ticket.Phase;Status='RETAINED';Code='REHEARSAL_TOOL_STOP_UNPROVEN'})}
            if(-not $retained){
                try{Assert-P6ExecutionSnapshot $Plan $ConfirmationToken|Out-Null;Remove-P6RuntimeReports $context;Remove-P6ResourceRun $context|Out-Null;$cleanup.Add([pscustomobject]@{Resource='STORAGE';Status='REMOVED';Code='REHEARSAL_REMOVAL_CONFIRMED'})}
                catch{$retained=$true;$ok=$false;$code='REHEARSAL_REMOVAL_UNPROVEN';$phase='CLEANUP';$cleanup.Add([pscustomobject]@{Resource='STORAGE';Status='RETAINED';Code=$code})}
            }else{$cleanup.Add([pscustomobject]@{Resource='STORAGE';Status='RETAINED';Code='FAILURE_EVIDENCE_RETAINED'})}
        }else{if($code -cin @('REHEARSAL_NONADMIN_HOST_REQUIRED','REHEARSAL_CONFIRMATION_INVALID','REHEARSAL_WORKTREE_DIRTY','REHEARSAL_TOOLS_UNCOMMITTED')){$retained=$false};foreach($role in @('GW','API','PG','TOOLS','STORAGE')){$cleanup.Add([pscustomobject]@{Resource=$role;Status='SKIP';Code='NOT_STARTED'})}}
    }
    $failedIndex=[array]::IndexOf($stageNames,$executionPhase)
    $steps=@(for($i=0;$i -lt $stageNames.Count;$i++){
        $status=if($businessPassed -or $i -lt $failedIndex){'PASS'}elseif($i -eq $failedIndex){'FAIL'}else{'SKIP'}
        [pscustomobject]@{Phase=$stageNames[$i];Status=$status}
    })
    $result=[pscustomobject]@{Status=$(if($ok){'PASS'}else{'FAIL'});Phase=$phase;Code=$code;Retained=$retained;SourceHead=$Plan.Head;Steps=$steps;Cleanup=$cleanup.ToArray();ArtifactHashes=$(if($null -ne $context){$context.ArtifactHashes}else{@{}});Evidence=$(if($null -ne $context){$context.Evidence.ToArray()}else{@()})}
    if($null -ne $context){$directory=Join-Path $context.RepositoryRoot '.superpowers/sdd/2026-09-06-p6-2-local-isolation-rehearsal/execution';[void][IO.Directory]::CreateDirectory($directory);$path=Join-Path $directory ($context.Receipt.RunId+'.json');[IO.File]::WriteAllText($path,($result|ConvertTo-Json -Depth 10))}
    return $result
}
