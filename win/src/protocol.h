// protocol.h -- NDJSON 行缓冲与 TCP 收发原语
#pragma once

#include "app.h"
#include "json.h"

#include <string>

namespace prot {

class LineReader {
public:
    void Reset() {
        buf_.clear();
        pos_ = 0;
        overflow_ = false;
    }
    void Feed(const char* data, size_t n) {
        if (pos_ > 0 && pos_ >= buf_.size()) {
            buf_.clear();
            pos_ = 0;
        } else if (pos_ > 64 * 1024) {
            buf_.erase(0, pos_);
            pos_ = 0;
        }
        buf_.append(data, n);
    }
    // 取出一行（不含 \r\n）。缓冲区里没有完整行时返回 false。
    bool Next(std::string* line) {
        for (;;) {
            size_t nl = buf_.find('\n', pos_);
            if (nl == std::string::npos) {
                if (buf_.size() - pos_ > proto::kMaxLineBytes) overflow_ = true;
                return false;
            }
            size_t end = nl;
            if (end > pos_ && buf_[end - 1] == '\r') --end;
            line->assign(buf_, pos_, end - pos_);
            pos_ = nl + 1;
            return true;
        }
    }
    bool Overflowed() const { return overflow_; }
    const std::string& Raw() const { return buf_; }

private:
    std::string buf_;
    size_t pos_ = 0;
    bool overflow_ = false;
};

bool SendAll(SOCKET s, const void* data, size_t n);
bool RecvExact(SOCKET s, void* data, size_t n);
std::string Frame(const js::Writer& w);  // 追加 '\n'
bool SendLine(SOCKET s, const js::Writer& w);
bool SendJson(SOCKET s, const js::Value& v);  // 原样回发（解析后重序列化由调用方负责）

}  // namespace prot
