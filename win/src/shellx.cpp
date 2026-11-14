#include "app.h"

#include <shlobj.h>
#include <shlwapi.h>

namespace shellx {

namespace {

const wchar_t* kMenuText = L"用内网快传发送";
const wchar_t* kKeyFiles = L"Software\\Classes\\*\\shell\\LanDrop";
const wchar_t* kKeyDirs = L"Software\\Classes\\Directory\\shell\\LanDrop";
const wchar_t* kKeyBg = L"Software\\Classes\\Directory\\Background\\shell\\LanDrop";

std::wstring ExePath() {
    wchar_t buf[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, buf, (DWORD)(sizeof(buf) / sizeof(buf[0])));
    if (n == 0) return L"";
    return std::wstring(buf, n);
}

bool WriteKey(const wchar_t* sub, const std::wstring& exe) {
    HKEY hk = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, sub, 0, nullptr, 0, KEY_SET_VALUE | KEY_WRITE, nullptr,
                        &hk, nullptr) != ERROR_SUCCESS) {
        return false;
    }
    // 中文菜单名用 Unicode 值
    RegSetValueExW(hk, L"MUIVerb", 0, REG_SZ, (const BYTE*)kMenuText,
                   (DWORD)((wcslen(kMenuText) + 1) * sizeof(wchar_t)));
    const wchar_t* icon = L"Icon";
    std::wstring iconVal = L"\"" + exe + L"\",0";
    RegSetValueExW(hk, icon, 0, REG_SZ, (const BYTE*)iconVal.c_str(),
                   (DWORD)((iconVal.size() + 1) * sizeof(wchar_t)));
    // 允许多选（最多 15 项）
    const wchar_t* ms = L"MultiSelectModel";
    const wchar_t* player = L"Player";
    RegSetValueExW(hk, ms, 0, REG_SZ, (const BYTE*)player,
                   (DWORD)((wcslen(player) + 1) * sizeof(wchar_t)));

    HKEY hcmd = nullptr;
    if (RegCreateKeyExW(hk, L"command", 0, nullptr, 0, KEY_SET_VALUE, nullptr, &hcmd, nullptr) ==
        ERROR_SUCCESS) {
        std::wstring cmd = L"\"" + exe + L"\" --send-many \"%1\"";
        RegSetValueExW(hcmd, nullptr, 0, REG_SZ, (const BYTE*)cmd.c_str(),
                       (DWORD)((cmd.size() + 1) * sizeof(wchar_t)));
        RegCloseKey(hcmd);
    }
    RegCloseKey(hk);
    return true;
}

void DeleteKeyTree(const wchar_t* sub) {
    RegDeleteTreeW(HKEY_CURRENT_USER, sub);
}

}  // namespace

bool InstallContextMenu() {
    const std::wstring exe = ExePath();
    if (exe.empty()) return false;
    WriteKey(kKeyFiles, exe);
    // 目录背景：把当前目录整体发送（%V 传目录路径）
    HKEY hk = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, kKeyBg, 0, nullptr, 0, KEY_SET_VALUE | KEY_WRITE,
                        nullptr, &hk, nullptr) == ERROR_SUCCESS) {
        RegSetValueExW(hk, L"MUIVerb", 0, REG_SZ, (const BYTE*)L"用内网快传发送此文件夹",
                       (DWORD)((wcslen(L"用内网快传发送此文件夹") + 1) * sizeof(wchar_t)));
        const wchar_t* ms = L"MultiSelectModel";
        const wchar_t* single = L"Single";
        RegSetValueExW(hk, ms, 0, REG_SZ, (const BYTE*)single,
                       (DWORD)((wcslen(single) + 1) * sizeof(wchar_t)));
        HKEY hcmd = nullptr;
        if (RegCreateKeyExW(hk, L"command", 0, nullptr, 0, KEY_SET_VALUE, nullptr, &hcmd, nullptr) ==
            ERROR_SUCCESS) {
            std::wstring cmd = L"\"" + exe + L"\" --send-many \"%V\"";
            RegSetValueExW(hcmd, nullptr, 0, REG_SZ, (const BYTE*)cmd.c_str(),
                           (DWORD)((cmd.size() + 1) * sizeof(wchar_t)));
            RegCloseKey(hcmd);
        }
        RegCloseKey(hk);
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    logx::Line("shell: context menu installed");
    return true;
}

bool RemoveContextMenu() {
    DeleteKeyTree(kKeyFiles);
    DeleteKeyTree(kKeyDirs);
    DeleteKeyTree(kKeyBg);
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
    return true;
}

bool IsInstalled() {
    HKEY hk = nullptr;
    bool ok = RegOpenKeyExW(HKEY_CURRENT_USER, kKeyFiles, 0, KEY_READ, &hk) == ERROR_SUCCESS;
    if (hk) RegCloseKey(hk);
    return ok;
}

}  // namespace shellx
