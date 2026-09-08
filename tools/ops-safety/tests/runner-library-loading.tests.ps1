[CmdletBinding()]
param([string] $RunnerSourcePath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$script:Total = 0
$script:Passed = 0
$script:Failures = New-Object System.Collections.Generic.List[string]
$script:HarnessTotal = 0
$script:HarnessPassed = 0
$script:HarnessFailures = New-Object System.Collections.Generic.List[string]
$script:OwnedProcesses = New-Object 'System.Collections.Generic.List[System.Diagnostics.Process]'
$testState = [pscustomobject]@{
    LastStartedProcessId = 0
    ProcessCleanupUnconfirmed = $false
    AclFixtures = New-Object System.Collections.Generic.List[object]
}
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRunner = if ([string]::IsNullOrWhiteSpace($RunnerSourcePath)) {
    Join-Path (Split-Path -Parent $here) 'Invoke-CloudOnboardSystemMigration.ps1'
} else {
    $RunnerSourcePath
}
$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
$testRunId = [guid]::NewGuid().ToString('N')
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ('p6-2 task11 library loading ' + $testRunId)))

function Write-TestUtf8 {
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Text)
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Quote-NativeArgument {
    param([Parameter(Mandatory)] [string] $Value)
    return '"' + $Value.Replace('\\', '\\').Replace('"', '\"') + '"'
}

function Stop-OwnedProcessBounded {
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [int] $TerminationTimeoutMilliseconds = 5000
    )
    if ($Process.HasExited) { return $true }
    try { $Process.Kill() } catch { if (-not $Process.HasExited) { return $false } }
    try { return [bool] $Process.WaitForExit($TerminationTimeoutMilliseconds) } catch { return $false }
}

function Invoke-HiddenPowerShell {
    param(
        [Parameter(Mandatory)] [string[]] $Arguments,
        [hashtable] $Environment = @{},
        [int] $TimeoutMilliseconds = 30000,
        [int] $OutputTimeoutMilliseconds = 30000,
        [int] $TerminationTimeoutMilliseconds = 5000,
        [switch] $TestFaultAfterCaptureStart
    )
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = 'powershell.exe'
    $start.Arguments = (($Arguments | ForEach-Object { Quote-NativeArgument -Value $_ }) -join ' ')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $start.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    foreach ($key in $Environment.Keys) { $start.EnvironmentVariables[[string] $key] = [string] $Environment[$key] }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    if (-not $process.Start()) { throw 'PROCESS_START_FAILED' }
    $script:OwnedProcesses.Add($process)
    $testState.LastStartedProcessId = $process.Id
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if ($TestFaultAfterCaptureStart) { throw 'TEST_FAULT_AFTER_CAPTURE_START' }
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            if (-not (Stop-OwnedProcessBounded -Process $process -TerminationTimeoutMilliseconds $TerminationTimeoutMilliseconds)) {
                throw 'PROCESS_TERMINATION_TIMEOUT'
            }
            throw 'PROCESS_TIMEOUT'
        }
        $streamsCompleted = [System.Threading.Tasks.Task]::WaitAll(@($stdoutTask, $stderrTask), $OutputTimeoutMilliseconds)
        if (-not $streamsCompleted) { throw 'OUTPUT_CAPTURE_TIMEOUT' }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdoutTask.Result
            Stderr = $stderrTask.Result
        }
    } finally {
        $cleanupConfirmed = $process.HasExited
        if (-not $cleanupConfirmed) {
            $cleanupConfirmed = Stop-OwnedProcessBounded -Process $process `
                -TerminationTimeoutMilliseconds $TerminationTimeoutMilliseconds
        }
        if ($cleanupConfirmed -and $process.HasExited) {
            [void] $script:OwnedProcesses.Remove($process)
            $process.Dispose()
        } else {
            $testState.ProcessCleanupUnconfirmed = $true
            throw 'PROCESS_CLEANUP_UNCONFIRMED'
        }
    }
}

function Assert-True {
    param([Parameter(Mandatory)] [bool] $Condition, [Parameter(Mandatory)] [string] $Code)
    if (-not $Condition) { throw $Code }
}

function Invoke-TestCase {
    param([Parameter(Mandatory)] [string] $Name, [Parameter(Mandatory)] [scriptblock] $Body)
    $script:Total++
    try {
        & $Body
        $script:Passed++
    } catch {
        $match = [regex]::Match([string] $_.Exception.Message, '[A-Z][A-Z0-9_]{3,}')
        $code = if ($match.Success) { $match.Value } else { 'UNSAFE_TEST_ERROR' }
        $script:Failures.Add($Name + ':' + $code)
    }
}

function Invoke-HarnessCase {
    param([Parameter(Mandatory)] [string] $Name, [Parameter(Mandatory)] [scriptblock] $Body)
    $script:HarnessTotal++
    try {
        & $Body
        $script:HarnessPassed++
    } catch {
        $match = [regex]::Match([string] $_.Exception.Message, '[A-Z][A-Z0-9_]{3,}')
        $code = if ($match.Success) { $match.Value } else { 'UNSAFE_HARNESS_ERROR' }
        $script:HarnessFailures.Add($Name + ':' + $code)
    }
}

function Assert-ThrowsSafeCode {
    param([Parameter(Mandatory)] [scriptblock] $Body, [Parameter(Mandatory)] [string] $Code)
    try { & $Body } catch {
        if ([string] $_.Exception.Message -ceq $Code) { return }
        throw 'WRONG_HELPER_ERROR_CODE'
    }
    throw 'HELPER_ERROR_NOT_THROWN'
}

function New-Fixture {
    param([Parameter(Mandatory)] [string] $Name)
    $root = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $runner = Join-Path $root 'Invoke-CloudOnboardSystemMigration.ps1'
    $manifest = Join-Path $root 'synthetic manifest.json'
    $privateRoot = Join-Path $root 'synthetic private source'
    $marker = Join-Path $root 'later action marker.txt'
    New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
    Copy-Item -LiteralPath $sourceRunner -Destination $runner
    Write-TestUtf8 -Path $manifest -Text '{"synthetic":"canary-manifest-unchanged"}'
    return [pscustomobject]@{
        Root = $root
        Runner = $runner
        Library = Join-Path $root 'cloud-onboard-system-migration-lib.ps1'
        Manifest = $manifest
        PrivateRoot = $privateRoot
        Marker = $marker
    }
}

function Invoke-RunnerCase {
    param([Parameter(Mandatory)] $Fixture, [Parameter(Mandatory)] [string] $Mode)
    $tokenName = 'P6_2_TASK11_SYNTHETIC_' + [guid]::NewGuid().ToString('N').ToUpperInvariant()
    return Invoke-HiddenPowerShell -Arguments @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $Fixture.Runner,
        '-Mode', $Mode, '-ApiBaseUri', 'http://127.0.0.1:9', '-ManifestPath', $Fixture.Manifest,
        '-PrivateSourceRoot', $Fixture.PrivateRoot, '-GatewayStopped',
        '-ApiTokenEnvironmentVariable', $tokenName
    ) -Environment @{ $tokenName = 'synthetic-token-never-printed'; 'P6_2_TASK11_MARKER' = $Fixture.Marker }
}

function Assert-SafeLoadFailure {
    param(
        [Parameter(Mandatory)] $Result,
        [Parameter(Mandatory)] $Fixture,
        [Parameter(Mandatory)] [string] $Mode,
        [Parameter(Mandatory)] [byte[]] $ManifestBefore
    )
    $expected = 'alias=batch phase=' + $Mode + ' step=load-library httpStatus=0 version=0 warnings=LIBRARY_LOAD_FAILED fileSha256=NONE'
    $lines = @($Result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
    Assert-True ($Result.ExitCode -eq 1) 'WRONG_EXIT_CODE'
    Assert-True ($lines.Count -eq 1) 'WRONG_STDOUT_COUNT'
    Assert-True ($lines[0] -ceq $expected) 'WRONG_SAFE_RECORD'
    Assert-True ([string]::IsNullOrEmpty($Result.Stderr)) 'STDERR_NOT_EMPTY'
    Assert-True (-not (Test-Path -LiteralPath $Fixture.Marker)) 'LATER_ACTION_EXECUTED'
    try { $manifestAfter = [System.IO.File]::ReadAllBytes($Fixture.Manifest) } catch { throw 'MANIFEST_POST_READ_FAILED' }
    Assert-True (([Convert]::ToBase64String($ManifestBefore)) -ceq ([Convert]::ToBase64String($manifestAfter))) 'MANIFEST_CHANGED'
}

function Read-ManifestBefore {
    param([Parameter(Mandatory)] [string] $Path)
    try { return [System.IO.File]::ReadAllBytes($Path) } catch { throw 'MANIFEST_PRE_READ_FAILED' }
}

function Get-SyntheticLibrary {
    return @'
function Assert-LoopbackApiTarget { param($ApiBaseUri) return $true }
function Assert-MigrationEnvironment { param([bool] $GatewayStopped) return $true }
function Read-StrictUtf8Json { param([string] $Path) return [pscustomobject]@{ records = @() } }
function Test-OnboardMigrationPlan { param($Plan, [string] $PrivateSourceRoot) return $true }
function Write-StrictUtf8Json { param([string] $Path, $Value) }
function Get-FileSha256 { param([string] $Path) return ('A' * 64) }
function Invoke-OnboardMigration {
    param($Mode, $Plan, [string] $PrivateSourceRoot, $ApiBaseUri, $Headers, $StatePersister)
    [System.IO.File]::WriteAllText([Environment]::GetEnvironmentVariable('P6_2_TASK11_MARKER', 'Process'), 'executed')
    return @()
}
function Format-OnboardMigrationResult { param($Result) return 'SYNTHETIC_RESULT' }
'@
}

function Assert-AclProbeResult {
    param(
        [Parameter(Mandatory)] $Result,
        [Parameter(Mandatory)] [int] $ExitCode,
        [Parameter(Mandatory)] [string] $Record,
        [Parameter(Mandatory)] [string] $Code
    )
    $lines = @($Result.Stdout -split "`r?`n" | Where-Object { $_ -ne '' })
    if ($Result.ExitCode -ne $ExitCode -or $lines.Count -ne 1 -or $lines[0] -cne $Record -or
        -not [string]::IsNullOrEmpty($Result.Stderr)) { throw $Code }
}

New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
try {
    Invoke-HarnessCase -Name 'slow_process_timeout_is_reclaimed' -Body {
        Assert-ThrowsSafeCode -Code 'PROCESS_TIMEOUT' -Body {
            Invoke-HiddenPowerShell -Arguments @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 30') `
                -TimeoutMilliseconds 200 -OutputTimeoutMilliseconds 1000 -TerminationTimeoutMilliseconds 5000 | Out-Null
        }
        Assert-True (-not (Get-Process -Id $testState.LastStartedProcessId -ErrorAction SilentlyContinue)) 'TIMEOUT_PROCESS_STILL_RUNNING'
        Assert-True ($script:OwnedProcesses.Count -eq 0) 'TIMEOUT_PROCESS_NOT_RELEASED'
    }

    Invoke-HarnessCase -Name 'post_start_helper_fault_is_reclaimed' -Body {
        Assert-ThrowsSafeCode -Code 'TEST_FAULT_AFTER_CAPTURE_START' -Body {
            Invoke-HiddenPowerShell -Arguments @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 30') `
                -TimeoutMilliseconds 30000 -OutputTimeoutMilliseconds 1000 -TerminationTimeoutMilliseconds 5000 `
                -TestFaultAfterCaptureStart | Out-Null
        }
        Assert-True (-not (Get-Process -Id $testState.LastStartedProcessId -ErrorAction SilentlyContinue)) 'FAULT_PROCESS_STILL_RUNNING'
        Assert-True ($script:OwnedProcesses.Count -eq 0) 'FAULT_PROCESS_NOT_RELEASED'
    }

    Invoke-HarnessCase -Name 'manifest_post_read_failure_is_infrastructure' -Body {
        $fixture = New-Fixture -Name 'manifest helper failure'
        $before = Read-ManifestBefore -Path $fixture.Manifest
        Remove-Item -LiteralPath $fixture.Manifest -Force
        $safeResult = [pscustomobject]@{
            ExitCode = 1
            Stdout = 'alias=batch phase=DryRun step=load-library httpStatus=0 version=0 warnings=LIBRARY_LOAD_FAILED fileSha256=NONE' + "`r`n"
            Stderr = ''
        }
        Assert-ThrowsSafeCode -Code 'MANIFEST_POST_READ_FAILED' -Body {
            Assert-SafeLoadFailure -Result $safeResult -Fixture $fixture -Mode 'DryRun' -ManifestBefore $before
        }
    }

    foreach ($mode in @('DryRun', 'ApplyV19', 'ContractCheck')) {
        Invoke-TestCase -Name ('missing_' + $mode) -Body {
            $fixture = New-Fixture -Name ('missing ' + $mode)
            $before = Read-ManifestBefore -Path $fixture.Manifest
            $result = Invoke-RunnerCase -Fixture $fixture -Mode $mode
            Assert-SafeLoadFailure -Result $result -Fixture $fixture -Mode $mode -ManifestBefore $before
        }.GetNewClosure()

        Invoke-TestCase -Name ('syntax_' + $mode) -Body {
            $fixture = New-Fixture -Name ('syntax ' + $mode)
            Write-TestUtf8 -Path $fixture.Library -Text "'TASK11_SYNTHETIC_LEAK_CANARY"
            $before = Read-ManifestBefore -Path $fixture.Manifest
            $result = Invoke-RunnerCase -Fixture $fixture -Mode $mode
            Assert-SafeLoadFailure -Result $result -Fixture $fixture -Mode $mode -ManifestBefore $before
            Assert-True (($result.Stdout + $result.Stderr) -notmatch 'TASK11_SYNTHETIC_LEAK_CANARY') 'SOURCE_CANARY_LEAKED'
            Assert-True (($result.Stdout + $result.Stderr) -notmatch [regex]::Escape($fixture.Root)) 'PRIVATE_PATH_LEAKED'
        }.GetNewClosure()

        Invoke-TestCase -Name ('acl_' + $mode) -Body {
            $fixture = New-Fixture -Name ('acl ' + $mode)
            $aclState = [pscustomobject]@{
                Name = $mode
                DeniedProven = $false
                RestoreVerified = $false
            }
            $testState.AclFixtures.Add($aclState)
            Write-TestUtf8 -Path $fixture.Library -Text (Get-SyntheticLibrary)
            $probePath = Join-Path $fixture.Root 'acl-read-probe.ps1'
            Write-TestUtf8 -Path $probePath -Text @'
[CmdletBinding()]
param([Parameter(Mandatory)] [string] $Path)
try {
    [System.IO.File]::ReadAllBytes($Path) | Out-Null
    [Console]::Out.WriteLine('READ_OK')
    exit 0
} catch {
    if ($_.Exception -is [System.UnauthorizedAccessException] -or
        $_.Exception.InnerException -is [System.UnauthorizedAccessException]) {
        [Console]::Out.WriteLine('READ_DENIED')
        exit 23
    }
    [Console]::Out.WriteLine('READ_ERROR')
    exit 24
}
'@
            $before = Read-ManifestBefore -Path $fixture.Manifest
            $originalAcl = Get-Acl -LiteralPath $fixture.Library
            $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
            $denyRule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                $sid, [System.Security.AccessControl.FileSystemRights]::ReadData,
                [System.Security.AccessControl.AccessControlType]::Deny)
            try {
                try {
                    $probeBefore = Invoke-HiddenPowerShell -Arguments @(
                        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $probePath,
                        '-Path', $fixture.Library)
                    Assert-AclProbeResult -Result $probeBefore -ExitCode 0 -Record 'READ_OK' -Code 'ACL_PROBE_BEFORE_FAILED'
                } catch { throw 'ACL_PROBE_BEFORE_FAILED' }
                try {
                    $deniedAcl = Get-Acl -LiteralPath $fixture.Library
                    $deniedAcl.AddAccessRule($denyRule)
                    Set-Acl -LiteralPath $fixture.Library -AclObject $deniedAcl
                } catch { throw 'ACL_DENY_SET_FAILED' }
                try {
                    $probeDenied = Invoke-HiddenPowerShell -Arguments @(
                        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $probePath,
                        '-Path', $fixture.Library)
                    Assert-AclProbeResult -Result $probeDenied -ExitCode 23 -Record 'READ_DENIED' -Code 'ACL_DENY_NOT_PROVEN'
                    $aclState.DeniedProven = $true
                } catch { throw 'ACL_DENY_NOT_PROVEN' }
                $result = Invoke-RunnerCase -Fixture $fixture -Mode $mode
                Assert-SafeLoadFailure -Result $result -Fixture $fixture -Mode $mode -ManifestBefore $before
            } finally {
                try {
                    Set-Acl -LiteralPath $fixture.Library -AclObject $originalAcl
                    $probeAfter = Invoke-HiddenPowerShell -Arguments @(
                        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $probePath,
                        '-Path', $fixture.Library)
                    Assert-AclProbeResult -Result $probeAfter -ExitCode 0 -Record 'READ_OK' -Code 'ACL_PROBE_AFTER_FAILED'
                    $aclState.RestoreVerified = $true
                } catch { throw 'ACL_RESTORE_OR_PROBE_FAILED' }
            }
        }.GetNewClosure()

        Invoke-TestCase -Name ('valid_' + $mode) -Body {
            $fixture = New-Fixture -Name ('valid ' + $mode)
            Write-TestUtf8 -Path $fixture.Library -Text (Get-SyntheticLibrary)
            $result = Invoke-RunnerCase -Fixture $fixture -Mode $mode
            Assert-True ($result.ExitCode -eq 0) 'VALID_EXIT_NONZERO'
            Assert-True ([string]::IsNullOrEmpty($result.Stderr)) 'VALID_STDERR_NOT_EMPTY'
            Assert-True (Test-Path -LiteralPath $fixture.Marker -PathType Leaf) 'VALID_MARKER_MISSING'
        }.GetNewClosure()
    }
} finally {
    foreach ($ownedProcess in @($script:OwnedProcesses)) {
        if (Stop-OwnedProcessBounded -Process $ownedProcess -TerminationTimeoutMilliseconds 5000) {
            [void] $script:OwnedProcesses.Remove($ownedProcess)
            $ownedProcess.Dispose()
        } else {
            $testState.ProcessCleanupUnconfirmed = $true
        }
    }
    if ($testState.ProcessCleanupUnconfirmed -or $script:OwnedProcesses.Count -ne 0) {
        $script:Failures.Add('process_cleanup:PROCESS_CLEANUP_UNCONFIRMED')
    }
    $aclRestored = $testState.AclFixtures.Count -eq 3 -and
        @($testState.AclFixtures | Where-Object { -not $_.DeniedProven -or -not $_.RestoreVerified }).Count -eq 0
    $rootParent = [System.IO.Path]::GetFullPath((Split-Path -Parent $testRoot)).TrimEnd('\')
    $rootLeaf = Split-Path -Leaf $testRoot
    $expectedLeaf = 'p6-2 task11 library loading ' + $testRunId
    if ($rootParent -cne $tempBase -or $rootLeaf -cne $expectedLeaf -or
        $rootLeaf -notmatch '^p6-2 task11 library loading [a-f0-9]{32}$') {
        $script:Failures.Add('cleanup:TEMP_PATH_BOUNDARY_INVALID')
    } elseif (-not $aclRestored) {
        $script:Failures.Add('cleanup:ACL_RECOVERY_UNCONFIRMED_FIXTURE_PRESERVED')
    } else {
        try { Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction Stop } catch { $script:Failures.Add('cleanup:TEMP_CLEANUP_FAILED') }
    }
}

Write-Output ('TOTAL=' + $script:Total)
Write-Output ('PASSED=' + $script:Passed)
Write-Output ('FAILED=' + $script:Failures.Count)
Write-Output ('HARNESS_TOTAL=' + $script:HarnessTotal)
Write-Output ('HARNESS_PASSED=' + $script:HarnessPassed)
Write-Output ('HARNESS_FAILED=' + $script:HarnessFailures.Count)
Write-Output ('ACL_RESTORED=' + ([string] $aclRestored).ToLowerInvariant())
foreach ($failure in $script:Failures) { Write-Output ('FAIL=' + $failure) }
foreach ($failure in $script:HarnessFailures) { Write-Output ('HARNESS_FAIL=' + $failure) }
if ($script:Failures.Count -ne 0 -or $script:HarnessFailures.Count -ne 0) { exit 1 }
exit 0
