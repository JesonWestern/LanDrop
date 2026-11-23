; LanDrop 内网快传 — Inno Setup 6 脚本
; 关键约束：AppId 必须固定，否则覆盖安装会被当成两个产品

#define MyAppName "内网快传"
#define MyAppNameEn "LanDrop"
#define MyAppVersion "1.3.0"
#define MyAppPublisher "LanDrop"
#define MyAppExeName "LanDrop.exe"
#define MyAppId "{{B1E4C7D2-9A3F-4E58-8C21-7D6F0A5B3E94}"

[Setup]
AppId={#MyAppId}
AppName={#MyAppName} {#MyAppNameEn}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\LanDrop
DefaultGroupName={#MyAppName}
UsePreviousAppDir=yes
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\dist
OutputBaseFilename=LanDropSetup-{#MyAppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName} {#MyAppNameEn}
SetupIconFile=..\assets\app.ico

[Languages]
Name: "zh"; MessagesFile: "ChineseSimplified.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "ctxmenu"; Description: "在资源管理器右键菜单中加入「用内网快传发送」"; GroupDescription: "集成"

[Files]
Source: "..\build\out\LanDrop.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\assets\installer\README.txt"; DestDir: "{app}"; Flags: ignoreversion isreadme

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
; 安装器已是管理员权限，直接写规则，避免首次运行再弹 UAC
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""LanDrop TCP"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""LanDrop UDP"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""LanDrop Program"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""LanDrop TCP"" dir=in action=allow protocol=TCP localport=47821 profile=private,public"; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""LanDrop UDP"" dir=in action=allow protocol=UDP localport=47820 profile=private,public"; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""LanDrop Program"" dir=in action=allow program=""{app}\{#MyAppExeName}"" enable=yes profile=private,public"; Flags: runhidden waituntilterminated
Filename: "{app}\{#MyAppExeName}"; Parameters: "--setup-ctxmenu"; Flags: runhidden waituntilterminated; Tasks: ctxmenu
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""LanDrop TCP""";     Flags: runhidden; RunOnceId: "DelFwTcp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""LanDrop UDP""";     Flags: runhidden; RunOnceId: "DelFwUdp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""LanDrop Program"""; Flags: runhidden; RunOnceId: "DelFwPrg"
Filename: "{app}\{#MyAppExeName}"; Parameters: "--uninstall-ctxmenu"; Flags: runhidden; RunOnceId: "DelCtx"

[UninstallDelete]
Type: files; Name: "{app}\*.log"
Type: dirifempty; Name: "{app}"

[Code]
// 覆盖安装后校正开机自启路径（如果用户开过自启）
procedure CurStepChanged(CurStep: TSetupStep);
var
  RunValue: String;
begin
  if CurStep = ssPostInstall then
  begin
    if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'LanDrop', RunValue) then
      RegWriteStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'LanDrop',
        '"' + ExpandConstant('{app}\LanDrop.exe') + '" --tray');
  end;
end;

// 卸载时询问是否保留配置与历史；静默卸载一律保留
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  D: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    D := ExpandConstant('{userappdata}\LanDrop');
    if DirExists(D) and (not UninstallSilent) then
      if MsgBox('是否保留传输历史与设置？' + #13#10 + '选择「否」将一并删除。',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON1) = IDNO then
        DelTree(D, True, True, True);
    DelTree(ExpandConstant('{localappdata}\LanDrop\tmp'), True, True, True);
  end;
end;

