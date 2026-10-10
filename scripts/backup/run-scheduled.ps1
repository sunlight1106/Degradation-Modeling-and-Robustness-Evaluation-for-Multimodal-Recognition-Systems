param([string]$Python='python',[int]$Keep=7)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pointer=[IntPtr]::Zero
try {
    $secure=Get-Content -LiteralPath (Join-Path $projectRoot '.runtime\backup-schedule\key.dpapi') -Raw | ConvertTo-SecureString
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $env:PKB_BACKUP_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    Set-Location $projectRoot
    & $Python (Join-Path $PSScriptRoot 'scheduled_backup.py') --root $projectRoot --keep $Keep *> (Join-Path $projectRoot '.runtime\backup-schedule\last-run.log')
    if ($LASTEXITCODE -ne 0) {throw 'Scheduled backup failed. Check the private task log.'}
} catch {
    $statusPath=Join-Path $projectRoot '.runtime\backups\status.json'
    $state=if(Test-Path -LiteralPath $statusPath){Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json}else{New-Object PSObject}
    $state | Add-Member -NotePropertyName 'lastError' -NotePropertyValue 'Automatic backup failed. Check the private task log.' -Force
    $state | Add-Member -NotePropertyName 'lastAttempt' -NotePropertyValue ([datetime]::UtcNow.ToString('o')) -Force
    [IO.File]::WriteAllText($statusPath,($state|ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
    throw
} finally {Remove-Item Env:PKB_BACKUP_PASSWORD -ErrorAction SilentlyContinue;if($pointer -ne [IntPtr]::Zero){[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}}
