param([string] $Mode = 'Plan', [string] $ConfirmationToken = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Task1A在加载库、预检、读取token之前拒绝Execute，不存在启动资源的分支。
if ($Mode -ceq 'Execute') {
    [Console]::Out.WriteLine('P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_EXECUTE_NOT_IMPLEMENTED ACTIONS=0')
    exit 1
}
if ($args.Count -ne 0) {
    [Console]::Out.WriteLine('P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_ARGUMENT_INVALID ACTIONS=0')
    exit 1
}
if ($Mode -cne 'Plan') {
    [Console]::Out.WriteLine('P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_MODE_INVALID ACTIONS=0')
    exit 1
}
try {
    . (Join-Path $PSScriptRoot 'p6-composite-isolation-lib.ps1')
    $root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $plan=Get-P6IsolationPlan $root
    [Console]::Out.WriteLine(('P6_REHEARSAL_STATUS=PLAN ACTIONS=0 EXECUTABLE=false FINGERPRINT={0}' -f $plan.Fingerprint))
    [Console]::Out.WriteLine(('DEPENDENCIES={0} LOCATION={1} BOUNDARY={2}' -f $plan.Dependencies,$plan.RunLocation,$plan.Boundary))
    exit 0
} catch {
    $code=[string]$_.Exception.Message
    if ($code -cnotin @('REHEARSAL_HEAD_MISMATCH','REHEARSAL_ROOT_MISMATCH','REHEARSAL_PATH_INVALID','REHEARSAL_PREFLIGHT_FAILED')) { $code='REHEARSAL_PREFLIGHT_FAILED' }
    [Console]::Out.WriteLine(('P6_REHEARSAL_STATUS=FAIL CODE={0} ACTIONS=0' -f $code))
    exit 1
}
