#include "app.h"

#include <algorithm>
#include <mutex>

namespace jobs {

namespace {
std::vector<std::shared_ptr<Job>> g_jobs;
CRITICAL_SECTION g_cs;
std::atomic<uint32_t> g_next{1};
bool g_inited = false;
}  // namespace

void Init() {
    if (g_inited) return;
    InitializeCriticalSectionAndSpinCount(&g_cs, 4000);
    g_inited = true;
}

void Shutdown() {
    if (!g_inited) return;
    {
        EnterCriticalSection(&g_cs);
        for (auto& j : g_jobs) j->cancel = true;
        LeaveCriticalSection(&g_cs);
    }
    // 等 worker 退出（client/srv 内部计数器）
    for (int i = 0; i < 300 && g_app.activeWorkers.load() > 0; ++i) Sleep(20);
    EnterCriticalSection(&g_cs);
    g_jobs.clear();
    LeaveCriticalSection(&g_cs);
    DeleteCriticalSection(&g_cs);
    g_inited = false;
}

std::shared_ptr<Job> Create(JobDir dir) {
    auto j = std::make_shared<Job>();
    j->id = g_next.fetch_add(1);
    if (j->id == 0) j->id = g_next.fetch_add(1);
    j->dir = dir;
    j->state = JobState::Queued;
    j->startTick = util::NowMs();
    return j;
}

void Add(const std::shared_ptr<Job>& j) {
    if (!j) return;
    EnterCriticalSection(&g_cs);
    if (g_jobs.size() > 200) {
        // 只淘汰已结束的最旧记录
        for (auto it = g_jobs.begin(); it != g_jobs.end(); ++it) {
            if (!StateIsActive((*it)->state.load())) { g_jobs.erase(it); break; }
        }
    }
    g_jobs.push_back(j);
    LeaveCriticalSection(&g_cs);
    if (g_app.mainWnd) PostMessageW(g_app.mainWnd, WM_APP_WAKE, 0, 0);
}

void Snapshot(std::vector<JobView>* out) {
    if (!out) return;
    out->clear();
    EnterCriticalSection(&g_cs);
    out->reserve(g_jobs.size());
    for (const auto& j : g_jobs) {
        JobView v;
        v.id = j->id;
        v.dir = j->dir;
        v.state = j->state.load();
        v.title = j->title;
        v.deviceName = j->dev.name;
        v.savePath = j->savePath;
        v.errMsg = j->errMsg;
        v.totalBytes = j->totalBytes;
        v.doneBytes = j->doneBytes.load();
        v.totalFiles = j->totalFiles;
        v.doneFiles = j->doneFiles.load();
        v.bps = j->bps.load();
        v.errCode = j->errCode.load();
        v.startTick = j->startTick;
        v.endTick = j->endTick;
        out->push_back(std::move(v));
    }
    LeaveCriticalSection(&g_cs);
    // 进行中的在前，其余按开始时间倒序
    std::stable_sort(out->begin(), out->end(), [](const JobView& a, const JobView& b) {
        const bool aa = StateIsActive(a.state), ba = StateIsActive(b.state);
        if (aa != ba) return aa;
        return a.startTick > b.startTick;
    });
}

std::shared_ptr<Job> Find(uint32_t id) {
    EnterCriticalSection(&g_cs);
    std::shared_ptr<Job> r;
    for (auto& j : g_jobs) {
        if (j->id == id) { r = j; break; }
    }
    LeaveCriticalSection(&g_cs);
    return r;
}

void Cancel(uint32_t id) {
    auto j = Find(id);
    if (!j) return;
    if (StateIsActive(j->state.load())) {
        j->cancel = true;
        logx::Line("job %u: cancel requested", id);
    }
}

void Remove(uint32_t id) {
    EnterCriticalSection(&g_cs);
    for (auto it = g_jobs.begin(); it != g_jobs.end(); ++it) {
        if ((*it)->id == id) {
            if (!StateIsActive((*it)->state.load())) g_jobs.erase(it);
            break;
        }
    }
    LeaveCriticalSection(&g_cs);
}

void RemoveFinished() {
    EnterCriticalSection(&g_cs);
    g_jobs.erase(std::remove_if(g_jobs.begin(), g_jobs.end(),
                                [](const std::shared_ptr<Job>& j) {
                                    return !StateIsActive(j->state.load());
                                }),
                 g_jobs.end());
    LeaveCriticalSection(&g_cs);
}

void NoteProgressTick(const std::shared_ptr<Job>& j) {
    if (!j) return;
    const uint64_t now = util::NowMs();
    if (j->prevTick == 0) {
        j->prevTick = now;
        j->prevBytes = j->doneBytes.load();
        return;
    }
    const uint64_t dt = now - j->prevTick;
    if (dt < 400) return;
    const uint64_t cur = j->doneBytes.load();
    const double inst = (double)(cur - j->prevBytes) * 1000.0 / (double)dt;
    j->prevBytes = cur;
    j->prevTick = now;
    const double old = j->bps.load();
    j->bps.store(old <= 0 ? inst : old * 0.7 + inst * 0.3);
}

bool AnyActive() {
    bool r = false;
    EnterCriticalSection(&g_cs);
    for (auto& j : g_jobs) {
        if (StateIsActive(j->state.load())) { r = true; break; }
    }
    LeaveCriticalSection(&g_cs);
    return r;
}

}  // namespace jobs
