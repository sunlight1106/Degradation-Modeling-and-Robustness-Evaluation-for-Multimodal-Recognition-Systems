param([int]$Port = 4173)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop, then run this script again.' }
if (-not (Test-Path -LiteralPath '.env')) {
    function New-Secret {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    }
    $config = Get-Content -LiteralPath '.env.example' -Raw
    foreach ($key in @('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','MINIO_ROOT_PASSWORD','JWT_SECRET','CREDENTIAL_MASTER_KEY')) {
        $config = $config.Replace("$key=", "$key=$(New-Secret)")
    }
    $config = $config.Replace('WEB_PORT=4173', "WEB_PORT=$Port")
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), $config, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Generated .env (random DB/JWT secrets). Admin password uses the fixed default from .env.example.'
}
& docker compose up -d --build --wait --wait-timeout 600
if ($LASTEXITCODE -ne 0) { throw 'Deployment failed. Inspect: docker compose logs --tail 100' }
$portLine = Get-Content '.env' | Where-Object { $_ -match '^WEB_PORT=' } | Select-Object -First 1
$actualPort = if ($portLine) { $portLine.Split('=',2)[1] } else { '4173' }
Write-Host "Ready: http://localhost:$actualPort (username admin; password 1926648785ljz)"
