param([int]$Port = 4173, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
. "$PSScriptRoot\scripts\ensure-docker.ps1"
Ensure-Docker
if (-not (Test-Path -LiteralPath '.env')) {
    function New-Secret {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    }
    $config = Get-Content -LiteralPath '.env.example' -Raw
    foreach ($key in @('BOOTSTRAP_ADMIN_PASSWORD','MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','MINIO_ROOT_PASSWORD','JWT_SECRET','CREDENTIAL_MASTER_KEY')) {
        $config = $config.Replace("$key=", "$key=$(New-Secret)")
    }
    $config = $config.Replace('WEB_PORT=4173', "WEB_PORT=$Port")
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), $config, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Generated .env with a random administrator password and service secrets. Keep this file private.'
}
$composeArgs = @('compose', 'up', '-d', '--wait', '--wait-timeout', '600')
if (-not $SkipBuild) { $composeArgs += '--build' }
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { throw 'Deployment failed. Inspect: docker compose logs --tail 100' }
$portLine = Get-Content '.env' | Where-Object { $_ -match '^WEB_PORT=' } | Select-Object -First 1
$actualPort = if ($portLine) { $portLine.Split('=',2)[1] } else { '4173' }
Write-Host "Ready: http://localhost:$actualPort (administrator credentials are in the local .env file)"
