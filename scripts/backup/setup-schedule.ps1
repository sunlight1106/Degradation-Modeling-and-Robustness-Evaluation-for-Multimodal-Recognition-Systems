param([string]$Time='03:30',[int]$Keep=7,[switch]$Remove,[string]$ExportRecoveryKey)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scheduleRoot=Join-Path $projectRoot '.runtime\backup-schedule'
$taskName='PersonalKnowledgeBackup-'+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($projectRoot)).Replace('=','').Replace('/','_').Replace('+','-')
if ($Remove) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue; $status=Join-Path $projectRoot '.runtime\backups\status.json'; if(Test-Path -LiteralPath $status){$data=Get-Content -LiteralPath $status -Raw|ConvertFrom-Json;$data|Add-Member -NotePropertyName scheduled -NotePropertyValue $false -Force;$data|Add-Member -NotePropertyName lastError -NotePropertyValue $null -Force;[IO.File]::WriteAllText($status,($data|ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))}; Write-Host 'Automatic backups disabled. Existing archives and recovery key retained.'; exit }
New-Item -ItemType Directory -Path $scheduleRoot -Force | Out-Null
$keyFile=Join-Path $scheduleRoot 'key.dpapi'
if (-not (Test-Path -LiteralPath $keyFile)) {
    $bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); try {$rng.GetBytes($bytes)} finally {$rng.Dispose()}
    $key=[Convert]::ToBase64String($bytes)
    ConvertTo-SecureString $key -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $keyFile
    $key=$null
}
# Inheritance removed: current owner and SYSTEM only, including any exported key.
function Protect-KeyFile([string]$Path) {
    $acl=New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true,$false)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($sid)
    foreach($id in @($sid,(New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($id,'FullControl','Allow')))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
Protect-KeyFile $keyFile
if ($ExportRecoveryKey) {
    $destination=[IO.Path]::GetFullPath($ExportRecoveryKey)
    $stream=[IO.File]::Open($destination,'CreateNew','Write','None');$stream.Dispose()
    Protect-KeyFile $destination
    $secure=Get-Content -LiteralPath $keyFile -Raw | ConvertTo-SecureString
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [IO.File]::WriteAllText($destination,[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)) } finally {[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
    Write-Host 'Recovery key exported. Move this file to independent secure storage.';exit
}
if ($Time -notmatch '^([01][0-9]|2[0-3]):[0-5][0-9]$' -or $Keep -lt 2 -or $Keep -gt 365) {throw 'Use HH:mm and Keep between 2 and 365.'}
$python=(Get-Command python -ErrorAction Stop).Source
& $python -c 'import cryptography'
if ($LASTEXITCODE -ne 0) {throw 'Install scripts/backup/requirements.txt first.'}
$action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\run-scheduled.ps1`" -Python `"$python`" -Keep $Keep"
$trigger=New-ScheduledTaskTrigger -Daily -At $Time
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
$backupStatusDirectory=Join-Path $projectRoot '.runtime\backups'
New-Item -ItemType Directory -Path $backupStatusDirectory -Force | Out-Null
$backupStatusPath=Join-Path $backupStatusDirectory 'status.json'
$backupState=if(Test-Path -LiteralPath $backupStatusPath){Get-Content -LiteralPath $backupStatusPath -Raw | ConvertFrom-Json}else{New-Object PSObject}
$backupState | Add-Member -NotePropertyName 'scheduled' -NotePropertyValue $true -Force
$backupState | Add-Member -NotePropertyName 'scheduledTime' -NotePropertyValue $Time -Force
[IO.File]::WriteAllText($backupStatusPath,($backupState|ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
Write-Host "Verified encrypted backup scheduled daily at $Time; keep $Keep archives. Missed runs resume when this Windows account is logged in."
