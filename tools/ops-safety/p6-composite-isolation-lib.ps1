# Task 1A：纯安全合同与只读Plan；不创建演练资源、不启动服务、不删除文件。
Set-StrictMode -Version Latest

function Get-P6NativeToolSpec {
    param([string] $Action,$Receipt,[string] $RunParent,$Marker,[hashtable] $Secrets)
    if($Action -cnotin @('INITDB','CREATE_MIGRATION_DB','CREATE_LIVE_DB','ENABLE_MIGRATION_POSTGIS','ENABLE_LIVE_POSTGIS','PG_STOP','PG_PROBE')){throw 'REHEARSAL_NATIVE_ACTION_INVALID'}
    $root=Assert-P6Receipt $Receipt $RunParent $Marker
    Assert-P6NativePathLength (Join-Path $root 'secrets/pg-password.txt')
    $password=Get-P6Field $Secrets 'DbPassword'
    if($password -isnot [string] -or $password -cnotmatch '^[A-Za-z0-9_-]{32,128}$'){throw 'REHEARSAL_NATIVE_CREDENTIAL_INVALID'}
    $pg='C:\Program Files\PostgreSQL\17\bin'
    $environment=Get-P6ChildEnvironment $root
    $environment.PGPASSWORD=$password
    $arguments=@();$tool=''
    switch -CaseSensitive($Action){
        'INITDB' {$tool='initdb.exe';$arguments=@('-D',$Receipt.PgData,'-U','composite','--encoding=UTF8','--auth-host=scram-sha-256','--auth-local=scram-sha-256',('--pwfile='+(Join-Path $root 'secrets/pg-password.txt')))}
        'CREATE_MIGRATION_DB' {$tool='createdb.exe';$arguments=@('-h','127.0.0.1','-p',[string]$Receipt.Ports[0],'-U','composite','--no-password','composite_onboard')}
        'CREATE_LIVE_DB' {$tool='createdb.exe';$arguments=@('-h','127.0.0.1','-p',[string]$Receipt.Ports[0],'-U','composite','--no-password','composite_live')}
        {$_ -cin @('ENABLE_MIGRATION_POSTGIS','ENABLE_LIVE_POSTGIS')} {
            $tool='psql.exe'
            $database=if($Action -ceq 'ENABLE_MIGRATION_POSTGIS'){'composite_onboard'}else{'composite_live'}
            $sql='CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public; DO $p6$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname=''postgis'' AND n.nspname=''public'') OR to_regtype(''public.geography'') IS NULL THEN RAISE EXCEPTION ''REHEARSAL_POSTGIS_SCHEMA_INVALID''; END IF; END $p6$;'
            $arguments=@('-X','--no-password','-h','127.0.0.1','-p',[string]$Receipt.Ports[0],'-U','composite','-d',$database,'-v','ON_ERROR_STOP=1','-c',$sql)
        }
        'PG_STOP' {$tool='pg_ctl.exe';$arguments=@('-D',$Receipt.PgData,'-m','fast','-w','-t','60','stop')}
        'PG_PROBE' {$tool='psql.exe';$arguments=@('-X','--no-password','-h','127.0.0.1','-p',[string]$Receipt.Ports[0],'-U','composite','-d','composite_live','-v','ON_ERROR_STOP=1','-A','-t','-c','SELECT 1')}
    }
    return [pscustomobject]@{Action=$Action;FileName=(Join-Path $pg $tool);Arguments=$arguments;WorkingDirectory=$root;Environment=$environment}
}
function Read-P6SystemObservation {
    param([string] $Kind,[int] $ProcessId,[scriptblock] $Query=$null)
    try {
        if($Kind -cnotin @('PROCESS','LISTENERS') -or ($Kind -ceq 'PROCESS' -and $ProcessId -le 0)){throw 'invalid'}
        if($null -eq $Query){
            $Query={param($namespace,$class,$filter,$seconds) Get-CimInstance -Namespace $namespace -ClassName $class -Filter $filter -OperationTimeoutSec $seconds -ErrorAction Stop}
        }
        if($Kind -ceq 'LISTENERS'){
            $rows=@(& $Query 'root/StandardCimv2' 'MSFT_NetTCPConnection' 'State = 2' 3)
            $items=@(foreach($row in $rows){
                [pscustomobject]@{ObservationSucceeded=$true;LocalAddress=(Get-P6Field $row 'LocalAddress');LocalPort=(Get-P6Field $row 'LocalPort');OwningProcess=(Get-P6Field $row 'OwningProcess')}
            })
            return Assert-P6ListenerEvidence ([pscustomobject]@{Succeeded=$true;Items=$items})
        }
        $rows=@(& $Query 'root/cimv2' 'Win32_Process' ('ProcessId = '+$ProcessId) 3)
        if($rows.Count -eq 0){return [pscustomobject]@{ObservationSucceeded=$true;Exists=$false;Pid=$ProcessId;Process=$null}}
        if($rows.Count -ne 1){throw 'invalid'}
        $row=$rows[0]
        if((Get-P6Field $row 'ProcessId') -ne $ProcessId -or (Get-P6Field $row 'CreationDate') -isnot [datetime]){throw 'invalid'}
        foreach($field in @('ExecutablePath','CommandLine')){if((Get-P6Field $row $field) -isnot [string] -or [string]::IsNullOrWhiteSpace($row.$field)){throw 'invalid'}}
        $observed=[pscustomobject]@{Pid=$ProcessId;StartTimeUtc=([DateTimeOffset]$row.CreationDate.ToUniversalTime()).ToString('o');ExecutablePath=$row.ExecutablePath;CommandLine=$row.CommandLine}
        # Win32_Process不提供cwd。这里不从receipt或StartInfo补成“实时观测”。
        return [pscustomobject]@{ObservationSucceeded=$true;Exists=$true;Pid=$ProcessId;Process=$observed}
    }catch{throw 'REHEARSAL_SYSTEM_OBSERVATION_FAILED'}
}
function Get-P6ChildEnvironment {
    param([string] $RunDirectory)
    # 清空继承环境后只传OS运行必需路径，PG/Java/Maven业务键须在专用adapter内明确添加。
    $system=[Environment]::GetFolderPath('Windows')
    if($system -cnotmatch '^[A-Za-z]:\\[^:]+$'){throw 'REHEARSAL_ENVIRONMENT_INVALID'}
    return @{SystemRoot=$system;WINDIR=$system;PATH=($system+'\System32;'+$system+';C:\Program Files\PostgreSQL\17\bin');TEMP=(Join-Path $RunDirectory 'secrets');TMP=(Join-Path $RunDirectory 'secrets');PGCONNECT_TIMEOUT='3';PGAPPNAME='p6-isolation-rehearsal'}
}
function Invoke-P6BoundedNativeTool {
    param([string] $Action,$Receipt,[string] $RunParent,$Marker,[hashtable] $Secrets,[int] $TimeoutMilliseconds=30000,[scriptblock] $ProcessFactory=$null)
    # pg_ctl stop只能由专用所有权stop入口调用；公共短命工具入口不可请求停止服务。
    if($Action -ceq 'PG_STOP'){throw 'REHEARSAL_NATIVE_ACTION_INVALID'}
    $spec=Get-P6NativeToolSpec $Action $Receipt $RunParent $Marker $Secrets
    return Invoke-P6BoundedChild $spec $TimeoutMilliseconds $ProcessFactory
}
function Protect-P6RunAcl {
    param([string] $RunDirectory)
    try{
        if([IO.Path]::GetFileName($RunDirectory) -cnotmatch '^native-[a-f0-9]{32}$'){throw 'invalid'}
        Assert-P6ChildPath ([IO.Path]::GetDirectoryName($RunDirectory)) $RunDirectory -MustExist | Out-Null
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl=New-Object Security.AccessControl.DirectorySecurity
        $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
        $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $RunDirectory -AclObject $acl -ErrorAction Stop
        Assert-P6PrivateAcl $RunDirectory
    }catch{throw 'REHEARSAL_ACL_UNPROVEN'}
}
function Assert-P6NativePathLength {
    param([string] $Path)
    if($Path.Length -gt 240){throw 'REHEARSAL_PATH_TOO_LONG'}
}
function Get-P6NativeRunParent {
    param([string] $RepositoryRoot)
    $parent=Assert-P6ChildPath $RepositoryRoot (Join-Path $RepositoryRoot '.tmp/p6iso')
    Assert-P6NativePathLength (Join-Path $parent ('native-'+('a'*32)+'/secrets/pg-password.txt'))
    return $parent
}
function Test-P6ExecutablePath {
    param($Actual,$Expected)
    try {
        foreach($path in @($Actual,$Expected)) {
            # 只接受本机盘符绝对路径；不解析链接，也不以 leaf/contains 代替身份。
            if($path -isnot [string] -or $path -cnotmatch '^[A-Za-z]:\\[^:]+$' -or $path.IndexOfAny([IO.Path]::GetInvalidPathChars()) -ge 0){return $false}
        }
        return [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetFullPath($Actual),[IO.Path]::GetFullPath($Expected))
    }catch{return $false}
}
function Assert-P6LaunchEvidence {
    param($Recorded,$Observed,$LaunchEvidence)
    try{
        $process=Get-P6Field $LaunchEvidence 'Process'
        if($process -isnot [Diagnostics.Process] -or $process.HasExited -or $process.Id -ne $Recorded.Pid -or $process.Id -ne $Observed.Pid){throw 'invalid'}
        $ticks=$process.StartTime.ToUniversalTime().Ticks
        if((Get-P6Field $LaunchEvidence 'StartTicks') -ne $ticks){throw 'invalid'}
        foreach($value in @($Recorded.StartTimeUtc,$Observed.StartTimeUtc)){
            $time=[DateTimeOffset]::ParseExact($value,'o',[Globalization.CultureInfo]::InvariantCulture).UtcTicks
            # CIM时间精度为微秒；原Process对象自身的100ns ticks仍与启动收据完全一致。
            if([Math]::Floor([decimal]$time/10) -ne [Math]::Floor([decimal]$ticks/10)){throw 'invalid'}
        }
        if($Recorded.WorkingDirectory -cne (Get-P6Field $LaunchEvidence 'WorkingDirectory') -or $Recorded.WorkingDirectory -cne $process.StartInfo.WorkingDirectory){throw 'invalid'}
        # C2a launch-request evidence adds no stop authority: recheck it against the original StartInfo.
        if($null -ne (Get-P6Field $LaunchEvidence 'LaunchExecutablePath')){
            if(-not (Test-P6ExecutablePath $LaunchEvidence.LaunchExecutablePath (Get-P6Field $Recorded 'LaunchExecutablePath')) -or -not (Test-P6ExecutablePath $LaunchEvidence.LaunchExecutablePath $Recorded.ExecutablePath) -or -not (Test-P6ExecutablePath $process.StartInfo.FileName $LaunchEvidence.LaunchExecutablePath)){throw 'invalid'}
        }
        if(-not (Test-P6ExecutablePath $process.MainModule.FileName $Observed.ExecutablePath) -or -not (Test-P6ExecutablePath $Recorded.ExecutablePath $Observed.ExecutablePath)){throw 'invalid'}
    }catch{throw 'REHEARSAL_LAUNCH_UNPROVEN'}
}
function Start-P6NativeCluster {
    param($Context,[hashtable] $Boundary=$null)
    if($null -eq $Boundary){$Boundary=Get-P6NativeBoundary}
    $stage='INITDB';$cleanup='NOT_STARTED'
    try{
        foreach($key in @('Tool','Start','Ready','Stop')){if($Boundary[$key] -isnot [scriptblock]){throw 'invalid'}}
        $result=& $Boundary.Tool 'INITDB' $Context
        if((Get-P6Field $result 'Status') -cne 'EXITED'){throw 'failed'}
        $stage='START';$result=& $Boundary.Start $Context
        $Context.Ticket=Get-P6Field $result 'Ticket'
        if((Get-P6Field $result 'Succeeded') -isnot [bool] -or -not $result.Succeeded -or $null -eq $Context.Ticket){throw 'failed'}
        $stage='READY';$ready=& $Boundary.Ready $Context
        if($ready -isnot [bool] -or -not $ready){throw 'failed'}
        foreach($stage in @('CREATE_MIGRATION_DB','CREATE_LIVE_DB','PG_PROBE')){
            $result=& $Boundary.Tool $stage $Context
            if((Get-P6Field $result 'Status') -cne 'EXITED'){throw 'failed'}
        }
        $Context.Status='READY'
        return [pscustomobject]@{Status='READY';Stage='PG_PROBE';Code='REHEARSAL_PG_READY'}
    }catch{
        if($null -ne $Context.Ticket){$stopped=Stop-P6NativeCluster $Context $Boundary;$cleanup=$stopped.Status}
        $Context.Status='FAILED'
        return [pscustomobject]@{Status='FAILED';Stage=$stage;Code='REHEARSAL_PG_STAGE_FAILED';Cleanup=$cleanup}
    }
}
function Stop-P6NativeCluster {
    param($Context,[hashtable] $Boundary=$null)
    if($null -eq $Boundary){$Boundary=Get-P6NativeBoundary}
    try{
        if($null -eq $Context.Ticket -or $Boundary.Stop -isnot [scriptblock]){throw 'invalid'}
        $result=& $Boundary.Stop $Context
        if((Get-P6Field $result 'Status') -cnotin @('STOPPED','RETAINED')){throw 'invalid'}
        return [pscustomobject]@{Status=$result.Status;Code=$(if($result.Status -ceq 'STOPPED'){'REHEARSAL_STOP_CONFIRMED'}else{'REHEARSAL_PROCESS_UNPROVEN'})}
    }catch{return [pscustomobject]@{Status='RETAINED';Code='REHEARSAL_PROCESS_UNPROVEN'}}
}
function Get-P6PostgresStartSpec {
    param($Receipt,[string] $RunParent,$Marker)
    $root=Assert-P6Receipt $Receipt $RunParent $Marker
    Assert-P6NativePathLength $Receipt.PgData
    return [pscustomobject]@{FileName='C:\Program Files\PostgreSQL\17\bin\postgres.exe';WorkingDirectory=$root;Arguments=@('-D',$Receipt.PgData,'-h','127.0.0.1','-p',[string]$Receipt.Ports[0]);Environment=(Get-P6ChildEnvironment $root)}
}
function Initialize-P6StreamDrain {
    if($null -ne ('P6NativeStreamDrain' -as [type])){return}
    # CLR任务只丢弃原始字符、保留数量/失败标记，不依赖后台PowerShell runspace。
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
public sealed class P6NativeStreamDrain {
    private int startupKind;
    public string StartupExceptionKind { get { return new[] { "NONE", "JAVA_EXCEPTION", "APPLICATION_START_FAILED", "DATABASE", "PORT_BIND" }[Volatile.Read(ref startupKind)]; } }
    private void Classify(string text) {
        int kind = text.Contains("java.net.BindException") ? 4 :
            text.Contains("org.h2.jdbc.JdbcSQL") || text.Contains("org.flywaydb.core.api.FlywayException") || text.Contains("java.sql.SQLException") ? 3 :
            text.Contains("APPLICATION FAILED TO START") ? 2 :
            System.Text.RegularExpressions.Regex.IsMatch(text, @"\b(?:java|org|com)\.[A-Za-z0-9_.$]*(?:Exception|Error)\b") ? 1 : 0;
        int old;
        do { old = Volatile.Read(ref startupKind); if (kind <= old) return; }
        while (Interlocked.CompareExchange(ref startupKind, kind, old) != old);
    }
    private long count;
    private int failed;
    private readonly Task[] tasks;
    private readonly bool classifyStartup;
    public long Count { get { return Interlocked.Read(ref count); } }
    public bool Failed { get { return Volatile.Read(ref failed) != 0; } }
    public P6NativeStreamDrain(StreamReader stdout, StreamReader stderr) : this(stdout, stderr, false) { }
    public P6NativeStreamDrain(StreamReader stdout, StreamReader stderr, bool classifyStartup) {
        this.classifyStartup = classifyStartup;
        tasks = new[] { Drain(stdout), Drain(stderr) };
    }
    private Task Drain(StreamReader input) {
        return Task.Run(async () => {
            try { var buffer = new char[2048]; int n; string tail = "";
                while ((n = await input.ReadAsync(buffer, 0, buffer.Length)) > 0) {
                    Interlocked.Add(ref count, n);
                    if (classifyStartup) {
                        string text = tail + new string(buffer, 0, n); Classify(text);
                        tail = text.Substring(Math.Max(0, text.Length - 256));
                    }
                }
            } catch { Interlocked.Exchange(ref failed, 1); }
        });
    }
    public bool Wait(int milliseconds) { try { return Task.WaitAll(tasks, milliseconds); } catch { return false; } }
}
// Fixed, typed in-process state only. No delegates, PowerShell getters, CIM or network I/O.
public sealed class P6QuickGuardState {
    public readonly System.Diagnostics.Stopwatch Clock;
    public long LastElapsedMilliseconds;
    public System.Diagnostics.Process[] Processes = new System.Diagnostics.Process[0];
    public P6NativeStreamDrain[] Drains = new P6NativeStreamDrain[0];
    public P6QuickGuardState() : this(System.Diagnostics.Stopwatch.StartNew()) { }
    public P6QuickGuardState(System.Diagnostics.Stopwatch clock) { Clock = clock; }
    public static string CheckState(P6QuickGuardState state) {
        try {
            if (state == null) return "REHEARSAL_HEALTH_UNPROVEN";
            if (state.Clock == null || !state.Clock.IsRunning) return "REHEARSAL_CLOCK_INVALID";
            long now = state.Clock.ElapsedMilliseconds;
            if (now < state.LastElapsedMilliseconds || state.LastElapsedMilliseconds < 0) return "REHEARSAL_CLOCK_INVALID";
            state.LastElapsedMilliseconds = now;
            if (now >= 1800000) return "REHEARSAL_TOTAL_DEADLINE";
            if (state.Processes == null || state.Drains == null) return "REHEARSAL_HEALTH_UNPROVEN";
            foreach (var process in state.Processes) if (process == null || process.HasExited) return "REHEARSAL_HEALTH_UNPROVEN";
            foreach (var drain in state.Drains) if (drain == null || drain.Failed || drain.Count > 65536) return "REHEARSAL_HEALTH_UNPROVEN";
            return "";
        } catch { return "REHEARSAL_HEALTH_UNPROVEN"; }
    }
}
'@ -ErrorAction Stop | Out-Null
}
function Write-P6ReceiptSnapshot {
    param($Context)
    $root=Assert-P6Receipt $Context.Receipt $Context.RunParent (Read-P6OwnerMarker $Context.Receipt $Context.RunParent)
    $target=Assert-P6ChildPath $root (Join-Path $root 'receipt.json') -MustExist
    $old=[IO.File]::ReadAllText($target)|ConvertFrom-Json
    Assert-P6Receipt $old $Context.RunParent $Context.Marker | Out-Null
    if(@($old.Processes).Count -ne 0 -or @($Context.Receipt.Processes).Count -ne 1){throw 'REHEARSAL_RECEIPT_UPDATE_INVALID'}
    $stage=Join-Path $root ([guid]::NewGuid().ToString('N').Substring(0,8)+'.tmp')
    Write-P6AtomicNewFile $root $stage ([Text.Encoding]::UTF8.GetBytes(($Context.Receipt|ConvertTo-Json -Depth 20 -Compress)))
    # 同目录原子替换，不移除run/secret/data；失败则保留原收据和stage供控制器核验。
    # Windows PowerShell把null字符串绑定为空路径；使用受控备份名保留原收据，避免该绑定歧义。
    $backup=Assert-P6ChildPath $root (Join-Path $root 'receipt.previous.json')
    Assert-P6NativePathLength $backup
    if(Test-Path -LiteralPath $backup){throw 'REHEARSAL_STORAGE_EXISTS'}
    try{[IO.File]::Replace($stage,$target,$backup)}catch{throw 'REHEARSAL_RECEIPT_WRITE_FAILED'}
}
function Start-P6OwnedPostgres {
    param($Context,[scriptblock] $ProcessFactory=$null)
    $ticket=$null
    try{
        $marker=Read-P6OwnerMarker $Context.Receipt $Context.RunParent
        $spec=Get-P6PostgresStartSpec $Context.Receipt $Context.RunParent $marker
        if(@($Context.Receipt.Processes).Count -ne 0 -or (Test-Path -LiteralPath (Join-Path $Context.Receipt.PgData 'postmaster.pid'))){throw 'invalid'}
        Assert-P6PrivateAcl $spec.WorkingDirectory
        Initialize-P6StreamDrain
        $info=New-P6ProcessStartInfo $spec
        if($null -eq $ProcessFactory){$process=New-Object Diagnostics.Process;$process.StartInfo=$info}else{$process=& $ProcessFactory $info}
        if($process -isnot [Diagnostics.Process] -or -not $process.Start()){throw 'invalid'}
        $ticket=[pscustomobject]@{Process=$process;Recorded=$null;LaunchEvidence=$null;Drain=$null}
        $Context.Ticket=$ticket
        $ticket.Drain=New-Object P6NativeStreamDrain($process.StandardOutput,$process.StandardError)
        $ticks=$process.StartTime.ToUniversalTime().Ticks
        $start=(New-Object DateTimeOffset(($ticks-($ticks%10)),[TimeSpan]::Zero)).ToString('o')
        $ticket.Recorded=[pscustomobject]@{Pid=$process.Id;StartTimeUtc=$start;ExecutablePath=$process.MainModule.FileName;WorkingDirectory=$info.WorkingDirectory;RunId=$Context.Receipt.RunId;OwnerNonce=$Context.Receipt.OwnerNonce;Kind='Postgres';ArgumentMarker=$Context.Receipt.PgData}
        $ticket.LaunchEvidence=[pscustomobject]@{Process=$process;StartTicks=$ticks;WorkingDirectory=$info.WorkingDirectory}
        if(-not (Test-P6ExecutablePath $ticket.Recorded.ExecutablePath $spec.FileName)){throw 'invalid'}
        $Context.Receipt.Processes=@($ticket.Recorded)
        Write-P6ReceiptSnapshot $Context
        return [pscustomobject]@{Succeeded=$true;Ticket=$ticket;Code='REHEARSAL_PG_STARTED'}
    }catch{
        # 启动后不能在缺失PG证明时kill；保留原句柄，交专用stop再次读取pidfile/监听证据。
        return [pscustomobject]@{Succeeded=$false;Ticket=$ticket;Code='REHEARSAL_PG_START_FAILED'}
    }
}
function Read-P6PostgresOwnership {
    param($Context)
    try{
        $ticket=$Context.Ticket
        if($null -eq $ticket -or $ticket.Process -isnot [Diagnostics.Process] -or $null -eq $ticket.Recorded){throw 'invalid'}
        $marker=Read-P6OwnerMarker $Context.Receipt $Context.RunParent
        $observation=Read-P6SystemObservation 'PROCESS' $ticket.Recorded.Pid
        $listeners=Read-P6SystemObservation 'LISTENERS' 0
        if($observation.Exists){Assert-P6LaunchEvidence $ticket.Recorded $observation.Process $ticket.LaunchEvidence}
        $pidfile=Assert-P6ChildPath $Context.Receipt.RunDirectory (Join-Path $Context.Receipt.PgData 'postmaster.pid')
        $lines=@()
        # 明确不存在不同于读取异常；后者必须throw，不能伪造成空pidfile证据。
        if(Test-Path -LiteralPath $pidfile){
            if((Get-Item -LiteralPath $pidfile -ErrorAction Stop).Length -gt 8192){throw 'invalid'}
            $lines=@([IO.File]::ReadAllLines($pidfile))
        }
        return [pscustomobject]@{Succeeded=$true;Marker=$marker;ObservedProcess=$observation.Process;Listeners=$listeners;PidFileLines=$lines;LaunchEvidence=$ticket.LaunchEvidence}
    }catch{throw 'REHEARSAL_PG_UNPROVEN'}
}
function Wait-P6PostgresReady {
    param($Context)
    try{
        if($null -eq $Context.Ticket){throw 'invalid'}
        $timer=[Diagnostics.Stopwatch]::StartNew()
        while($timer.ElapsedMilliseconds -lt 15000){
            if($Context.Ticket.Process.HasExited -or $Context.Ticket.Drain.Failed -or $Context.Ticket.Drain.Count -gt 65536){throw 'invalid'}
            $evidence=Read-P6PostgresOwnership $Context
            if($evidence.PidFileLines.Count -ge 8 -and $evidence.PidFileLines[7] -cmatch '\Aready *\z'){
                Assert-P6PgIdentity $Context.Receipt $Context.Ticket.Recorded $evidence.ObservedProcess $evidence.PidFileLines $evidence.Listeners.Items $evidence.LaunchEvidence | Out-Null
                return $true
            }
            [Threading.Thread]::Sleep(100)
        }
    }catch{throw 'REHEARSAL_PG_UNPROVEN'}
    throw 'REHEARSAL_PG_UNPROVEN'
}
function Assert-P6PgStopCommandResult($Result) {
    # This only admits the command result to the existing wait + independent exit proof.
    # It never certifies that PostgreSQL stopped, and never admits an unowned/retained tool.
    if((Get-P6Field $Result 'Retained') -isnot [bool] -or $Result.Retained -or (Get-P6Field $Result 'ExitCode') -isnot [int]){throw 'REHEARSAL_STOP_FAILED'}
    if($Result.Status -ceq 'EXITED' -and $Result.ExitCode -eq 0 -and $Result.Code -ceq 'REHEARSAL_NATIVE_EXIT_CONFIRMED'){return $false}
    if($Result.Status -ceq 'FAILED' -and $Result.ExitCode -ne 0 -and $Result.Code -ceq 'REHEARSAL_NATIVE_EXIT_FAILED'){return $true}
    throw 'REHEARSAL_STOP_FAILED'
}
function Stop-P6NativePostgres {
    param($Context)
    try{
        if($null -eq $Context.Ticket -or $Context.Ticket.Process -isnot [Diagnostics.Process]){throw 'invalid'}
        $read={param($record,$timeout) Read-P6PostgresOwnership $Context}.GetNewClosure()
        $stop={param($record)
            $spec=Get-P6NativeToolSpec 'PG_STOP' $Context.Receipt $Context.RunParent (Read-P6OwnerMarker $Context.Receipt $Context.RunParent) $Context.Secrets
            $result=Invoke-P6BoundedChild $spec 70000
            Assert-P6PgStopCommandResult $result|Out-Null
        }.GetNewClosure()
        $wait={param($record,$timeout) return $Context.Ticket.Process.WaitForExit($timeout)}.GetNewClosure()
        $result=Stop-P6OwnedPostgres $Context.Receipt $Context.RunParent $Context.Ticket.Recorded $read $stop $wait
        if($result.Status -ceq 'STOPPED'){
            if(-not $Context.Ticket.Drain.Wait(5000)){return [pscustomobject]@{Status='RETAINED';Code='REHEARSAL_STOP_TIMEOUT'}}
            $Context.Ticket.Process.Dispose()
        }
        return $result
    }catch{return [pscustomobject]@{Status='RETAINED';Code='REHEARSAL_PROCESS_UNPROVEN'}}
}
function Get-P6NativeBoundary {
    return @{
        Tool={param($action,$context)
            $marker=Read-P6OwnerMarker $context.Receipt $context.RunParent
            if($action -cne 'INITDB'){[void](Wait-P6PostgresReady $context)}
            Invoke-P6BoundedNativeTool $action $context.Receipt $context.RunParent $marker $context.Secrets 60000
        }
        Start={param($context) Start-P6OwnedPostgres $context}
        Ready={param($context) Wait-P6PostgresReady $context}
        Stop={param($context) Stop-P6NativePostgres $context}
    }
}
function Assert-P6PrivateAcl {
    param([string] $Path)
    try{
        Assert-P6ChildPath ([IO.Path]::GetDirectoryName($Path)) $Path -MustExist | Out-Null
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $acl=Get-Acl -LiteralPath $Path -ErrorAction Stop
        if($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $sid){throw 'invalid'}
        $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
        if($rules.Count -eq 0){throw 'invalid'}
        foreach($rule in $rules){
            if($rule.IdentityReference.Value -cne $sid -or $rule.AccessControlType -ne 'Allow' -or ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne [Security.AccessControl.FileSystemRights]::FullControl){throw 'invalid'}
        }
        # run必须禁继承；子目录可继承已验证run的唯一用户ACL。
        if([IO.Path]::GetFileName($Path) -cmatch '^native-[a-f0-9]{32}$' -and -not $acl.AreAccessRulesProtected){throw 'invalid'}
    }catch{throw 'REHEARSAL_ACL_UNPROVEN'}
}
function Write-P6AtomicNewFile {
    param([string] $RunDirectory,[string] $Target,[byte[]] $Bytes)
    $targetPath=Assert-P6ChildPath $RunDirectory $Target
    Assert-P6NativePathLength $targetPath
    Assert-P6PrivateAcl $RunDirectory
    if(Test-Path -LiteralPath $targetPath){throw 'REHEARSAL_STORAGE_EXISTS'}
    # 同一目录内的短随机stage，避免Windows PowerShell/.NET Framework的MAX_PATH限制。
    $stage=Join-Path ([IO.Path]::GetDirectoryName($targetPath)) ([guid]::NewGuid().ToString('N').Substring(0,8)+'.tmp')
    Assert-P6NativePathLength $stage
    Assert-P6ChildPath $RunDirectory $stage | Out-Null
    $stream=$null
    try{
        $stream=New-Object IO.FileStream($stage,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None,4096,[IO.FileOptions]::WriteThrough)
        $stream.Write($Bytes,0,$Bytes.Length);$stream.Flush($true);$stream.Dispose();$stream=$null
        [IO.File]::Move($stage,$targetPath)
    }catch{throw 'REHEARSAL_STORAGE_WRITE_FAILED'}
    finally{if($null -ne $stream){$stream.Dispose()}}
}
function Write-P6OwnedStorage {
    param($Receipt,[string] $RunParent,$Marker,[hashtable] $Secrets)
    $root=Assert-P6Receipt $Receipt $RunParent $Marker
    Assert-P6PrivateAcl $root
    foreach($file in @('owner.properties','receipt.json','secrets')){if(Test-Path -LiteralPath (Join-Path $root $file)){throw 'REHEARSAL_STORAGE_EXISTS'}}
    $password=Get-P6Field $Secrets 'DbPassword'
    if($password -isnot [string] -or $password -cnotmatch '^[A-Za-z0-9_-]{32,128}$'){throw 'REHEARSAL_NATIVE_CREDENTIAL_INVALID'}
    $secretsDirectory=Assert-P6ChildPath $root (Join-Path $root 'secrets')
    [void][IO.Directory]::CreateDirectory($secretsDirectory)
    # 提权宿主默认新目录owner可能是Administrators；保持唯一用户DACL并显式归属当前用户。
    $secretAcl=Get-Acl -LiteralPath $secretsDirectory
    $secretAcl.SetOwner([Security.Principal.WindowsIdentity]::GetCurrent().User)
    Set-Acl -LiteralPath $secretsDirectory -AclObject $secretAcl -ErrorAction Stop
    Assert-P6PrivateAcl $secretsDirectory
    $lines=@(foreach($key in @('SchemaVersion','RunId','OwnerNonce','RunDirectory','CreatedAt','PgPort')){
        $value=[string]$Marker.$key
        $escaped=New-Object Text.StringBuilder
        foreach($character in $value.ToCharArray()){
            if($character -eq '\'){[void]$escaped.Append('\\')}
            elseif([int]$character -lt 32 -or [int]$character -gt 126){[void]$escaped.Append(('\u{0:x4}' -f [int]$character))}
            else{[void]$escaped.Append($character)}
        }
        $key+'='+$escaped.ToString()
    })
    Write-P6AtomicNewFile $root (Join-Path $root 'owner.properties') ([Text.Encoding]::ASCII.GetBytes(($lines -join "`n")+"`n"))
    Write-P6AtomicNewFile $root (Join-Path $root 'receipt.json') ([Text.Encoding]::UTF8.GetBytes(($Receipt|ConvertTo-Json -Depth 20 -Compress)))
    Write-P6AtomicNewFile $root (Join-Path $secretsDirectory 'pg-password.txt') ([Text.Encoding]::ASCII.GetBytes($password))
    return [pscustomobject]@{Status='STORED';Code='REHEARSAL_STORAGE_CONFIRMED'}
}
function Read-P6OwnerMarker {
    param($Receipt,[string] $RunParent)
    try{
        $root=Assert-P6ChildPath $RunParent $Receipt.RunDirectory -MustExist
        Assert-P6PrivateAcl $root
        $path=Assert-P6ChildPath $root (Join-Path $root 'owner.properties') -MustExist
        $bytes=[IO.File]::ReadAllBytes($path)
        if($bytes.Length -gt 8192 -or @($bytes|Where-Object{$_ -gt 127 -or $_ -eq 0}).Count -gt 0){throw 'invalid'}
        $values=@{}
        foreach($line in ([Text.Encoding]::ASCII.GetString($bytes) -split "`n")){
            if($line -ceq ''){continue}
            if($line -cnotmatch '^([A-Za-z]+)=(.*)$' -or $values.ContainsKey($Matches[1])){throw 'invalid'}
            $key=$Matches[1];$value=$Matches[2]
            if($value -cnotmatch '^(?:[^\\]|\\\\|\\u[0-9a-f]{4})*$'){throw 'invalid'}
            $values[$key]=[regex]::Unescape($value)
        }
        if((($values.Keys|Sort-Object) -join ',') -cne 'CreatedAt,OwnerNonce,PgPort,RunDirectory,RunId,SchemaVersion'){throw 'invalid'}
        $marker=[pscustomobject]$values
        Assert-P6Receipt $Receipt $RunParent $marker | Out-Null
        return $marker
    }catch{throw 'REHEARSAL_OWNER_UNPROVEN'}
}
function New-P6ProcessStartInfo {
    param($Spec)
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=$Spec.FileName;$info.WorkingDirectory=$Spec.WorkingDirectory
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    # CommandLineToArgvW兼容的引号，绝不经cmd或拼shell操作。
    $info.Arguments=(@($Spec.Arguments | ForEach-Object {
        if($_ -match '[\x00-\x1f]'){throw 'REHEARSAL_NATIVE_ARGUMENT_INVALID'}
        '"'+([regex]::Replace([regex]::Replace([string]$_,'(\\*)"','$1$1\"'),'(\\+)$','$1$1'))+'"'
    }) -join ' ')
    $info.EnvironmentVariables.Clear()
    foreach($key in $Spec.Environment.Keys){$info.EnvironmentVariables[$key]=[string]$Spec.Environment[$key]}
    return $info
}
function Invoke-P6BoundedChild {
    [CmdletBinding()]
    param($Spec,[int] $TimeoutMilliseconds,[scriptblock] $ProcessFactory=$null,$QuickState=$null)
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $process=$null;$started=$false;$count=0;$code='REHEARSAL_NATIVE_FAILED';$ownedFactory=($null -eq $ProcessFactory)
    $drain=$null;$retained=$false;$status='FAILED';$exitCode=-1;$cleanup='NOT_STARTED'
    $launchElapsed=-1;$drainElapsed=-1;$cleanupWait=0
    try {
        # Only the fixed PostgreSQL stop tool gets 10s overhead beyond its 60s wait.
        $maxTimeout=if((Get-P6Field $Spec 'Action') -ceq 'PG_STOP' -and (Get-P6Field $Spec 'FileName') -ceq 'C:\Program Files\PostgreSQL\17\bin\pg_ctl.exe'){70000}else{60000}
        if($TimeoutMilliseconds -lt 100 -or $TimeoutMilliseconds -gt $maxTimeout){throw 'invalid'}
        Initialize-P6StreamDrain
        if($null -ne $QuickState -and $QuickState -isnot [P6QuickGuardState]){throw 'invalid'}
        if($timer.ElapsedMilliseconds -ge $TimeoutMilliseconds){$code='REHEARSAL_NATIVE_TIMEOUT';throw 'timeout'}
        if($null -ne $QuickState){$guardCode=[P6QuickGuardState]::CheckState([P6QuickGuardState]$QuickState);if($guardCode -ne ''){$code=$guardCode;throw 'guard'}}
        $info=New-P6ProcessStartInfo $Spec
        # The factory is a test seam, not a claim to preempt arbitrary caller code.
        if($ownedFactory){$process=New-Object Diagnostics.Process;$process.StartInfo=$info}else{$process=& $ProcessFactory $info}
        if($timer.ElapsedMilliseconds -ge $TimeoutMilliseconds){$code='REHEARSAL_NATIVE_TIMEOUT';throw 'timeout'}
        if($process -isnot [Diagnostics.Process] -or -not $process.Start()){throw 'invalid'}
        $started=$true
        $launchElapsed=$timer.ElapsedMilliseconds
        # Once started, establish autonomous drains before any deadline exit can retain the child.
        try{$drain=[P6NativeStreamDrain]::new($process.StandardOutput,$process.StandardError)}
        catch{$code='REHEARSAL_NATIVE_DRAIN_FAILED';throw 'drain'}
        $drainElapsed=$timer.ElapsedMilliseconds
        while($true){
            if($timer.ElapsedMilliseconds -ge $TimeoutMilliseconds){$code='REHEARSAL_NATIVE_TIMEOUT';throw 'timeout'}
            if($null -ne $QuickState){$guardCode=[P6QuickGuardState]::CheckState([P6QuickGuardState]$QuickState);if($guardCode -ne ''){$code=$guardCode;throw 'guard'}}
            $count=$drain.Count
            if($count -gt 65536){$code='REHEARSAL_NATIVE_OUTPUT_LIMIT';throw 'limit'}
            if($drain.Failed){throw 'drain'}
            if($process.HasExited -and $drain.Wait(0)){break}
            $remaining=$TimeoutMilliseconds-[int]$timer.ElapsedMilliseconds
            if($remaining -le 0){$code='REHEARSAL_NATIVE_TIMEOUT';throw 'timeout'}
            [void]$process.WaitForExit([Math]::Min(10,$remaining))
            $remaining=$TimeoutMilliseconds-[int]$timer.ElapsedMilliseconds
            if($process.HasExited -and $remaining -gt 0){[Threading.Thread]::Sleep([Math]::Min(1,$remaining))}
        }
        $exitCode=$process.ExitCode
        if($exitCode -ne 0){$code='REHEARSAL_NATIVE_EXIT_FAILED'}else{$status='EXITED';$code='REHEARSAL_NATIVE_EXIT_CONFIRMED'}
    }catch{}finally{
        if($started){
            try{
                if($null -eq $drain){throw 'drain'}
                $remaining=$TimeoutMilliseconds-[int]$timer.ElapsedMilliseconds
                if($remaining -le 0){throw 'deadline'}
                if(-not $process.HasExited){
                    $process.Kill()
                    $remaining=$TimeoutMilliseconds-[int]$timer.ElapsedMilliseconds
                    if($remaining -le 0){throw 'stop'}
                    $cleanupWait+=$remaining
                    if(-not $process.WaitForExit($remaining)){throw 'stop'}
                }
                $remaining=$TimeoutMilliseconds-[int]$timer.ElapsedMilliseconds
                if($remaining -le 0 -or -not $process.HasExited){throw 'stop'}
                if($null -ne $drain){$cleanupWait+=$remaining;if(-not $drain.Wait($remaining)){throw 'stop'}}
                $cleanup='STOPPED'
            }catch{$retained=$true;$status='FAILED';$cleanup='RETAINED';if($timer.ElapsedMilliseconds -ge $TimeoutMilliseconds -and $code -cne 'REHEARSAL_NATIVE_DRAIN_FAILED'){$code='REHEARSAL_NATIVE_TIMEOUT'}}
        }
        # An unknown stop transfers the original handle and autonomous drain to the caller.
        if($ownedFactory -and $null -ne $process -and -not $retained){$process.Dispose()}
    }
    $ticket=if($retained){[pscustomobject]@{Process=$process;Drain=$drain}}else{$null}
    return [pscustomobject]@{Status=$status;Code=$code;ExitCode=$exitCode;OutputCharacters=[Math]::Min($count,65536);Retained=$retained;Cleanup=$cleanup;HeldTicket=$ticket;ElapsedMilliseconds=$timer.ElapsedMilliseconds;LaunchElapsedMilliseconds=$launchElapsed;DrainElapsedMilliseconds=$drainElapsed;CleanupWaitMilliseconds=$cleanupWait}
}

function Get-P6Field {
    param($Value, [string] $Name)
    if ($null -eq $Value) { return $null }
    if ($Value -is [Collections.IDictionary]) { return $Value[$Name] }
    $property=$Value.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
function Get-P6Sha256 {
    param([byte[]] $Bytes)
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Get-P6GitValue {
    param([string] $RepositoryRoot, [string] $Operation)
    # 仅固定只读命令；不拼接调用者的命令/URL，输出仅在内存消费。
    $arguments=switch -CaseSensitive ($Operation) {
        'Head' { 'rev-parse --verify HEAD' }
        'Branch' { 'branch --show-current' }
        'Root' { 'rev-parse --show-toplevel' }
        'TrackedStatus' { 'status --porcelain=v1 --untracked-files=no' }
        'FullStatus' { 'status --porcelain=v1 --untracked-files=all' }
        'TrackedTools' { 'ls-files -- tools/ops-safety/Invoke-P6CompositeIsolationRehearsal.ps1 tools/ops-safety/p6-composite-isolation-lib.ps1 tools/ops-safety/p6-composite-isolation-pipeline.ps1 tools/ops-safety/fixtures/P6CompositeFlywayTool.java tools/ops-safety/fixtures/P6CompositeWireHarness.java tools/ops-safety/fixtures/P6CompositeWireHarnessContractTest.java tools/ops-safety/Invoke-Task12SafetyGate.ps1 tools/ops-safety/task12-safety-lib.ps1 tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1 tools/ops-safety/tests/p6-composite-isolation-pipeline.tests.ps1 docs/pilot/p6-2-local-isolation-rehearsal-runbook.md' }
        default { throw 'REHEARSAL_PREFLIGHT_FAILED' }
    }
    if($Operation -ceq 'TrackedTools'){$arguments+=' tools/ops-safety/p6-composite-real-runtime.ps1 tools/ops-safety/fixtures/P6CompositeBusinessTool.java tools/ops-safety/fixtures/P6CompositeBusinessToolContractTest.java tools/ops-safety/tests/p6-composite-real-runtime.tests.ps1'}
    $psi=New-Object Diagnostics.ProcessStartInfo
    $psi.FileName=(Get-Command git.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $psi.WorkingDirectory=$RepositoryRoot
    $psi.Arguments=$arguments
    $psi.UseShellExecute=$false
    $psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true
    $psi.RedirectStandardError=$true
    foreach ($key in @($psi.EnvironmentVariables.Keys)) { if ($key -match '^GIT_') { $psi.EnvironmentVariables.Remove($key) } }
    # 沙箱身份可能不是工作树拥有者。仅为此固定只读子进程信任确切目录，不改全局配置。
    $psi.EnvironmentVariables['GIT_CONFIG_COUNT']='1'
    $psi.EnvironmentVariables['GIT_CONFIG_KEY_0']='safe.directory'
    $psi.EnvironmentVariables['GIT_CONFIG_VALUE_0']=[IO.Path]::GetFullPath($RepositoryRoot)
    $psi.EnvironmentVariables['GIT_OPTIONAL_LOCKS']='0'
    $process=New-Object Diagnostics.Process
    $process.StartInfo=$psi
    try {
        if (-not $process.Start()) { throw 'REHEARSAL_PREFLIGHT_FAILED' }
        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) { throw 'REHEARSAL_PREFLIGHT_FAILED' }
        if (-not [Threading.Tasks.Task]::WaitAll(@($stdout,$stderr),5000) -or $process.ExitCode -ne 0) { throw 'REHEARSAL_PREFLIGHT_FAILED' }
        return $stdout.Result.Trim()
    } catch { throw 'REHEARSAL_PREFLIGHT_FAILED' }
    finally {
        # 只结束亲自启动的短命git句柄，不按PID重新查找/杀进程。
        try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) } } catch {}
        $process.Dispose()
    }
}
function Get-P6PostgresHostState {
    try {
        $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
        $principal=New-Object Security.Principal.WindowsPrincipal($identity)
        $admin=$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        return [pscustomobject]@{IsAdministrator=[bool]$admin;CanStartPostgres=[bool](-not $admin);IdentityDigest=(Get-P6Sha256 ([Text.Encoding]::UTF8.GetBytes($identity.User.Value)))}
    }catch{throw 'REHEARSAL_HOST_IDENTITY_UNPROVEN'}
}
function Get-P6IsolationPlan {
    param([string] $RepositoryRoot, $RepositoryState=$null)
    try {
        $root=[IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\','/')
        if ($null -eq $RepositoryState) {
            $RepositoryState=[pscustomobject]@{ Head=(Get-P6GitValue $root 'Head'); Branch=(Get-P6GitValue $root 'Branch'); Root=(Get-P6GitValue $root 'Root'); TrackedStatus=(Get-P6GitValue $root 'TrackedStatus'); FullStatus=(Get-P6GitValue $root 'FullStatus'); ToolFilesCommitted=(@((Get-P6GitValue $root 'TrackedTools') -split "`n" | Where-Object { $_ -ne '' }).Count -eq 15) }
        }
        if ((Get-P6Field $RepositoryState 'Head') -cnotmatch '^[a-f0-9]{40}$' -or (Get-P6Field $RepositoryState 'Head') -ceq ('0'*40) -or
            (Get-P6Field $RepositoryState 'Branch') -cne 'codex/p6-2-ops-safety-gates') { throw 'REHEARSAL_HEAD_MISMATCH' }
        $gitRoot=[IO.Path]::GetFullPath([string](Get-P6Field $RepositoryState 'Root')).TrimEnd('\','/')
        if (-not [StringComparer]::OrdinalIgnoreCase.Equals($gitRoot,$root)) { throw 'REHEARSAL_ROOT_MISMATCH' }
        # Windows根路径按绝对路径、去尾分隔符、大小写不敏感规范化；只把不可逆摘要加入计划。
        $rootDigest=Get-P6Sha256 ([Text.Encoding]::UTF8.GetBytes($gitRoot.ToUpperInvariant()))
        $trackedStatus=Get-P6Field $RepositoryState 'TrackedStatus'
        $clean=($trackedStatus -is [string] -and $trackedStatus -ceq '')
        $fullStatus=Get-P6Field $RepositoryState 'FullStatus'
        $cleanInputs=($fullStatus -is [string] -and $fullStatus -ceq '')
        $committed=((Get-P6Field $RepositoryState 'ToolFilesCommitted') -is [bool] -and $RepositoryState.ToolFilesCommitted)
        $hostState=Get-P6PostgresHostState
        $manifest=@('p6-isolation-plan-v4',[string]$RepositoryState.Head,[string]$RepositoryState.Branch,('root='+$rootDigest),('clean='+$clean),('cleanInputs='+$cleanInputs),('committed='+$committed))
        $manifest+=('hostCanStartPostgres='+$hostState.CanStartPostgres)
        $manifest+=('hostIdentity='+$hostState.IdentityDigest)
        # 固定清单；未来wire/runbook加入自动使指纹失效；不枚举private目录。
        foreach ($relative in @(
            'tools/ops-safety/Invoke-P6CompositeIsolationRehearsal.ps1',
            'tools/ops-safety/p6-composite-real-runtime.ps1',
            'tools/ops-safety/fixtures/P6CompositeBusinessTool.java',
            'tools/ops-safety/fixtures/P6CompositeBusinessToolContractTest.java',
            'tools/ops-safety/tests/p6-composite-real-runtime.tests.ps1',
            'tools/ops-safety/p6-composite-isolation-lib.ps1',
            'tools/ops-safety/p6-composite-isolation-pipeline.ps1',
            'tools/ops-safety/fixtures/P6CompositeFlywayTool.java',
            'tools/ops-safety/fixtures/P6CompositeWireHarness.java',
            'tools/ops-safety/fixtures/P6CompositeWireHarnessContractTest.java',
            'tools/ops-safety/Invoke-Task12SafetyGate.ps1',
            'tools/ops-safety/task12-safety-lib.ps1',
            'tools/ops-safety/tests/p6-composite-isolation-safety.tests.ps1',
            'tools/ops-safety/tests/p6-composite-isolation-pipeline.tests.ps1',
            'docs/pilot/p6-2-local-isolation-rehearsal-runbook.md')) {
            $path=Assert-P6ChildPath $root (Join-Path $root $relative)
            $hash=if ([IO.File]::Exists($path)) { Get-P6Sha256 ([IO.File]::ReadAllBytes($path)) } else { 'ABSENT' }
            $manifest+=$relative+'='+$hash
        }
        return [pscustomobject]@{
            Status='PLAN'; Actions=0; Executable=($clean -and $cleanInputs -and $committed -and $hostState.CanStartPostgres)
            HostCanStartPostgres=$hostState.CanStartPostgres;HostIsAdministrator=$hostState.IsAdministrator
            CleanTracked=$clean; CleanInputs=$cleanInputs; RepositoryRoot=$root; ToolFilesCommitted=$committed; ExecutionBlocker=$(if(-not $hostState.CanStartPostgres){'REHEARSAL_NONADMIN_HOST_REQUIRED'}elseif(-not $clean -or -not $cleanInputs){'REHEARSAL_WORKTREE_DIRTY'}elseif(-not $committed){'REHEARSAL_TOOLS_UNCOMMITTED'}else{'NONE'}); Branch=$RepositoryState.Branch; RootDigest=$rootDigest
            Fingerprint=(Get-P6Sha256 ([Text.Encoding]::UTF8.GetBytes(($manifest -join "`n"))))
            Head=$RepositoryState.Head; Dependencies='JDK21,POSTGRESQL17_POSTGIS,MAVEN,WINDOWS_POWERSHELL_5_1'; Host='WINDOWS_POWERSHELL_5_1'
            Boundary='NEW_NATIVE_CLUSTER,LOOPBACK_ONLY,NO_DOCKER,NO_CLOUD,NO_EXISTING_DB'; RunLocation='WORKTREE_TMP_P6ISO'
        }
    } catch {
        if ([string]$_.Exception.Message -cin @('REHEARSAL_HEAD_MISMATCH','REHEARSAL_ROOT_MISMATCH','REHEARSAL_PATH_INVALID')) { throw $_.Exception.Message }
        throw 'REHEARSAL_PREFLIGHT_FAILED'
    }
}
function Assert-P6ExecutionSnapshot {
    param($Plan,[string] $ConfirmationToken)
    # Old Plan is only a root hint. Never trust its HEAD/cleanliness/hash claims for an action.
    $fresh=Get-P6IsolationPlan ([string](Get-P6Field $Plan 'RepositoryRoot'))
    if (-not $fresh.CleanTracked -or -not $fresh.CleanInputs) { throw 'REHEARSAL_WORKTREE_DIRTY' }
    if (-not $fresh.ToolFilesCommitted) { throw 'REHEARSAL_TOOLS_UNCOMMITTED' }
    Assert-P6Confirmation $fresh $ConfirmationToken
    return $fresh
}
function Assert-P6Confirmation {
    param($Plan, [AllowEmptyString()][string] $ConfirmationToken)
    $fingerprint=Get-P6Field $Plan 'Fingerprint'
    if ($fingerprint -isnot [string] -or $fingerprint -cnotmatch '^[a-f0-9]{64}$' -or
        $ConfirmationToken -cnotmatch '^[a-f0-9]{64}$' -or $ConfirmationToken -cne $fingerprint) { throw 'REHEARSAL_CONFIRMATION_INVALID' }
}
function Assert-P6ChildPath {
    param([string] $Root, [string] $Path, [switch] $MustExist)
    try {
        # 拒绝UNC/设备路径/ADS/跳转片段；不能仅做路径字符串前缀比较。
        foreach ($candidate in @($Root,$Path)) {
            if ($candidate -cnotmatch '^[A-Za-z]:[\\/]' -or $candidate.Substring(2) -match '[:*?"<>|\x00-\x1f]' -or
                $candidate -match '(^|[\\/])\.{1,2}([\\/]|$)' -or $candidate -match '[. ]([\\/]|$)') { throw 'invalid' }
        }
        $canonicalRoot=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')
        $canonical=[IO.Path]::GetFullPath($Path).TrimEnd('\','/')
        if (-not $canonical.StartsWith($canonicalRoot+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'invalid' }
        $walk=$canonical
        while (-not [string]::IsNullOrEmpty($walk)) {
            if (Test-Path -LiteralPath $walk) {
                $item=Get-Item -LiteralPath $walk -Force -ErrorAction Stop
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'invalid' }
            }
            $parent=[IO.Path]::GetDirectoryName($walk)
            if ($parent -eq $walk) { break }
            $walk=$parent
        }
        if ($MustExist -and -not (Test-Path -LiteralPath $canonical)) { throw 'invalid' }
        return $canonical
    } catch { throw 'REHEARSAL_PATH_INVALID' }
}
function Assert-P6NewRunDirectory {
    param([string] $Root,[string] $Path)
    $canonical=Assert-P6ChildPath $Root $Path
    if ([IO.Path]::GetFileName($canonical) -cnotmatch '^native-[a-f0-9]{32}$' -or
        -not [StringComparer]::OrdinalIgnoreCase.Equals([IO.Path]::GetDirectoryName($canonical),[IO.Path]::GetFullPath($Root).TrimEnd('\','/'))) { throw 'REHEARSAL_PATH_INVALID' }
    if (Test-Path -LiteralPath $canonical) { throw 'REHEARSAL_RUN_EXISTS' }
    return $canonical
}
function Assert-P6LoopbackPorts {
    param($Ports,$Listeners)
    if ($null -eq $Ports -or @($Ports).Count -ne 4 -or $null -eq $Listeners) { throw 'REHEARSAL_PORT_INVALID' }
    $seen=New-Object 'Collections.Generic.HashSet[int]'
    foreach ($port in $Ports) {
        if (($port -isnot [int] -and $port -isnot [long]) -or $port -lt 1024 -or $port -gt 65535 -or -not $seen.Add([int]$port)) { throw 'REHEARSAL_PORT_INVALID' }
    }
    foreach ($listener in $Listeners) {
        $port=Get-P6Field $listener 'LocalPort'
        if ($null -eq $port -or $null -eq (Get-P6Field $listener 'LocalAddress') -or $null -eq (Get-P6Field $listener 'OwningProcess')) { throw 'REHEARSAL_PORT_INVALID' }
        if ($seen.Contains([int]$port)) { throw 'REHEARSAL_PORT_OCCUPIED' }
    }
}
function Assert-P6ListenerEvidence {
    param($Evidence)
    try {
        # 空结果也必须由一次明确成功的观测提供；未知/失败绝不能降格为空数组。
        if ($null -eq $Evidence -or (Get-P6Field $Evidence 'Succeeded') -isnot [bool] -or -not $Evidence.Succeeded -or
            $null -eq $Evidence.PSObject.Properties['Items'] -or $Evidence.Items -isnot [array]) { throw 'invalid' }
        foreach ($listener in $Evidence.Items) {
            if ($null -eq $listener -or (Get-P6Field $listener 'ObservationSucceeded') -isnot [bool] -or -not $listener.ObservationSucceeded) { throw 'invalid' }
            $port=Get-P6Field $listener 'LocalPort'
            $owner=Get-P6Field $listener 'OwningProcess'
            foreach ($number in @($port,$owner)) {
                if ($number -isnot [int16] -and $number -isnot [uint16] -and $number -isnot [int32] -and $number -isnot [uint32] -and $number -isnot [int64]) { throw 'invalid' }
            }
            if ($port -lt 1 -or $port -gt 65535 -or $owner -lt 1 -or $owner -gt [int]::MaxValue) { throw 'invalid' }
            $address=Get-P6Field $listener 'LocalAddress'
            $parsedAddress=$null
            if ($address -isnot [string] -or -not [Net.IPAddress]::TryParse($address,[ref]$parsedAddress)) { throw 'invalid' }
        }
        return $Evidence
    } catch { throw 'REHEARSAL_LISTENER_UNPROVEN' }
}
function Assert-P6Receipt {
    param($Receipt,[string] $RunParent,$Marker)
    try {
        if ((Get-P6Field $Receipt 'SchemaVersion') -ne 1 -or (Get-P6Field $Marker 'SchemaVersion') -ne 1) { throw 'invalid' }
        foreach ($field in @('RunId','OwnerNonce','RunDirectory','CreatedAt')) {
            $value=Get-P6Field $Receipt $field
            if ($value -isnot [string] -or $value -cne (Get-P6Field $Marker $field)) { throw 'invalid' }
        }
        if ($Receipt.RunId -cnotmatch '^[a-f0-9]{32}$' -or $Receipt.OwnerNonce -cnotmatch '^[a-f0-9]{64}$') { throw 'invalid' }
        # 直接检查属性，避免PowerShell把合法的空数组展开成null。
        if ($null -eq $Receipt.PSObject.Properties['Processes'] -or $null -eq $Receipt.Processes) { throw 'invalid' }
        $created=[DateTimeOffset]::ParseExact($Receipt.CreatedAt,'o',[Globalization.CultureInfo]::InvariantCulture)
        if ($created -gt [DateTimeOffset]::UtcNow) { throw 'invalid' }
        $root=Assert-P6ChildPath $RunParent $Receipt.RunDirectory -MustExist
        if (-not [StringComparer]::OrdinalIgnoreCase.Equals($root,(Join-Path $RunParent ('native-'+$Receipt.RunId)))) { throw 'invalid' }
        $data=Assert-P6ChildPath $root ([string](Get-P6Field $Receipt 'PgData'))
        if (-not [StringComparer]::OrdinalIgnoreCase.Equals($data,(Join-Path $root 'pgdata'))) { throw 'invalid' }
        if ((Get-P6Field $Receipt 'PgDatabase') -cne 'composite_live' -or (Get-P6Field $Receipt 'MigrationDatabase') -cne 'composite_onboard') { throw 'invalid' }
        Assert-P6LoopbackPorts (Get-P6Field $Receipt 'Ports') @()
        if ((Get-P6Field $Marker 'PgPort') -ne $Receipt.Ports[0]) { throw 'invalid' }
        return $root
    } catch { throw 'REHEARSAL_RECEIPT_INVALID' }
}
function ConvertFrom-P6FixedCommandLine {
    param([string] $CommandLine)
    # 只接受本runner产生的Windows规范token子集：整token引号或无引号。
    # 不把引号拼接/转义的多种等价形式放宽为“路径子串存在”，不接受额外选项。
    if($CommandLine.Length -gt 4096 -or $CommandLine -match '[\x00-\x08\x0a-\x1f]'){throw 'invalid'}
    $tokens=New-Object 'Collections.Generic.List[string]'
    $position=0
    while($position -lt $CommandLine.Length){
        while($position -lt $CommandLine.Length -and $CommandLine[$position] -cin @(' ',"`t")){$position++}
        if($position -eq $CommandLine.Length){break}
        if($CommandLine[$position] -ceq '"'){
            $end=$CommandLine.IndexOf('"',$position+1)
            if($end -lt 0){throw 'invalid'}
            $token=$CommandLine.Substring($position+1,$end-$position-1)
            # 结尾反斜杠会改变Windows引号解释；固定pgdata/选项都不需要这种形式。
            if($token.Length -eq 0 -or $token.EndsWith('\')){throw 'invalid'}
            $position=$end+1
            if($position -lt $CommandLine.Length -and $CommandLine[$position] -cnotin @(' ',"`t")){throw 'invalid'}
        }else{
            $start=$position
            while($position -lt $CommandLine.Length -and $CommandLine[$position] -cnotin @(' ',"`t")){
                if($CommandLine[$position] -ceq '"'){throw 'invalid'}
                $position++
            }
            $token=$CommandLine.Substring($start,$position-$start)
        }
        $tokens.Add($token)
    }
    return ,$tokens.ToArray()
}
function Assert-P6PostgresCommandLine {
    param([string] $CommandLine,[string] $ExecutablePath,[string] $PgData,[int] $PgPort)
    $tokens=ConvertFrom-P6FixedCommandLine $CommandLine
    if($tokens.Count -ne 7 -or ($tokens[0] -cne 'postgres.exe' -and -not (Test-P6ExecutablePath $tokens[0] $ExecutablePath))){throw 'invalid'}
    $expected=@('-D',$PgData,'-h','127.0.0.1','-p',[string]$PgPort)
    for($i=0;$i -lt $expected.Count;$i++){if($tokens[$i+1] -cne $expected[$i]){throw 'invalid'}}
}
function Assert-P6ProcessIdentity {
    param($Receipt,$Recorded,$Observed,$LaunchEvidence=$null)
    try {
        if ($null -eq $Observed -or $null -eq $Recorded) { throw 'invalid' }
        $processId=Get-P6Field $Recorded 'Pid'
        if (($processId -isnot [int] -and $processId -isnot [long]) -or $processId -le 0 -or $processId -eq $PID -or $processId -ne (Get-P6Field $Observed 'Pid')) { throw 'invalid' }
        $owned=@((Get-P6Field $Receipt 'Processes') | Where-Object { (Get-P6Field $_ 'Pid') -eq $processId })
        if ($owned.Count -ne 1) { throw 'invalid' }
        foreach ($field in @('StartTimeUtc','ExecutablePath','WorkingDirectory','RunId','OwnerNonce','Kind','ArgumentMarker')) {
            if ((Get-P6Field $Recorded $field) -cne (Get-P6Field $owned[0] $field)) { throw 'invalid' }
        }
        foreach ($field in @('StartTimeUtc')) {
            $value=Get-P6Field $Recorded $field
            if ($value -isnot [string] -or $value -cne (Get-P6Field $Observed $field)) { throw 'invalid' }
        }
        if(-not (Test-P6ExecutablePath (Get-P6Field $Recorded 'ExecutablePath') (Get-P6Field $Observed 'ExecutablePath'))){throw 'invalid'}
        if($null -ne $LaunchEvidence){Assert-P6LaunchEvidence $Recorded $Observed $LaunchEvidence}
        elseif($Recorded.WorkingDirectory -cne (Get-P6Field $Observed 'WorkingDirectory')){throw 'invalid'}
        $start=[DateTimeOffset]::ParseExact($Recorded.StartTimeUtc,'o',[Globalization.CultureInfo]::InvariantCulture)
        $created=[DateTimeOffset]::ParseExact([string]$Receipt.CreatedAt,'o',[Globalization.CultureInfo]::InvariantCulture)
        if ($start -lt $created -or $start -gt [DateTimeOffset]::UtcNow -or $Recorded.WorkingDirectory -cne $Receipt.RunDirectory -or
            (Get-P6Field $Recorded 'RunId') -cne $Receipt.RunId -or (Get-P6Field $Recorded 'OwnerNonce') -cne $Receipt.OwnerNonce) { throw 'invalid' }
        $kind=Get-P6Field $Recorded 'Kind'
        $command=Get-P6Field $Observed 'CommandLine'
        if ($command -isnot [string]) { throw 'invalid' }
        if ($kind -ceq 'Java') {
            if (-not (Test-P6ExecutablePath $Recorded.ExecutablePath 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe')) { throw 'invalid' }
            $marker='-Dp6.rehearsal.run='+$Receipt.RunId
            if ($Recorded.ArgumentMarker -cne $marker -or $command -cnotmatch ('(?:^|\s)"?'+[regex]::Escape($marker)+'"?(?:\s|$)')) { throw 'invalid' }
        } elseif ($kind -ceq 'Postgres') {
            if (-not (Test-P6ExecutablePath $Recorded.ExecutablePath 'C:\Program Files\PostgreSQL\17\bin\postgres.exe') -or $Recorded.ArgumentMarker -cne $Receipt.PgData) { throw 'invalid' }
            Assert-P6PostgresCommandLine $command $Recorded.ExecutablePath $Receipt.PgData $Receipt.Ports[0]
        } else { throw 'invalid' }
        return $true
    } catch { throw 'REHEARSAL_PROCESS_UNPROVEN' }
}
function Assert-P6PgIdentity {
    param($Receipt,$Recorded,$Observed,$PidFileLines,$Listeners,$LaunchEvidence=$null)
    try {
        Assert-P6ProcessIdentity $Receipt $Recorded $Observed $LaunchEvidence | Out-Null
        if ($Recorded.Kind -cne 'Postgres' -or $null -eq $PidFileLines -or @($PidFileLines).Count -lt 8 -or $null -eq $Listeners) { throw 'invalid' }
        $epoch=([DateTimeOffset]::Parse($Recorded.StartTimeUtc)).ToUnixTimeSeconds()
        # PostgreSQL samples its own start time after Windows process creation.
        # Permit only the adjacent later second; OS/held-handle identity stays exact above.
        $pidEpochMatches=$PidFileLines[2] -ceq [string]$epoch -or $PidFileLines[2] -ceq [string]($epoch+1)
        # PostgreSQL writes forward slashes and space-pads its fixed-width status line.
        # Only normalize separators; do not resolve aliases, dot segments, or other paths.
        if ($PidFileLines[1] -isnot [string] -or $PidFileLines[7] -isnot [string]) { throw 'invalid' }
        if ($PidFileLines[0] -cne [string]$Recorded.Pid -or $PidFileLines[1].Replace('/','\') -cne $Receipt.PgData.Replace('/','\') -or
            -not $pidEpochMatches -or $PidFileLines[3] -cne [string]$Receipt.Ports[0] -or
            $PidFileLines[5] -cne '127.0.0.1' -or $PidFileLines[7] -cnotmatch '\Aready *\z') { throw 'invalid' }
        $matching=@($Listeners | Where-Object { $_.LocalPort -eq $Receipt.Ports[0] -or $_.OwningProcess -eq $Recorded.Pid })
        if ($matching.Count -ne 1 -or $matching[0].LocalAddress -cne '127.0.0.1' -or
            $matching[0].LocalPort -ne $Receipt.Ports[0] -or $matching[0].OwningProcess -ne $Recorded.Pid) { throw 'invalid' }
        return $true
    } catch { throw 'REHEARSAL_PG_UNPROVEN' }
}
function Invoke-P6ValidatedStop {
    param($Receipt,[string] $RunParent,$Recorded,[scriptblock] $ReadOwnershipEvidence,[scriptblock] $StopProcess,[scriptblock] $WaitStopped,[string] $Kind)
    # 这个危险入口自身强制全部前置验证；直接调用它也没有跳过验证或默认成功的分支。
    try {
        if ($Kind -cnotin @('Java','Postgres') -or (Get-P6Field $Recorded 'Kind') -cne $Kind -or
            $null -eq $ReadOwnershipEvidence -or $null -eq $StopProcess -or $null -eq $WaitStopped) { throw 'invalid' }
        $evidence=& $ReadOwnershipEvidence $Recorded 5000
        if ((Get-P6Field $evidence 'Succeeded') -isnot [bool] -or -not $evidence.Succeeded -or
            $null -eq $evidence.PSObject.Properties['Marker'] -or $null -eq $evidence.PSObject.Properties['ObservedProcess'] -or
            $null -eq $evidence.PSObject.Properties['Listeners']) { throw 'invalid' }
        # 当前marker与现存路径链在stop前重验，不能只信启动时的内存receipt。
        $root=Assert-P6Receipt $Receipt $RunParent $evidence.Marker
        $listeners=Assert-P6ListenerEvidence $evidence.Listeners
        if ($Kind -ceq 'Postgres') {
            if ($null -eq $evidence.PSObject.Properties['PidFileLines'] -or $evidence.PidFileLines -isnot [array]) { throw 'invalid' }
            Assert-P6ChildPath $root $Receipt.PgData -MustExist | Out-Null
            Assert-P6PgIdentity $Receipt $Recorded $evidence.ObservedProcess $evidence.PidFileLines $listeners.Items (Get-P6Field $evidence 'LaunchEvidence') | Out-Null
        } else {
            Assert-P6ProcessIdentity $Receipt $Recorded $evidence.ObservedProcess (Get-P6Field $evidence 'LaunchEvidence') | Out-Null
        }
    } catch { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_PROCESS_UNPROVEN' } }
    try { & $StopProcess $Recorded | Out-Null }
    catch { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_STOP_FAILED' } }
    try {
        $stopped=& $WaitStopped $Recorded 5000
        if ($stopped -isnot [bool] -or -not $stopped) { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_STOP_TIMEOUT' } }
        $after=& $ReadOwnershipEvidence $Recorded 5000
        if ((Get-P6Field $after 'Succeeded') -isnot [bool] -or -not $after.Succeeded -or
            $null -eq $after.PSObject.Properties['ObservedProcess'] -or $null -ne $after.ObservedProcess) { throw 'invalid' }
        Assert-P6Receipt $Receipt $RunParent (Get-P6Field $after 'Marker') | Out-Null
        $afterListeners=Assert-P6ListenerEvidence (Get-P6Field $after 'Listeners')
        if($Kind -ceq 'Postgres' -and ($null -eq $after.PSObject.Properties['PidFileLines'] -or $after.PidFileLines -isnot [array] -or $after.PidFileLines.Count -ne 0)){throw 'invalid'}
        foreach ($listener in $afterListeners.Items) {
            if ($listener.OwningProcess -eq $Recorded.Pid -or ($Kind -ceq 'Postgres' -and $listener.LocalPort -eq $Receipt.Ports[0])) { throw 'invalid' }
        }
    } catch { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_PROCESS_UNPROVEN' } }
    return [pscustomobject]@{ Status='STOPPED'; Code='REHEARSAL_STOP_CONFIRMED' }
}
function Invoke-P6OwnedStop {
    param($Receipt,[string] $RunParent,$Recorded,[scriptblock] $ReadOwnershipEvidence,[scriptblock] $StopProcess,[scriptblock] $WaitStopped)
    # 保留名称的旧入口现在严格限Java，Postgres不能绕过专属pidfile/listener证明。
    if ((Get-P6Field $Recorded 'Kind') -cne 'Java') { return [pscustomobject]@{ Status='RETAINED'; Code='REHEARSAL_PROCESS_UNPROVEN' } }
    return Invoke-P6ValidatedStop $Receipt $RunParent $Recorded $ReadOwnershipEvidence $StopProcess $WaitStopped 'Java'
}
function Stop-P6OwnedPostgres {
    param($Receipt,[string] $RunParent,$Recorded,[scriptblock] $ReadOwnershipEvidence,[scriptblock] $StopProcess,[scriptblock] $WaitStopped)
    return Invoke-P6ValidatedStop $Receipt $RunParent $Recorded $ReadOwnershipEvidence $StopProcess $WaitStopped 'Postgres'
}
function Assert-P6RemovalProof {
    param($Receipt,[string] $RunParent,$Marker,[string] $Target,$ProcessObservations,$Listeners)
    try {
        $root=Assert-P6Receipt $Receipt $RunParent $Marker
        # A missing fixed child is not an exemption from proving the run/receipt/processes.
        $canonical=Assert-P6ChildPath $root $Target
        $listenerEvidence=Assert-P6ListenerEvidence $Listeners
        if ($canonical -cnotin @((Join-Path $root 'pgdata'),(Join-Path $root 'secrets'),(Join-Path $root 'gateway-outbox'))) { throw 'invalid' }
        # 每个收据PID必须有独立、成功、明确“不存在”的观测；空数组不能伪装全部已停。
        $owned=@($Receipt.Processes)
        if ($null -eq $ProcessObservations -or @($ProcessObservations).Count -ne $owned.Count -or $null -eq $Listeners) { throw 'invalid' }
        $seen=New-Object 'Collections.Generic.HashSet[int]'
        foreach ($entry in $owned) {
            $processId=Get-P6Field $entry 'Pid'
            if (($processId -isnot [int] -and $processId -isnot [long]) -or $processId -le 0 -or -not $seen.Add([int]$processId)) { throw 'invalid' }
            $proof=@($ProcessObservations | Where-Object { (Get-P6Field $_ 'Pid') -eq $processId })
            if ($proof.Count -ne 1 -or (Get-P6Field $proof[0] 'ObservationSucceeded') -isnot [bool] -or
                -not $proof[0].ObservationSucceeded -or (Get-P6Field $proof[0] 'Exists') -isnot [bool] -or $proof[0].Exists) { throw 'invalid' }
        }
        foreach ($listener in $listenerEvidence.Items) { if ($listener.LocalPort -in $Receipt.Ports) { throw 'invalid' } }
        $pending=New-Object 'Collections.Generic.Stack[string]'
        $pending.Push($root)
        while ($pending.Count -gt 0) {
            foreach ($item in @(Get-ChildItem -LiteralPath $pending.Pop() -Force -ErrorAction Stop)) {
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'invalid' }
                if ($item.PSIsContainer) { $pending.Push($item.FullName) }
            }
        }
        return [pscustomobject]@{ Status='PROVEN'; Target=$canonical }
    } catch { throw 'REHEARSAL_REMOVAL_UNPROVEN' }
}
