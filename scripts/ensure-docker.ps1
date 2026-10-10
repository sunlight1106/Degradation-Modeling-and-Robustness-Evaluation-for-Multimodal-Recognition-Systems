# Recover only the known Windows Docker Desktop runtime-socket failure.
function Test-DockerReady {
    $probe = New-Object Diagnostics.Process
    try {
        $probe.StartInfo.FileName = (Get-Command docker -ErrorAction Stop).Source
        $probe.StartInfo.Arguments = 'info --format "{{.ServerVersion}}"'
        $probe.StartInfo.UseShellExecute = $false
        $probe.StartInfo.CreateNoWindow = $true
        $probe.StartInfo.RedirectStandardOutput = $true
        $probe.StartInfo.RedirectStandardError = $true
        [void]$probe.Start()
        $stdout = $probe.StandardOutput.ReadToEndAsync()
        $stderr = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit(5000)) {
            $probe.Kill()
            return $false
        }
        return ($probe.ExitCode -eq 0)
    } catch { return $false }
    finally { $probe.Dispose() }
}

function Get-DockerSocketFailure([datetime]$Since) {
    $log = Join-Path $env:LOCALAPPDATA 'Docker\log\host\com.docker.backend.exe.log'
    if (-not (Test-Path -LiteralPath $log)) { return $false }
    foreach ($line in (Get-Content -LiteralPath $log -Tail 100 -ErrorAction SilentlyContinue)) {
        if ($line -match '^\[(?<time>[^\]]+)\].*(backend crashed|reporting error to user).*starting services:.*(Docker[/\\]run[/\\]|docker-secrets-engine[/\\]).*(cannot be accessed|filename, directory name)') {
            $timestamp = [datetimeoffset]::MinValue
            if ([datetimeoffset]::TryParse($Matches.time, [ref]$timestamp) -and $timestamp.UtcDateTime -ge $Since.ToUniversalTime()) { return $true }
        }
    }
    return $false
}

function Repair-DockerSockets([switch]$SkipStop,[string]$LocalData=$env:LOCALAPPDATA) {
    if (Test-DockerReady) { return }
    if ($SkipStop) {
        if (Get-Process -Name 'Docker Desktop','com.docker.backend','com.docker.build' -ErrorAction SilentlyContinue) { return }
    } else {
    $stop = Start-Process -FilePath (Get-Command docker).Source -ArgumentList @('desktop','stop','--timeout','30') -WindowStyle Hidden -PassThru
    if (-not $stop.WaitForExit(30000)) {
        Get-CimInstance Win32_Process -Filter "ParentProcessId=$($stop.Id)" |
            Where-Object { $_.Name -eq 'docker-desktop.exe' } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $stop.Id -Force -ErrorAction SilentlyContinue
    }
    # A crashed Desktop can leave its error dialog/backend alive after stop.
    foreach ($process in (Get-Process -Name 'Docker Desktop','com.docker.backend','com.docker.build' -ErrorAction SilentlyContinue)) {
        if ($process.Path -and $process.Path.StartsWith("$env:ProgramFiles\Docker\Docker\", [StringComparison]::OrdinalIgnoreCase)) {
            try { Stop-Process -Id $process.Id -Force -ErrorAction Stop }
            catch {
                # Desktop can exit on its own after the process list was captured.
                if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) { throw }
            }
        }
    }
    Start-Sleep -Seconds 2
    }
    $runtimePaths = @('Docker\run', 'docker-secrets-engine')
    $knownNames = @('dockerEthernetVfkit','dockerInference','sailor-ingest.sock','userAnalyticsOtlpHttp.sock','engine.sock')
    foreach ($relative in $runtimePaths) {
        $path = Join-Path $LocalData $relative
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $resolved = (Resolve-Path -LiteralPath $path).Path
        $root = Get-Item -LiteralPath $resolved -Force
        if ($resolved -ne $path -or ($root.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected Docker runtime directory; automatic recovery stopped.' }
        foreach ($entry in (Get-ChildItem -LiteralPath $resolved -Force)) {
            if ($entry.PSIsContainer -or $entry.Length -ne 0 -or $entry.Name -notin $knownNames) {
                throw 'Unexpected Docker runtime contents; automatic recovery stopped.'
            }
        }
        $destination = $resolved + '.startup-backup-' + [guid]::NewGuid().ToString('N')
        Move-Item -LiteralPath $resolved -Destination $destination -ErrorAction Stop
    }
}

function Ensure-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install Docker Desktop first.' }
    if (Test-DockerReady) { return }
    $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) { throw 'Docker engine is unavailable and Docker Desktop was not found.' }
    # Windows AF_UNIX endpoints survive a stopped/crashed Desktop as reparse files.
    # Quarantine ONLY known zero-byte runtime entries while ALL Desktop processes
    # are absent. This avoids paying for one failed Desktop boot before recovery.
    if (-not (Get-Process -Name 'Docker Desktop','com.docker.backend','com.docker.build' -ErrorAction SilentlyContinue)) {
        Repair-DockerSockets -SkipStop
    }
    $since = [datetime]::UtcNow
    Write-Host 'Starting Docker Desktop...'
    Start-Process -FilePath $desktop -WindowStyle Hidden
    $repaired = $false
    $deadline = [datetime]::UtcNow.AddMinutes(3)
    while ([datetime]::UtcNow -lt $deadline) {
        if (Test-DockerReady) { return }
        if (-not $repaired -and (Get-DockerSocketFailure $since)) {
            Write-Host 'Recovering stale Docker runtime sockets (persistent data is untouched)...'
            Repair-DockerSockets
            $repaired = $true
            Start-Process -FilePath $desktop -WindowStyle Hidden
            $deadline = [datetime]::UtcNow.AddMinutes(3)
        }
        Start-Sleep -Seconds 3
    }
    throw 'Docker did not become ready. Check Docker Desktop diagnostics; no data was reset.'
}
