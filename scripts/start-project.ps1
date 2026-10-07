# Kept separate from secret generation so startup decisions can be tested without Docker.
function Invoke-Compose([string[]]$ComposeArguments) {
    & docker compose @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed. Inspect: docker compose logs --tail 100' }
}

function Start-Platform([switch]$Build, [switch]$FullBuild, [switch]$SkipBuild) {
    if (([int]$Build.IsPresent + [int]$FullBuild.IsPresent + [int]$SkipBuild.IsPresent) -gt 1) {
        throw 'Choose only one of -Build, -FullBuild or -SkipBuild.'
    }
    $required = @(& docker compose config --services)
    if ($LASTEXITCODE -ne 0 -or -not $required.Count) { throw 'Cannot read Compose services.' }
    $installed = @(& docker compose ps --all --format '{{.Service}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect installed services.' }
    $missing = @($required | Where-Object { $_ -notin $installed })
    if ($FullBuild -or $missing.Count) {
        if ($SkipBuild) { throw 'This installation is incomplete. Run deploy.ps1 once without -SkipBuild.' }
        Write-Host 'Installing services / applying full configuration. This can download and compile dependencies.'
        Invoke-Compose -ComposeArguments @('up', '-d', '--build', '--wait', '--wait-timeout', '600')
    } elseif ($Build) {
        Write-Host 'Building application changes; installed storage services are retained.'
        Invoke-Compose -ComposeArguments @('build', 'backend', 'frontend', 'training')
        # Retire old application processes before migrating, and restart Nginx
        # afterwards so its upstream DNS points at the replacement backend.
        Invoke-Compose -ComposeArguments @('stop', 'frontend', 'model-worker', 'backend')
        Invoke-Compose -ComposeArguments @('start', '--wait', '--wait-timeout', '600', 'mysql', 'redis', 'minio', 'clamav')
        Invoke-Compose -ComposeArguments @('up', '-d', '--no-deps', '--no-build', '--wait', '--wait-timeout', '240', 'backend', 'model-worker', 'training')
        Invoke-Compose -ComposeArguments @('up', '-d', '--no-deps', '--no-build', '--wait', '--wait-timeout', '240', 'frontend')
    } else {
        Write-Host 'Starting installed services without rebuilding, downloading or recreating containers.'
        Invoke-Compose -ComposeArguments @('start', '--wait', '--wait-timeout', '600')
        Write-Host 'After changing code, run: .\deploy.ps1 -Build'
    }
}
