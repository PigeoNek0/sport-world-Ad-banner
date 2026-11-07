param([Parameter(Mandatory=$true)][string]$Apk,[string]$Adb)
if (-not $Adb) { $Adb = Join-Path $PSScriptRoot '..\tools\platform-tools\adb.exe' }
$Apk = (Resolve-Path $Apk).Path
& $Adb start-server 2>&1 | Out-Null
$dev = @(& $Adb devices | Select-String '^\S+\s+device$')
if ($dev.Count -ne 1) { Write-Host "ERR: 需要恰好一台设备，当前 $($dev.Count)"; exit 1 }
$log = Join-Path $env:TEMP ("adbinst_" + [guid]::NewGuid().ToString('N') + ".txt")
$p = Start-Process -FilePath $Adb -ArgumentList @('install','-r','-d',$Apk) -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError ($log + '.err')
$deadline = (Get-Date).AddSeconds(180)
$tapped = 0
while (-not $p.HasExited -and (Get-Date) -lt $deadline) {
    $w = (& $Adb shell dumpsys window 2>$null) -join "`n"
    if ($w -match 'AdbInstallActivity') {
        & $Adb shell input tap 315 2438 2>$null | Out-Null
        $tapped++
        Start-Sleep -Milliseconds 600
        & $Adb shell input tap 315 2438 2>$null | Out-Null
    }
    Start-Sleep -Milliseconds 500
}
if (-not $p.HasExited) { $p.Kill(); Write-Host "TIMEOUT" }
$out = (Get-Content $log -Raw -ErrorAction SilentlyContinue)
$err = (Get-Content ($log + '.err') -Raw -ErrorAction SilentlyContinue)
Remove-Item $log,($log + '.err') -ErrorAction SilentlyContinue
Write-Host ("tap次数: " + $tapped)
Write-Host ("stdout: " + ($out -replace "`r?`n", " ").Trim())
if ($err) { Write-Host ("stderr: " + ($err -replace "`r?`n", " ").Trim()) }
if (($out + $err) -match 'Success') { exit 0 } else { exit 2 }
