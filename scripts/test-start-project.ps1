$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\start-project.ps1"
$script:required = @('mysql','redis','minio','clamav','backend','model-worker','training','frontend')
$script:installed = $script:required
$script:calls = New-Object 'Collections.Generic.List[string]'
$script:fail = ''
function docker {
    $command = $args -join ' '
    $script:calls.Add($command)
    $global:LASTEXITCODE = 0
    if ($script:fail -and $command -like $script:fail) { $global:LASTEXITCODE = 1; return }
    if ($command -eq 'compose config --services') { return $script:required }
    if ($command -eq 'compose ps --all --format {{.Service}}') { return $script:installed }
}
function Assert-Startup($condition, $message) {
    if (-not $condition) { throw $message }
    Write-Host "PASS: $message"
}
Start-Platform
Assert-Startup ($script:calls -contains 'compose start --wait --wait-timeout 600') 'Existing installation uses start with health verification'
Assert-Startup (-not ($script:calls | Where-Object { $_ -match ' compose |^compose (build|up|pull|down|rm)' })) 'Daily start performs no image build, pull or container recreation'
$script:calls.Clear()
Start-Platform -Build
Assert-Startup ($script:calls -contains 'compose build backend frontend training') 'Explicit update builds all application images'
Assert-Startup (-not ($script:calls | Where-Object { $_ -match '^compose up.* (mysql|minio|redis|clamav)' })) 'Application update preserves installed storage services'
Assert-Startup ($script:calls.IndexOf('compose stop frontend model-worker backend') -lt $script:calls.IndexOf('compose up -d --no-deps --no-build --wait --wait-timeout 240 backend model-worker training')) 'Old application processes stop before schema updates'
Assert-Startup ($script:calls -contains 'compose up -d --no-deps --no-build --wait --wait-timeout 240 backend model-worker training') 'API and worker initialize together with migration locking'
Assert-Startup ($script:calls[$script:calls.Count - 1] -eq 'compose up -d --no-deps --no-build --wait --wait-timeout 240 frontend') 'Frontend restarts after backend readiness to refresh upstream DNS'
$script:installed = @()
$script:calls.Clear()
Start-Platform
Assert-Startup ($script:calls -contains 'compose up -d --build --wait --wait-timeout 600') 'First deployment installs the full stack'
$rejected=$false
try { Start-Platform -SkipBuild } catch { $rejected=$true }
Assert-Startup $rejected 'SkipBuild never silently compiles missing services'
$script:installed=$script:required
$script:fail='compose start*'
$rejected=$false
try { Start-Platform } catch { $rejected=$true }
Assert-Startup $rejected 'A failed start cannot be reported as ready'
$rejected=$false
try { Start-Platform -Build -SkipBuild } catch { $rejected=$true }
Assert-Startup $rejected 'Conflicting startup options are rejected'
Remove-Item Function:docker
