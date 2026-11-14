@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "PATH=C:\application\qt\Tools\mingw1310_64\bin;%PATH%"
if not exist "build\out"  mkdir "build\out"
if not exist "build\_tmp" mkdir "build\_tmp"

where g++ >nul 2>nul || ( echo [FAIL] g++ not found in PATH & exit /b 1 )
where windres >nul 2>nul || ( echo [FAIL] windres not found in PATH & exit /b 1 )

echo [1/3] resources
windres --input src\app.rc --output build\_tmp\app.res --output-format=coff
if errorlevel 1 ( echo [FAIL] windres & exit /b 1 )

set "SRCS="
for %%F in (main.cpp cli.cpp ui_main.cpp ui_views.cpp ui_settings.cpp theme.cpp util.cpp json.cpp ^
            log.cpp config.cpp net.cpp discovery.cpp jobs.cpp protocol.cpp ^
            storage.cpp tcp_server.cpp tcp_client.cpp speed.cpp ^
            firewall.cpp shellx.cpp tray.cpp) do (
  if exist "src\%%F" ( set "SRCS=!SRCS! src\%%F" ) else ( echo       skip src\%%F )
)

echo [2/3] compile + link
g++ -std=c++17 -O2 -s -municode -mwindows -DUNICODE -D_UNICODE ^
    -DWINVER=0x0A00 -D_WIN32_WINNT=0x0A00 -DNTDDI_VERSION=0x0A000000 ^
    -Wall -Wextra -Wno-unused-parameter -Wno-cast-function-type -Wno-missing-field-initializers ^
    !SRCS! build\_tmp\app.res -o build\out\LanDrop.exe ^
    -lws2_32 -lgdiplus -lgdi32 -luser32 -lshell32 -lshlwapi -lcomctl32 ^
    -lole32 -loleaut32 -luuid -liphlpapi -ladvapi32 -ldwmapi -lcomdlg32 -lversion ^
    -static -static-libgcc -static-libstdc++ ^
    -Wl,--no-insert-timestamp -Wl,--gc-sections -ffunction-sections -fdata-sections
if errorlevel 1 ( echo [FAIL] link & exit /b 1 )

echo [3/3] artifact
for %%F in ("build\out\LanDrop.exe") do echo       %%~fF  %%~zF bytes
endlocal & exit /b 0
