#include "protocol.h"

#include <cstring>
#include <functional>

namespace prot {

bool SendAll(SOCKET s, const void* data, size_t n) {
    const char* p = static_cast<const char*>(data);
    size_t left = n;
    while (left > 0) {
        int chunk = (int)(left > 256 * 1024 ? 256 * 1024 : left);
        int sent = ::send(s, p, chunk, 0);
        if (sent > 0) {
            p += sent;
            left -= (size_t)sent;
            continue;
        }
        if (sent == SOCKET_ERROR) {
            int e = WSAGetLastError();
            if (e == WSAEWOULDBLOCK) {
                fd_set wr;
                FD_ZERO(&wr);
                FD_SET(s, &wr);
                timeval tv{0, 100 * 1000};
                select(0, nullptr, &wr, nullptr, &tv);
                continue;
            }
            if (e == WSAENOBUFS || e == WSAEINTR) continue;
            return false;
        }
        return false;
    }
    return true;
}

bool RecvExact(SOCKET s, void* data, size_t n) {
    char* p = static_cast<char*>(data);
    size_t left = n;
    while (left > 0) {
        int got = ::recv(s, p, (int)(left > 1024 * 1024 ? 1024 * 1024 : left), 0);
        if (got > 0) {
            p += got;
            left -= (size_t)got;
            continue;
        }
        if (got == 0) return false;
        int e = WSAGetLastError();
        if (e == WSAEWOULDBLOCK) {
            fd_set rd;
            FD_ZERO(&rd);
            FD_SET(s, &rd);
            timeval tv{0, 100 * 1000};
            select(0, &rd, nullptr, nullptr, &tv);
            continue;
        }
        if (e == WSAEINTR) continue;
        return false;
    }
    return true;
}

std::string Frame(const js::Writer& w) {
    std::string s = w.Str();
    s.push_back('\n');
    return s;
}

bool SendLine(SOCKET s, const js::Writer& w) {
    std::string f = Frame(w);
    return SendAll(s, f.data(), f.size());
}

bool SendJson(SOCKET s, const js::Value& v) {
    // 只用于简单回发：把解析后的对象重新拼装
    js::Writer w;
    std::function<void(const js::Value&)> emit = [&](const js::Value& x) {
        switch (x.type) {
            case js::Value::Null: w.Null(); break;
            case js::Value::Bool: w.B(x.b); break;
            case js::Value::Num: w.D(x.num); break;
            case js::Value::Str: w.S(x.str); break;
            case js::Value::Arr:
                w.Arr();
                for (const auto& e : x.arr) emit(e);
                w.End();
                break;
            case js::Value::Obj:
                w.Obj();
                for (const auto& kv : x.obj) {
                    w.Key(kv.first.c_str());
                    emit(kv.second);
                }
                w.End();
                break;
        }
    };
    emit(v);
    return SendLine(s, w);
}

}  // namespace prot
