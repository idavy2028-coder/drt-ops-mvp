param([string] $Mode = 'Plan', [string] $ConfirmationToken = '')
# 必须先于库加载/JSON/任何资源动作；本轮只验证Windows PowerShell 5.1宿主。
if ($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -lt 1) {
    [Console]::Out.WriteLine('P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_POWERSHELL_UNSUPPORTED ACTIONS=0')
    exit 1
}
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($args.Count -ne 0) {
    [Console]::Out.WriteLine('P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_ARGUMENT_INVALID ACTIONS=0')
    exit 1
}
if ($Mode -cnotin @('Plan','Execute')) {
    [Console]::Out.WriteLine('P6_REHEARSAL_STATUS=FAIL CODE=REHEARSAL_MODE_INVALID ACTIONS=0')
    exit 1
}
try {
    . (Join-Path $PSScriptRoot 'p6-composite-isolation-lib.ps1')
    . (Join-Path $PSScriptRoot 'p6-composite-isolation-pipeline.ps1')
    . (Join-Path $PSScriptRoot 'p6-composite-real-runtime.ps1')
    $root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $plan=Get-P6IsolationPlan $root
    if($Mode -ceq 'Execute'){
        Assert-P6Confirmation $plan $ConfirmationToken
        if(-not $plan.HostCanStartPostgres){throw 'REHEARSAL_NONADMIN_HOST_REQUIRED'}
        Assert-P6ExecutionSnapshot $plan $ConfirmationToken|Out-Null
        $result=Invoke-P6RealIsolationExecution $plan $ConfirmationToken
        [Console]::Out.WriteLine(('P6_REHEARSAL_STATUS={0} PHASE={1} CODE={2} RETAINED={3}' -f $result.Status,$result.Phase,$result.Code,$result.Retained.ToString().ToLowerInvariant()))
        if($result.Status -ceq 'PASS'){exit 0}else{exit 1}
    }
    [Console]::Out.WriteLine(('P6_REHEARSAL_STATUS=PLAN ACTIONS=0 EXECUTABLE={0} FINGERPRINT={1}' -f $plan.Executable.ToString().ToLowerInvariant(),$plan.Fingerprint))
    [Console]::Out.WriteLine('BUSINESS_PIPELINE=REAL_LOCAL_BOUNDARY EXECUTE=SNAPSHOT_GATED')
    [Console]::Out.WriteLine(('HOST_CAN_START_POSTGRES={0} HOST_IS_ADMINISTRATOR={1}' -f $plan.HostCanStartPostgres.ToString().ToLowerInvariant(),$plan.HostIsAdministrator.ToString().ToLowerInvariant()))
    [Console]::Out.WriteLine(('HEAD={0} BRANCH={1} CLEAN_TRACKED={2} TOOLS_COMMITTED={3} BLOCKER={4} CLEAN_INPUTS={5}' -f $plan.Head,$plan.Branch,$plan.CleanTracked.ToString().ToLowerInvariant(),$plan.ToolFilesCommitted.ToString().ToLowerInvariant(),$plan.ExecutionBlocker,$plan.CleanInputs.ToString().ToLowerInvariant()))
    [Console]::Out.WriteLine(('DEPENDENCIES={0} LOCATION={1} BOUNDARY={2} HOST={3}' -f $plan.Dependencies,$plan.RunLocation,$plan.Boundary,$plan.Host))
    exit 0
} catch {
    $code=[string]$_.Exception.Message
    if ($code -cnotin @('REHEARSAL_HEAD_MISMATCH','REHEARSAL_ROOT_MISMATCH','REHEARSAL_PATH_INVALID','REHEARSAL_PREFLIGHT_FAILED','REHEARSAL_CONFIRMATION_INVALID','REHEARSAL_WORKTREE_DIRTY','REHEARSAL_TOOLS_UNCOMMITTED','REHEARSAL_NONADMIN_HOST_REQUIRED')) { $code='REHEARSAL_PREFLIGHT_FAILED' }
    [Console]::Out.WriteLine(('P6_REHEARSAL_STATUS=FAIL CODE={0} ACTIONS=0' -f $code))
    exit 1
}
