#include "app.h"
#include "json.h"

#include <algorithm>
#include <cstdio>
#include <vector>

namespace storage {

namespace {

bool WriteTextFile(const std::wstring& path, const std::string& data) {
    HANDLE h = CreateFileW(util::AddLong(path).c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    bool ok = WriteFile(h, data.data(), (DWORD)data.size(), &written, nullptr) != 0 &&
              written == data.size();
    CloseHandle(h);
    return ok;
}

bool ReadTextFile(const std::wstring& path, std::string* out) {
    HANDLE h = CreateFileW(util::AddLong(path).c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER sz{};
    if (!GetFileSizeEx(h, &sz) || sz.QuadPart > 16 * 1024 * 1024) {
        CloseHandle(h);
        return false;
    }
    out->resize((size_t)sz.QuadPart);
    DWORD got = 0;
    bool ok = out->empty() || (ReadFile(h, &(*out)[0], (DWORD)out->size(), &got, nullptr) && got == out->size());
    CloseHandle(h);
    return ok;
}

uint64_t FileKey(const std::string& peerId, const std::wstring& rel, uint64_t size, uint64_t mtime,
                 uint32_t chunkBytes) {
    js::Writer w;
    w.Obj();
    w.Key("p").S(peerId);
    w.Key("r").WS(rel);
    w.Key("s").U(size);
    w.Key("m").U(mtime);
    w.Key("c").I((long long)chunkBytes);
    w.End();
    const std::string s = w.Str();
    return util::Fnv1a64(s.data(), s.size());
}

}  // namespace

std::wstring MakeJobKey(const std::string& peerId, const std::vector<FileEntry>& files,
                        uint32_t chunkBytes) {
    std::vector<uint64_t> keys;
    keys.reserve(files.size());
    for (const auto& f : files) {
        keys.push_back(FileKey(peerId, f.rel, f.size, f.mtime, chunkBytes));
    }
    std::sort(keys.begin(), keys.end());
    return util::Hex16(util::Fnv1a64(keys.data(), keys.size() * sizeof(uint64_t)));
}

bool Prepare(const std::shared_ptr<Job>& j, Layout* out) {
    if (!j || !out) return false;
    Layout lay;
    lay.root = util::JoinPath(g_app.cfg.tmpDir, j->jobKey.empty() ? L"unknown" : j->jobKey);
    lay.dataDir = util::JoinPath(lay.root, L"data");
    lay.meta = util::JoinPath(lay.root, L"meta.json");
    if (!util::EnsureDir(lay.dataDir)) {
        logx::Line("storage: cannot create %s", util::Narrow(lay.dataDir).c_str());
        return false;
    }
    j->tmpDir = lay.root;
    *out = lay;
    return true;
}

bool SaveMeta(const std::shared_ptr<Job>& j, const Layout& lay) {
    if (!j) return false;
    js::Writer w;
    w.Obj();
    w.Key("jobKey").WS(j->jobKey);
    w.Key("peerId").S(j->dev.id);
    w.Key("chunkBytes").I((long long)proto::kChunkBytes);
    w.Key("totalBytes").U(j->totalBytes);
    w.Key("files").Arr();
    for (const auto& f : j->files) {
        w.Obj();
        w.Key("rel").WS(f.rel);
        w.Key("size").U(f.size);
        w.Key("mtime").U(f.mtime);
        w.Key("isDir").B(f.isDir);
        w.Key("bits").Arr();
        for (uint32_t word : f.doneBits) w.I((long long)word);
        w.End();
        w.End();
    }
    w.End();
    w.End();
    return WriteTextFile(lay.meta, w.Str());
}

bool LoadMeta(const std::shared_ptr<Job>& j, const Layout& lay) {
    if (!j) return false;
    std::string text;
    if (!ReadTextFile(lay.meta, &text)) return false;
    js::Value root;
    if (!js::Parse(text, &root)) return false;
    if (root.GetStr("jobKey") != util::Narrow(j->jobKey)) return false;
    const js::Value* arr = root.GetArr("files");
    if (!arr) return false;
    // 按 rel 匹配（清单顺序可能不同）
    for (const auto& item : arr->arr) {
        if (item.type != js::Value::Obj) continue;
        const std::wstring rel = item.GetWStr("rel");
        const uint64_t size = (uint64_t)item.GetInt("size", 0);
        for (auto& f : j->files) {
            if (f.rel != rel || f.size != size) continue;
            const js::Value* bits = item.GetArr("bits");
            if (!bits) break;
            f.InitBits();
            size_t i = 0;
            uint32_t count = 0;
            for (const auto& wv : bits->arr) {
                if (i >= f.doneBits.size()) break;
                uint32_t word = (uint32_t)wv.num;
                f.doneBits[i++] = word;
                for (int b = 0; b < 32; ++b) {
                    if (word & (1u << b)) {
                        const uint32_t ci = (uint32_t)((i - 1) * 32 + b);
                        if (ci < f.chunkCount) ++count;
                    }
                }
            }
            f.doneChunks = count;
            break;
        }
    }
    return true;
}

std::wstring PartPathFor(const Layout& lay, const std::wstring& rel) {
    return util::JoinPath(lay.dataDir, rel) + L".part";
}

bool EnsureParent(const std::wstring& filePath) {
    return util::EnsureDir(util::ParentOf(filePath));
}

bool CommitFile(const std::wstring& partPath, const std::wstring& finalPath) {
    if (!EnsureParent(finalPath)) return false;
    std::wstring p = util::AddLong(partPath);
    std::wstring f = util::AddLong(finalPath);
    if (MoveFileExW(p.c_str(), f.c_str(), MOVEFILE_WRITE_THROUGH)) return true;
    const DWORD e = GetLastError();
    if (e != ERROR_NOT_SAME_DEVICE && e != ERROR_FILE_EXISTS && e != ERROR_ALREADY_EXISTS) {
        logx::Line("storage: MoveFileEx %lu", e);
    }
    // 跨盘：显式拷贝 + 删源（可取消、可进度）
    BOOL cancel = FALSE;
    if (!CopyFileExW(p.c_str(), f.c_str(), nullptr, nullptr, &cancel, 0)) {
        logx::Line("storage: CopyFileEx %lu", GetLastError());
        return false;
    }
    if (!DeleteFileW(p.c_str())) logx::Line("storage: delete part failed %lu", GetLastError());
    return true;
}

bool MoveToSaveDir(const std::wstring& src, const std::wstring& dstDir, std::wstring* outPath) {
    if (src.empty() || dstDir.empty()) return false;
    if (!util::EnsureDir(dstDir)) return false;
    const bool isDir = util::IsDirPath(src);
    std::wstring dst;
    if (isDir) {
        dst = util::UniquePath(dstDir, util::FileNameOf(src), L"");
    } else {
        dst = util::UniquePath(dstDir, util::BaseOf(src), util::ExtOf(src));
    }
    if (!MoveFileExW(util::AddLong(src).c_str(), util::AddLong(dst).c_str(),
                     MOVEFILE_COPY_ALLOWED | MOVEFILE_WRITE_THROUGH)) {
        DWORD e = GetLastError();
        logx::Line("storage: move to save dir failed %lu (%s -> %s)", e,
                   util::Narrow(src).c_str(), util::Narrow(dst).c_str());
        return false;
    }
    if (outPath) *outPath = dst;
    return true;
}

bool RemoveTree(const std::wstring& path) {
    if (path.empty() || !util::PathExists(path)) return true;
    std::wstring p = util::AddLong(path);
    if (!util::IsDirPath(path)) return DeleteFileW(p.c_str()) != 0;
    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileW((p + L"\\*").c_str(), &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
            std::wstring child = util::JoinPath(path, fd.cFileName);
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) RemoveTree(child);
            else DeleteFileW(util::AddLong(child).c_str());
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    return RemoveDirectoryW(p.c_str()) != 0;
}

void GcTemp(int days) {
    if (days <= 0) return;
    const uint64_t limitMs = (uint64_t)days * 24ull * 3600ull * 1000ull;
    std::wstring root = g_app.cfg.tmpDir;
    if (!util::IsDirPath(root)) return;
    FILETIME nowRaw;
    GetSystemTimeAsFileTime(&nowRaw);
    const uint64_t nowFt =
        (((uint64_t)nowRaw.dwHighDateTime << 32) | nowRaw.dwLowDateTime) / 10000ull;

    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileW((util::AddLong(root) + L"\\*").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return;
    int removed = 0;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        if (!wcscmp(fd.cFileName, L".") || !wcscmp(fd.cFileName, L"..")) continue;
        std::wstring child = util::JoinPath(root, fd.cFileName);
        WIN32_FILE_ATTRIBUTE_DATA ad{};
        if (!GetFileAttributesExW(util::AddLong(child).c_str(), GetFileExInfoStandard, &ad)) continue;
        uint64_t ms = (((uint64_t)ad.ftLastWriteTime.dwHighDateTime << 32) |
                       ad.ftLastWriteTime.dwLowDateTime) /
                      10000ull;
        if (nowFt > ms && nowFt - ms > limitMs) {
            RemoveTree(child);
            ++removed;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    if (removed) logx::Line("storage: gc removed %d stale temp dirs", removed);
}

}  // namespace storage
