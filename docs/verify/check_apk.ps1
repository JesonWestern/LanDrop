# LanDrop Android 交付门禁：版本 / SDK / 签名 / Manifest 关键声明 / SHA-256
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\verify\check_apk.ps1
#   powershell ... -File docs\verify\check_apk.ps1 -Apk <path> -ExpectedSha256 <hex>
#   powershell ... -File docs\verify\check_apk.ps1 -Apk app-debug.apk -CheckDebug -SkipSha256
#
# SDK 路径依次取 -SdkRoot / ANDROID_HOME / ANDROID_SDK_ROOT / android/local.properties /
# %LOCALAPPDATA%\Android\Sdk。

param(
    [string]$Apk = "",
    [int]$ExpectedVersionCode = 3,
    [string]$ExpectedVersionName = "1.2.0",
    [int]$ExpectedMinSdk = 26,
    [int]$ExpectedTargetSdk = 36,
    [string]$ExpectedCertSha256 = "2aad010903feb953c53bf4aa7741863acf14850ce9b5c768a32e18c02c23c80f",
    [string]$ExpectedSha256 = "3EBB30358580A78E3C833D2248D1163FB3AE74A1E322E23A604FB664CE01232D",
    [switch]$SkipSha256,
    [switch]$CheckDebug,
    [string]$SdkRoot = ""
)

$ErrorActionPreference = "Stop"
$script:failed = 0

function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) {
        Write-Host "  [PASS] $name" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $name $detail" -ForegroundColor Red
        $script:failed++
    }
}

if ([string]::IsNullOrWhiteSpace($Apk)) {
    $Apk = Join-Path $PSScriptRoot "..\..\android\app\build\outputs\apk\release\app-release.apk"
}
$Apk = (Resolve-Path $Apk).Path

if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    if ($env:ANDROID_HOME) {
        $SdkRoot = $env:ANDROID_HOME
    } elseif ($env:ANDROID_SDK_ROOT) {
        $SdkRoot = $env:ANDROID_SDK_ROOT
    } else {
        $lp = Join-Path $PSScriptRoot "..\..\android\local.properties"
        if (Test-Path $lp) {
            $line = Get-Content $lp | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
            if ($line) {
                $SdkRoot = ($line -replace "^sdk.dir=", "") -replace "\\\\", "\" -replace "\\:", ":"
            }
        }
    }
}
if ([string]::IsNullOrWhiteSpace($SdkRoot)) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
if (-not (Test-Path $SdkRoot)) {
    Write-Host "找不到 Android SDK：$SdkRoot（可用 -SdkRoot 指定）" -ForegroundColor Red
    exit 2
}

$btDir = Get-ChildItem (Join-Path $SdkRoot "build-tools") -Directory |
    Sort-Object { [int](($_.Name -split "\.")[0]) } -Descending | Select-Object -First 1
$aapt2 = Join-Path $btDir.FullName "aapt2.exe"
$apksigner = Join-Path $btDir.FullName "apksigner.bat"
if (-not (Test-Path $aapt2) -or -not (Test-Path $apksigner)) {
    Write-Host "build-tools 缺少 aapt2/apksigner：$($btDir.FullName)" -ForegroundColor Red
    exit 2
}

Write-Host "APK : $Apk"
Write-Host "SDK : $($btDir.FullName)"
Write-Host ""

$badging = (& $aapt2 dump badging $Apk 2>&1 | Out-String)
$tree = (& $aapt2 dump xmltree $Apk --file AndroidManifest.xml 2>&1 | Out-String)
$resTable = (& $aapt2 dump resources $Apk 2>&1 | Out-String)

# ---- 版本 / SDK / 包名 ----
Check "package = com.landrop.transfer" ($badging -match "package: name='com\.landrop\.transfer'")
Check "versionCode = $ExpectedVersionCode" ($badging -match ("versionCode='" + $ExpectedVersionCode + "'"))
Check "versionName = $ExpectedVersionName" ($badging -match ("versionName='" + [regex]::Escape($ExpectedVersionName) + "'"))
Check "minSdkVersion = $ExpectedMinSdk" ($badging -match ("minSdkVersion:'" + $ExpectedMinSdk + "'"))
Check "targetSdkVersion = $ExpectedTargetSdk" ($badging -match ("targetSdkVersion:'" + $ExpectedTargetSdk + "'"))

# ---- 权限 ----
Check "REQUEST_INSTALL_PACKAGES（点 APK 走安装器）" ($badging -match "REQUEST_INSTALL_PACKAGES")
Check "MANAGE_EXTERNAL_STORAGE（文件浏览）" ($badging -match "MANAGE_EXTERNAL_STORAGE")
Check "READ_EXTERNAL_STORAGE maxSdkVersion=32" ($badging -match "READ_EXTERNAL_STORAGE' maxSdkVersion='32'")
Check "WRITE_EXTERNAL_STORAGE maxSdkVersion=29" ($badging -match "WRITE_EXTERNAL_STORAGE' maxSdkVersion='29'")
Check "FOREGROUND_SERVICE_DATA_SYNC（前台服务类型）" ($badging -match "FOREGROUND_SERVICE_DATA_SYNC")

# ---- FileProvider / queries ----
Check "FileProvider 已声明" ($tree -match "androidx\.core\.content\.FileProvider")
Check "FileProvider authority 正确" ($tree -match "com\.landrop\.transfer\.fileprovider")
Check "FileProvider exported=false" ($tree -match "exported\(0x01010010\)=false")
Check "FileProvider grantUriPermissions=true" ($tree -match "grantUriPermissions\(0x0101001b\)=true")
Check "file_paths 资源存在且已绑定" (($resTable -match "xml/file_paths") -and ($tree -match "FILE_PROVIDER_PATHS"))
Check "provider 未暴露 root-path（最小授权）" (-not ($tree -match 'root-path'))
Check "queries 已声明" ($tree -match "E: queries")
Check "queries 覆盖 content scheme" ($tree -match 'scheme\(0x01010027\)="content"')

# ---- debug / release 差异 ----
if ($CheckDebug) {
    Check "debug 变体包含 DebugCommandReceiver" ($tree -match "DebugCommandReceiver")
    Check "debug 变体 debuggable=true" ($badging -match "application-debuggable")
} else {
    Check "release 不含调试广播入口" (-not ($tree -match "DebugCommandReceiver"))
    Check "release 不可调试" (-not ($badging -match "application-debuggable"))
}

# ---- 签名 / 哈希 ----
$certs = (& $apksigner verify --print-certs $Apk 2>&1 | Out-String)
Check "签名证书 SHA-256 与 1.0/1.1 一致（覆盖安装不丢数据）" ($certs -match [regex]::Escape($ExpectedCertSha256)) "证书不匹配"
$hash = (Get-FileHash $Apk -Algorithm SHA256).Hash
if (-not $SkipSha256 -and -not [string]::IsNullOrWhiteSpace($ExpectedSha256)) {
    Check "SHA-256 与交付说明一致" ($hash -eq $ExpectedSha256.ToUpper()) "actual=$hash"
}
$size = (Get-Item $Apk).Length
Write-Host ""
Write-Host "大小    : $size 字节"
Write-Host "SHA-256 : $hash"
Write-Host ""
if ($script:failed -eq 0) {
    Write-Host "RESULT: PASS（$Apk）" -ForegroundColor Green
    exit 0
} else {
    Write-Host "RESULT: FAIL（$script:failed 项）" -ForegroundColor Red
    exit 1
}
