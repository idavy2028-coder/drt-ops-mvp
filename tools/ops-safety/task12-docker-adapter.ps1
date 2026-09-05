Set-StrictMode -Version Latest

function Assert-Task12DockerEndpoint {
    param([Parameter(Mandatory)] [string] $Endpoint)
    if ($Endpoint -cnotin @(
            'npipe:////./pipe/dockerDesktopLinuxEngine',
            'npipe:////./pipe/docker_engine',
            'unix:///var/run/docker.sock')) {
        throw 'TASK12_DOCKER_ENDPOINT_INVALID'
    }
}

function ConvertTo-Task12NativeArgument {
    param([Parameter(Mandatory)] [AllowEmptyString()] [string] $Value)
    if ($Value.IndexOf([char] 0) -ge 0) { throw 'TASK12_DOCKER_ARGUMENT_INVALID' }
    return '"' + $Value.Replace('\', '\').Replace('"', '\"') + '"'
}

function Stop-Task12OwnedProcess {
    param([Parameter(Mandatory)] [System.Diagnostics.Process] $Process)
    if ($Process.HasExited) { return $true }
    try { $Process.Kill() } catch { return $false }
    try { return [bool] $Process.WaitForExit(5000) } catch { return $false }
}

function Invoke-Task12DockerFixedProcess {
    param(
        [Parameter(Mandatory)] [string] $DockerExecutablePath,
        [Parameter(Mandatory)] [string] $Endpoint,
        [Parameter(Mandatory)] [ValidateSet('ListContainers','InspectContainers','ListVolumes','InspectVolumes','RemoveContainer','RemoveVolume')] [string] $Operation,
        [string[]] $Targets = @(),
        [ValidateRange(100, 60000)] [int] $TimeoutMilliseconds = 10000
    )

    Assert-Task12DockerEndpoint -Endpoint $Endpoint
    if ([string]::IsNullOrWhiteSpace($DockerExecutablePath)) { throw 'TASK12_DOCKER_EXECUTABLE_INVALID' }
    $arguments = New-Object 'System.Collections.Generic.List[string]'
    $arguments.Add('--host')
    $arguments.Add($Endpoint)
    switch ($Operation) {
        'ListContainers' { foreach ($value in @('container','ls','--all','--quiet','--no-trunc')) { $arguments.Add($value) } }
        'InspectContainers' {
            if ($Targets.Count -lt 1) { throw 'TASK12_DOCKER_TARGET_INVALID' }
            foreach ($target in $Targets) {
                if ($target -cnotmatch '^[a-f0-9]{64}$') { throw 'TASK12_DOCKER_TARGET_INVALID' }
            }
            foreach ($value in @('container','inspect')) { $arguments.Add($value) }
            foreach ($target in $Targets) { $arguments.Add($target) }
        }
        'ListVolumes' { foreach ($value in @('volume','ls','--quiet')) { $arguments.Add($value) } }
        'InspectVolumes' {
            if ($Targets.Count -lt 1) { throw 'TASK12_DOCKER_TARGET_INVALID' }
            foreach ($target in $Targets) {
                if ($target -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$') { throw 'TASK12_DOCKER_TARGET_INVALID' }
            }
            foreach ($value in @('volume','inspect')) { $arguments.Add($value) }
            foreach ($target in $Targets) { $arguments.Add($target) }
        }
        'RemoveContainer' {
            if ($Targets.Count -ne 1 -or $Targets[0] -cnotmatch '^[a-f0-9]{64}$') { throw 'TASK12_DOCKER_TARGET_INVALID' }
            foreach ($value in @('container','rm',$Targets[0])) { $arguments.Add($value) }
        }
        'RemoveVolume' {
            if ($Targets.Count -ne 1 -or $Targets[0] -cnotmatch '^drt-p6-2-task12-pgdata-\d{8}-[a-f0-9]{6}$') { throw 'TASK12_DOCKER_TARGET_INVALID' }
            foreach ($value in @('volume','rm',$Targets[0])) { $arguments.Add($value) }
        }
    }

    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $DockerExecutablePath
    $start.Arguments = (($arguments.ToArray() | ForEach-Object { ConvertTo-Task12NativeArgument $_ }) -join ' ')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $start.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    foreach ($name in @('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','DOCKER_API_VERSION')) {
        [void] $start.EnvironmentVariables.Remove($name)
    }

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $started = $false
    $completedNormally = $false
    try {
        try { $started = [bool] $process.Start() } catch { throw 'TASK12_DOCKER_PROCESS_START_FAILED' }
        if (-not $started) { throw 'TASK12_DOCKER_PROCESS_START_FAILED' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) {
            # 只回收本函数持有的进程；超时后也不给后台命令继续运行的机会。
            if (-not (Stop-Task12OwnedProcess -Process $process)) { throw 'TASK12_DOCKER_PROCESS_RECLAIM_FAILED' }
            throw 'TASK12_DOCKER_COMMAND_TIMEOUT'
        }
        if (-not [System.Threading.Tasks.Task]::WaitAll(@($stdoutTask, $stderrTask), 5000)) {
            throw 'TASK12_DOCKER_OUTPUT_TIMEOUT'
        }
        $completedNormally = $true
        if ($process.ExitCode -ne 0) { throw 'TASK12_DOCKER_COMMAND_FAILED' }
        $stdout = [string] $stdoutTask.Result
        if ($stdout.Length -gt 4194304) { throw 'TASK12_DOCKER_OUTPUT_TOO_LARGE' }
        return $stdout
    } catch {
        $message = [string] $_.Exception.Message
        if ($message -match '^TASK12_[A-Z0-9_]+$') { throw $message }
        throw 'TASK12_DOCKER_COMMAND_FAILED'
    } finally {
        if ($started -and -not $process.HasExited) {
            if (-not (Stop-Task12OwnedProcess -Process $process)) {
                $process.Dispose()
                throw 'TASK12_DOCKER_PROCESS_RECLAIM_FAILED'
            }
        }
        if (-not $started -or $process.HasExited) { $process.Dispose() }
    }
}

function ConvertFrom-Task12DockerJson {
    param([Parameter(Mandatory)] [AllowEmptyString()] [string] $Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { throw 'TASK12_DOCKER_JSON_INVALID' }
    try {
        $parsed = $Json | ConvertFrom-Json -ErrorAction Stop
        return $parsed
    } catch { throw 'TASK12_DOCKER_JSON_INVALID' }
}

function Get-Task12DockerJsonProperty {
    param(
        [Parameter(Mandatory)] [AllowNull()] $Object,
        [Parameter(Mandatory)] [string] $Name,
        [switch] $Required
    )
    if ($null -eq $Object) {
        if ($Required) { throw 'TASK12_DOCKER_JSON_INVALID' }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        if ($Required) { throw 'TASK12_DOCKER_JSON_INVALID' }
        return $null
    }
    return $property.Value
}

function Get-Task12DockerInventory {
    [CmdletBinding()]
    param(
        [string] $DockerExecutablePath = 'docker.exe',
        [Parameter(Mandatory)] [string] $Endpoint,
        [ValidateRange(100, 60000)] [int] $TimeoutMilliseconds = 10000
    )

    $containerList = Invoke-Task12DockerFixedProcess -DockerExecutablePath $DockerExecutablePath -Endpoint $Endpoint `
        -Operation ListContainers -TimeoutMilliseconds $TimeoutMilliseconds
    $containerIds = @($containerList -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($id in $containerIds) { if ($id -cnotmatch '^[a-f0-9]{64}$') { throw 'TASK12_DOCKER_LIST_INVALID' } }
    $rawContainers = @()
    if ($containerIds.Count -gt 0) {
        $containerJson = Invoke-Task12DockerFixedProcess -DockerExecutablePath $DockerExecutablePath -Endpoint $Endpoint `
            -Operation InspectContainers -Targets $containerIds -TimeoutMilliseconds $TimeoutMilliseconds
        $rawContainers = @(ConvertFrom-Task12DockerJson -Json $containerJson)
    }

    $volumeList = Invoke-Task12DockerFixedProcess -DockerExecutablePath $DockerExecutablePath -Endpoint $Endpoint `
        -Operation ListVolumes -TimeoutMilliseconds $TimeoutMilliseconds
    $volumeNames = @($volumeList -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($name in $volumeNames) { if ($name -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,254}$') { throw 'TASK12_DOCKER_LIST_INVALID' } }
    $rawVolumes = @()
    if ($volumeNames.Count -gt 0) {
        $volumeJson = Invoke-Task12DockerFixedProcess -DockerExecutablePath $DockerExecutablePath -Endpoint $Endpoint `
            -Operation InspectVolumes -Targets $volumeNames -TimeoutMilliseconds $TimeoutMilliseconds
        $rawVolumes = @(ConvertFrom-Task12DockerJson -Json $volumeJson)
    }

    try {
        if ($rawContainers.Count -ne $containerIds.Count) { throw 'TASK12_DOCKER_JSON_INVALID' }
        if ($rawVolumes.Count -ne $volumeNames.Count) { throw 'TASK12_DOCKER_JSON_INVALID' }
        $containers = @($rawContainers | ForEach-Object {
            $containerId = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Id' -Required)
            $name = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Name' -Required)
            if ($name.StartsWith('/')) { $name = $name.Substring(1) }
            if ($containerId -cnotmatch '^[a-f0-9]{64}$' -or [string]::IsNullOrWhiteSpace($name)) {
                throw 'TASK12_DOCKER_JSON_INVALID'
            }
            $config = Get-Task12DockerJsonProperty -Object $_ -Name 'Config' -Required
            $state = Get-Task12DockerJsonProperty -Object $_ -Name 'State' -Required
            $rawMounts = @(Get-Task12DockerJsonProperty -Object $_ -Name 'Mounts' -Required)
            [pscustomobject]@{
                Id = $containerId
                Name = $name
                Labels = Get-Task12DockerJsonProperty -Object $config -Name 'Labels'
                State = [string] (Get-Task12DockerJsonProperty -Object $state -Name 'Status' -Required)
                Mounts = @($rawMounts | ForEach-Object {
                    [pscustomobject]@{
                        Type = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Type' -Required)
                        Name = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Name')
                        Source = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Source')
                        Destination = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Destination')
                    }
                })
            }
        })
        $volumes = @($rawVolumes | ForEach-Object {
            $volumeName = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Name' -Required)
            if ([string]::IsNullOrWhiteSpace($volumeName)) { throw 'TASK12_DOCKER_JSON_INVALID' }
            [pscustomobject]@{
                Name = $volumeName
                Driver = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Driver' -Required)
                Scope = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Scope' -Required)
                Labels = Get-Task12DockerJsonProperty -Object $_ -Name 'Labels'
                CreatedAt = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'CreatedAt' -Required)
                Options = Get-Task12DockerJsonProperty -Object $_ -Name 'Options'
                Mountpoint = [string] (Get-Task12DockerJsonProperty -Object $_ -Name 'Mountpoint')
            }
        })
        $normalizedIds = @($containers | ForEach-Object { $_.Id } | Sort-Object)
        $listedIds = @($containerIds | Sort-Object)
        $normalizedNames = @($volumes | ForEach-Object { $_.Name } | Sort-Object)
        $listedNames = @($volumeNames | Sort-Object)
        if (($normalizedIds -join '|') -cne ($listedIds -join '|') -or
            ($normalizedNames -join '|') -cne ($listedNames -join '|')) {
            throw 'TASK12_DOCKER_JSON_INVALID'
        }
    } catch {
        throw 'TASK12_DOCKER_JSON_INVALID'
    }
    return [pscustomobject]@{ Containers=$containers; Volumes=$volumes }
}

function Remove-Task12DockerContainer {
    [CmdletBinding()]
    param(
        [string] $DockerExecutablePath = 'docker.exe',
        [Parameter(Mandatory)] [string] $Endpoint,
        [Parameter(Mandatory)] [string] $ContainerId,
        [ValidateRange(100, 60000)] [int] $TimeoutMilliseconds = 10000
    )
    Invoke-Task12DockerFixedProcess -DockerExecutablePath $DockerExecutablePath -Endpoint $Endpoint `
        -Operation RemoveContainer -Targets @($ContainerId) -TimeoutMilliseconds $TimeoutMilliseconds | Out-Null
}

function Remove-Task12DockerVolume {
    [CmdletBinding()]
    param(
        [string] $DockerExecutablePath = 'docker.exe',
        [Parameter(Mandatory)] [string] $Endpoint,
        [Parameter(Mandatory)] [string] $VolumeName,
        [ValidateRange(100, 60000)] [int] $TimeoutMilliseconds = 10000
    )
    Invoke-Task12DockerFixedProcess -DockerExecutablePath $DockerExecutablePath -Endpoint $Endpoint `
        -Operation RemoveVolume -Targets @($VolumeName) -TimeoutMilliseconds $TimeoutMilliseconds | Out-Null
}
