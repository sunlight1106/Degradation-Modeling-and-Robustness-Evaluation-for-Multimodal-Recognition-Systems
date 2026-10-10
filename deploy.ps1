param([int]$Port = 4173, [switch]$SkipBuild, [switch]$Build, [switch]$FullBuild)
New-Item -ItemType Directory -Path "$PSScriptRoot\.runtime" -Force | Out-Null
$startupGuard = $null
function Write-StartupStatus([string]$Phase,[string]$Step,[string]$State='running',[string]$Url='') {
    $temp = Join-Path $PSScriptRoot ('.runtime\startup-' + [guid]::NewGuid().ToString('N') + '.tmp')
    @{ phase=$Phase; step=$Step; state=$State; url=$Url; updatedAt=[datetime]::UtcNow.ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination "$PSScriptRoot\.runtime\startup-status.json" -Force
}
try {
    $startupGuard=[IO.File]::Open("$PSScriptRoot\.runtime\deploy.lock",'OpenOrCreate','ReadWrite','None')
    Write-StartupStatus '正在启动 Docker；会自动检查残留通信文件' 'docker'
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$startupWatch = [Diagnostics.Stopwatch]::StartNew()
. "$PSScriptRoot\scripts\ensure-docker.ps1"
. "$PSScriptRoot\scripts\start-project.ps1"
Ensure-Docker
$dockerSeconds = $startupWatch.Elapsed.TotalSeconds
function New-Secret {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    }
if (-not (Test-Path -LiteralPath '.env')) {
    $config = Get-Content -LiteralPath '.env.example' -Raw
    foreach ($key in @('BOOTSTRAP_ADMIN_PASSWORD','MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','MINIO_ROOT_PASSWORD','JWT_SECRET','CREDENTIAL_MASTER_KEY','TRAINING_SERVICE_TOKEN')) {
        $config = $config.Replace("$key=", "$key=$(New-Secret)")
    }
    $config = $config.Replace('WEB_PORT=4173', "WEB_PORT=$Port")
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), $config, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Generated .env with a random administrator password and service secrets. Keep this file private.'
}
$trainingLine = Get-Content '.env' | Where-Object { $_ -match '^TRAINING_SERVICE_TOKEN=.+$' }
if (-not $trainingLine) {
    $config = (Get-Content '.env' -Raw) -replace '(?m)^TRAINING_SERVICE_TOKEN=.*\r?\n?', ''
    $config = $config.TrimEnd() + "`nTRAINING_SERVICE_TOKEN=$(New-Secret)`n"
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), $config, (New-Object Text.UTF8Encoding($false)))
}
Write-StartupStatus '正在启动并检查项目服务' 'project'
Start-Platform -Build:$Build -FullBuild:$FullBuild -SkipBuild:$SkipBuild
$readySeconds = $startupWatch.Elapsed.TotalSeconds
$portLine = Get-Content '.env' | Where-Object { $_ -match '^WEB_PORT=' } | Select-Object -First 1
$actualPort = if ($portLine) { $portLine.Split('=',2)[1] } else { '4173' }
Write-Host "Ready: http://localhost:$actualPort (administrator credentials are in the local .env file)"
Write-Host ('Startup: Docker {0:N1}s; project {1:N1}s; total {2:N1}s.' -f $dockerSeconds, ($readySeconds-$dockerSeconds), $readySeconds)
New-Item -ItemType Directory -Path '.runtime' -Force | Out-Null
@{ completedAt = [datetime]::UtcNow.ToString('o'); dockerSeconds = [math]::Round($dockerSeconds,2); projectSeconds = [math]::Round($readySeconds-$dockerSeconds,2); totalSeconds = [math]::Round($readySeconds,2) } | ConvertTo-Json | Set-Content -Encoding UTF8 '.runtime/startup-last.json'
Write-StartupStatus '平台已经就绪' 'ready' 'ready' "http://localhost:$actualPort"
} catch {
    if ($startupGuard) { Write-StartupStatus '启动失败，请查看启动日志后重试' 'failed' 'failed' }
    throw
} finally { if ($startupGuard) { $startupGuard.Dispose() } }
