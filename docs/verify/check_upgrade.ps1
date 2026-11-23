# LanDrop 覆盖安装门禁：连续安装两次，断言"只有一条卸载条目 + 配置保留"
# 用法（管理员）: powershell -ExecutionPolicy Bypass -File docs\verify\check_upgrade.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$setup = Join-Path $root "win\dist\LanDropSetup-1.1.0.exe"
$cfgDir = Join-Path $env:APPDATA "LanDrop"
$cfg = Join-Path $cfgDir "config.ini"

$fails = 0
function Check($name, $ok, $detail) {
    if ($ok) { Write-Host "  [PASS] $name $detail" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $name $detail" -ForegroundColor Red; $script:fails++ }
}

if (-not (Test-Path $setup)) { Write-Host "缺少安装包 $setup" -ForegroundColor Red; exit 2 }

# 1) 先造一份"用户已改过"的配置，验证升级不覆盖
New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
@"
[LanDrop]
cfgver=1
deviceName=UPGRADE_TEST
dataConns=3
autoAcceptTrusted=1
"@ | Set-Content -Path $cfg -Encoding UTF8

Write-Host "`n[1] 第一次静默安装"
Start-Process -FilePath $setup -ArgumentList "/VERYSILENT","/SUPPRESSMSGBOXES","/NORESTART","/CURRENTUSER" -Wait
Check "安装目录存在" (Test-Path (Join-Path $env:LOCALAPPDATA "Programs\LanDrop")) ""

Write-Host "`n[2] 第二次静默安装（覆盖升级）"
Start-Process -FilePath $setup -ArgumentList "/VERYSILENT","/SUPPRESSMSGBOXES","/NORESTART","/CURRENTUSER" -Wait

Write-Host "`n[3] 断言"
$entries = @()
Get-ChildItem "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue | ForEach-Object {
    $p = Get-ItemProperty $_.PSPath
    if ($p.DisplayName -like "*内网快传*") { $entries += $p }
}
Check "卸载条目只有一条" ($entries.Count -eq 1) "实际 $($entries.Count) 条"

$cfgText = Get-Content $cfg -Raw -ErrorAction SilentlyContinue
Check "用户配置未被覆盖" ($cfgText -match "UPGRADE_TEST") ""

$exe = Join-Path $env:LOCALAPPDATA "Programs\LanDrop\LanDrop.exe"
Check "主程序就位" (Test-Path $exe) ""

Write-Host "`n[4] 卸载"
$unins = Join-Path $env:LOCALAPPDATA "Programs\LanDrop\unins000.exe"
if (Test-Path $unins) {
    Start-Process -FilePath $unins -ArgumentList "/VERYSILENT","/SUPPRESSMSGBOXES","/NORESTART" -Wait
    Check "卸载后目录清空" (-not (Test-Path $exe)) ""
    Check "卸载后保留配置" (Test-Path $cfg) ""
}

if ($fails -gt 0) { Write-Host "`n失败 $fails 项" -ForegroundColor Red; exit 1 }
Write-Host "`n全部通过" -ForegroundColor Green
exit 0
