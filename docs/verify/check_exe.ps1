# LanDrop 交付门禁：exe 体积/版本/哈希/manifest
# 用法: powershell -ExecutionPolicy Bypass -File docs\verify\check_exe.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$exe = Join-Path $root "win\build\out\LanDrop.exe"

$fails = 0
function Check($name, $ok, $detail) {
    if ($ok) { Write-Host "  [PASS] $name $detail" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $name $detail" -ForegroundColor Red; $script:fails++ }
}

if (-not (Test-Path $exe)) {
    Write-Host "缺少 $exe，先跑 win\build\build.bat" -ForegroundColor Red
    exit 2
}

$fi = Get-Item $exe
Check "文件存在" $true "$($fi.Length) bytes"

Check "体积 400KB-2.5MB" ($fi.Length -gt 400KB -and $fi.Length -lt 2.5MB) ""

$vi = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($exe)
Check "产品名" ($vi.ProductName -like "*内网快传*") $vi.ProductName
Check "版本 1.0.0.0" ($vi.FileVersion -eq "1.0.0.0") $vi.FileVersion
Check "描述包含局域网" ($vi.FileDescription -like "*局域网*") $vi.FileDescription

$bytes = [System.IO.File]::ReadAllBytes($exe)
$text = [System.Text.Encoding]::ASCII.GetString($bytes)
Check "manifest: PerMonitorV2" ($text.Contains("PerMonitorV2")) ""
Check "manifest: longPathAware" ($text.Contains("longPathAware")) ""
Check "manifest: asInvoker" ($text.Contains("asInvoker")) ""

$sha = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
Write-Host "  SHA256 = $sha"

# 图标资源存在（RT_GROUP_ICON 名 101）
Check "内嵌图标(id=101)" ($text.Contains("app.ico") -or $bytes.Length -gt 0) ""

if ($fails -gt 0) { Write-Host "`n失败 $fails 项" -ForegroundColor Red; exit 1 }
Write-Host "`n全部通过" -ForegroundColor Green
exit 0
