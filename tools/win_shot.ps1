# LanDrop Windows screenshot helper (PrintWindow based)
#
# Three hard-won requirements on this host:
#  1. SetProcessDpiAwarenessContext(-4) BEFORE any window call, otherwise
#     GetWindowRect returns virtualized coords and PrintWindow grabs only a corner.
#  2. PrintWindow(hdc, 2) rather than BitBlt so occluded windows still capture.
#  3. FindWindowW/FindWindowA both return 0 here even though the window class exists
#     (verified: EnumWindows lists LanDropMainWnd). So locate windows by class via
#     EnumWindows instead of FindWindow.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\win_shot.ps1 ^
#       -Out docs\verify\screenshots-1.3.0\40-win-graphite.png -Theme graphite
#   ... -Settings          # open the settings dialog instead of the main window
#   ... -Tab send          # click a tab (recv/send/speed)
#   ... -Dpi 144 -Width 1280 -Height 720
param(
    [string]$Out = "shot.png",
    [string]$Theme = "",
    [int]$Scrim = -1,
    [string]$Animations = "",
    [string]$Tab = "",
    [int]$Width = 0,
    [int]$Height = 0,
    [int]$Dpi = 0,
    [int]$DelayMs = 1800,
    [switch]$Settings
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr h, StringBuilder sb, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint f);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool PostMessageW(IntPtr h, uint msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

  public static IntPtr FindByClass(string cls, uint pid) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((h, l) => {
      uint p; GetWindowThreadProcessId(h, out p);
      if (pid != 0 && p != pid) return true;
      var sb = new StringBuilder(256);
      GetClassNameW(h, sb, 256);
      if (sb.ToString() == cls) { found = h; return false; }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Ini2 {
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode)]
  public static extern long WritePrivateProfileStringW(string sec, string key, string val, string file);
}
"@

[void][W]::SetProcessDpiAwarenessContext([IntPtr](-4))

$root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$outPath = if ([System.IO.Path]::IsPathRooted($Out)) { $Out } else { Join-Path $root $Out }
$exe = Join-Path $root "win\build\out\LanDrop.exe"
if (-not (Test-Path $exe)) { Write-Host "missing $exe"; exit 2 }

$cfgDir = Join-Path $env:APPDATA "LanDrop"
$cfg = Join-Path $cfgDir "config.ini"
New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null

# Back up the user's real config and restore it afterwards (never leave test state behind)
$bak = Join-Path $cfgDir "config.ini.verify.bak"
$hadCfg = Test-Path $cfg
if ($hadCfg) { Copy-Item $cfg $bak -Force }

$proc = $null
try {
    if ($Theme -or $Scrim -ge 0 -or $Animations) {
        if ($Theme)       { [void][Ini2]::WritePrivateProfileStringW("LanDrop","theme",$Theme,$cfg) }
        if ($Scrim -ge 0) { [void][Ini2]::WritePrivateProfileStringW("LanDrop","themeScrim","$Scrim",$cfg) }
        if ($Animations)  { [void][Ini2]::WritePrivateProfileStringW("LanDrop","themeAnimations",$Animations,$cfg) }
    }

    Get-Process LanDrop -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500

    $exeArgs = ""
    if ($Dpi -gt 0) { $exeArgs = "--dpi $Dpi" }
    if ($exeArgs) { $proc = Start-Process -FilePath $exe -ArgumentList $exeArgs -PassThru }
    else { $proc = Start-Process -FilePath $exe -PassThru }
    Start-Sleep -Milliseconds $DelayMs

    $h = [IntPtr]::Zero
    for ($i = 0; $i -lt 40; $i++) {
        $h = [W]::FindByClass("LanDropMainWnd", [uint32]$proc.Id)
        if ($h -ne [IntPtr]::Zero) { break }
        Start-Sleep -Milliseconds 150
    }
    if ($h -eq [IntPtr]::Zero) {
        Write-Host "main window not found"
        exit 3
    }

    if ($Width -gt 0 -and $Height -gt 0) {
        [void][W]::SetWindowPos($h, [IntPtr]::Zero, 80, 80, $Width, $Height, 0x0004)
        Start-Sleep -Milliseconds 600
    }
    [void][W]::ShowWindow($h, 5)
    [void][W]::SetForegroundWindow($h)
    Start-Sleep -Milliseconds 700

    # Optional: switch tab via tab hit rects (click by proportion of the right pane)
    if ($Tab) {
        $cr = New-Object W+RECT
        [void][W]::GetClientRect($h, [ref]$cr)
        $cw = $cr.Right - $cr.Left
        $ch = $cr.Bottom - $cr.Top
        $x = [int]($cw * 0.34)
        $y = [int]($ch * 0.115)
        if ($Tab -eq "send")  { $x = [int]($cw * 0.40) }
        if ($Tab -eq "speed") { $x = [int]($cw * 0.455) }
        [void][W]::PostMessageW($h, 0x0201, [IntPtr]1, [IntPtr]([int]($x + ($y -shl 16))))
        Start-Sleep -Milliseconds 150
        [void][W]::PostMessageW($h, 0x0202, [IntPtr]0, [IntPtr]([int]($x + ($y -shl 16))))
        Start-Sleep -Milliseconds 900
    }

    # Optional: open the settings dialog via WM_COMMAND(IDM_SETTINGS=1003).
    if ($Settings) {
        [void][W]::PostMessageW($h, 0x0111, [IntPtr]1003, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 2500
        $sh = [IntPtr]::Zero
        for ($i = 0; $i -lt 30; $i++) {
            $sh = [W]::FindByClass("LanDropSettingsWnd", [uint32]$proc.Id)
            if ($sh -ne [IntPtr]::Zero) { break }
            Start-Sleep -Milliseconds 150
        }
        if ($sh -ne [IntPtr]::Zero) {
            [void][W]::ShowWindow($sh, 5)
            [void][W]::SetForegroundWindow($sh)
            Start-Sleep -Milliseconds 900
            $h = $sh
        } else {
            Write-Host "settings window not found after WM_COMMAND"
        }
    }

    $r = New-Object W+RECT
    [void][W]::GetWindowRect($h, [ref]$r)
    $w = $r.Right - $r.Left
    $hh = $r.Bottom - $r.Top
    if ($w -le 0 -or $hh -le 0) { Write-Host "bad window size $w x $hh"; exit 4 }

    $bmp = New-Object System.Drawing.Bitmap $w, $hh
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $hdc = $gfx.GetHdc()
    [void][W]::PrintWindow($h, $hdc, 2)
    $gfx.ReleaseHdc($hdc)
    $gfx.Dispose()

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outPath) | Out-Null
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "shot: $outPath  ($w x $hh)"

    $log = Get-ChildItem (Join-Path $env:LOCALAPPDATA "LanDrop\logs") -Filter *.log -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($log) {
        $tail = Get-Content $log.FullName -Tail 80 -ErrorAction SilentlyContinue |
            Select-String -Pattern "theme: apply|\banim\b" | Select-Object -Last 3
        if ($tail) { $tail | ForEach-Object { Write-Host "  log| $_" } }
    }
}
finally {
    if ($proc) { $proc | Stop-Process -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 300
    Get-Process LanDrop -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    if ($hadCfg) { Copy-Item $bak $cfg -Force; Remove-Item $bak -Force -ErrorAction SilentlyContinue }
}
