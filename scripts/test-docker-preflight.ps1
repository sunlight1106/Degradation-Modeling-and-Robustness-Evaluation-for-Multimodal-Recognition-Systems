$ErrorActionPreference='Stop'
. "$PSScriptRoot\ensure-docker.ps1"
# Mock process/engine probes, and use an isolated fixture directory exclusively.
function Test-DockerReady { return $false }
function Get-Process { param($Name,$ErrorAction) return @() }
$taskFixture=Join-Path (Resolve-Path "$PSScriptRoot\..\") ('.runtime\docker-preflight-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $taskFixture 'docker-secrets-engine') -Force | Out-Null
[IO.File]::WriteAllBytes((Join-Path $taskFixture 'docker-secrets-engine\engine.sock'),[byte[]]@())
Repair-DockerSockets -SkipStop -LocalData $taskFixture
if(Test-Path -LiteralPath (Join-Path $taskFixture 'docker-secrets-engine')){throw 'Known stale runtime directory was not quarantined'}
if(@(Get-ChildItem -LiteralPath $taskFixture -Filter '*.startup-backup-*').Count -ne 1){throw 'Runtime quarantine backup was not retained'}
New-Item -ItemType Directory -Path (Join-Path $taskFixture 'docker-secrets-engine') -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $taskFixture 'docker-secrets-engine\important.txt'),'preserve synthetic fixture')
$refused=$false;try {Repair-DockerSockets -SkipStop -LocalData $taskFixture}catch{$refused=$true}
if(-not $refused -or -not (Test-Path -LiteralPath (Join-Path $taskFixture 'docker-secrets-engine\important.txt'))){throw 'Unexpected data was not preserved'}
Write-Host 'PASS: known zero-byte sockets quarantined; unknown files preserved. Fixture retained in .runtime.'
