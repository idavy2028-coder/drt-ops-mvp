$ErrorActionPreference='Stop'
$ops=Split-Path -Parent $PSScriptRoot
. (Join-Path $ops 'p6-composite-isolation-lib.ps1')
Initialize-P6StreamDrain
foreach($case in @(
    @('java.net.BindException: DO_NOT_EXPORT','PORT_BIND'),
    @('org.h2.jdbc.JdbcSQLNonTransientConnectionException: DO_NOT_EXPORT','DATABASE'),
    @('APPLICATION FAILED TO START DO_NOT_EXPORT','APPLICATION_START_FAILED'),
    @('java.lang.IllegalStateException: DO_NOT_EXPORT','JAVA_EXCEPTION'),
    @('ordinary DO_NOT_EXPORT output','NONE')
)) {
    $bytes=[Text.Encoding]::UTF8.GetBytes($case[0]);$a=New-Object IO.MemoryStream(,$bytes);$b=New-Object IO.MemoryStream
    $out=New-Object IO.StreamReader($a);$err=New-Object IO.StreamReader($b)
    try {$drain=New-Object P6NativeStreamDrain($out,$err,$true);if(-not $drain.Wait(3000)){throw 'DRAIN_TIMEOUT'}
        if($drain.StartupExceptionKind -cne $case[1]){throw 'STARTUP_CLASSIFICATION_MISSING'}
    }finally{$out.Dispose();$err.Dispose()}
}
. (Join-Path $ops 'p6-composite-isolation-pipeline.ps1')
function Assert-P6CurrentResourceReceipt($Context){throw (New-Object ComponentModel.Win32Exception(5,'DO_NOT_EXPORT'))}
$context=[pscustomobject]@{Evidence=(New-Object 'Collections.Generic.List[object]')}
$result=Start-P6HeldResource $context GW
if($result.Status -cne 'FAILED' -or $context.Evidence.Count -ne 1 -or $context.Evidence[0].ExceptionKind -cne 'WIN32' -or ($context.Evidence|ConvertTo-Json -Compress).Contains('DO_NOT_EXPORT')){throw 'START_FAILURE_DIAGNOSTIC_MISSING'}
function Assert-P6CurrentResourceReceipt($Context){
    $inner=New-Object ComponentModel.Win32Exception(5,'DO_NOT_EXPORT')
    throw (New-Object Management.Automation.MethodInvocationException('DO_NOT_EXPORT',$inner))
}
$context=[pscustomobject]@{Evidence=(New-Object 'Collections.Generic.List[object]')}
$result=Start-P6HeldResource $context GW
if($result.Status -cne 'FAILED' -or $context.Evidence[0].ExceptionKind -cne 'WIN32'){throw 'WRAPPED_START_EXCEPTION_MISCLASSIFIED'}
'GW_STARTUP_TESTS=PASS COUNT=7'
