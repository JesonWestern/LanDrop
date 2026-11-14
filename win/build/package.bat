@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" (
  echo [FAIL] ISCC.exe not found
  exit /b 1
)

if not exist "build\out\LanDrop.exe" (
  echo [FAIL] build first: build\build.bat
  exit /b 1
)

for %%F in ("build\out\LanDrop.exe") do echo       source %%~zF bytes

echo [1/2] compile installer
"%ISCC%" /Qp installer\LanDrop.iss
if errorlevel 1 ( echo [FAIL] iscc & exit /b 1 )

echo [2/2] artifact
if exist "dist" for %%F in ("dist\LanDropSetup-*.exe") do echo       %%~fF  %%~zF bytes
endlocal & exit /b 0
