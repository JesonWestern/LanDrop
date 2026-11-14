#include "json.h"

#include "app.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace js {

void AppendUtf8(std::string* out, unsigned int cp) {
    if (cp < 0x80) {
        out->push_back((char)cp);
    } else if (cp < 0x800) {
        out->push_back((char)(0xC0 | (cp >> 6)));
        out->push_back((char)(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out->push_back((char)(0xE0 | (cp >> 12)));
        out->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back((char)(0x80 | (cp & 0x3F)));
    } else {
        out->push_back((char)(0xF0 | (cp >> 18)));
        out->push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
        out->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out->push_back((char)(0x80 | (cp & 0x3F)));
    }
}

std::string Escape(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\b': o += "\\b"; break;
            case '\f': o += "\\f"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    o += buf;
                } else {
                    o.push_back((char)c);
                }
        }
    }
    return o;
}

// ---- Writer ---------------------------------------------------------------
void Writer::Pre() {
    if (pendingKey_) { pendingKey_ = false; return; }
    if (!first_.empty()) {
        if (!first_.back()) buf_.push_back(',');
        first_.back() = false;
    }
}

Writer& Writer::Obj() {
    Pre();
    buf_.push_back('{');
    open_.push_back('{');
    first_.push_back(true);
    return *this;
}
Writer& Writer::Arr() {
    Pre();
    buf_.push_back('[');
    open_.push_back('[');
    first_.push_back(true);
    return *this;
}
Writer& Writer::End() {
    const char c = open_.empty() ? '}' : (open_.back() == '[' ? ']' : '}');
    buf_.push_back(c);
    if (!open_.empty()) open_.pop_back();
    if (!first_.empty()) first_.pop_back();
    return *this;
}

Writer& Writer::Key(const char* k) {
    Pre();
    buf_.push_back('"');
    buf_ += Escape(k);
    buf_ += "\":";
    pendingKey_ = true;
    return *this;
}

Writer& Writer::S(const std::string& v) {
    Pre();
    buf_.push_back('"');
    buf_ += Escape(v);
    buf_.push_back('"');
    return *this;
}

Writer& Writer::WS(const std::wstring& v) { return S(util::Narrow(v)); }

Writer& Writer::I(long long v) {
    Pre();
    char buf[32];
    snprintf(buf, sizeof(buf), "%lld", v);
    buf_ += buf;
    return *this;
}

Writer& Writer::U(unsigned long long v) {
    Pre();
    char buf[32];
    snprintf(buf, sizeof(buf), "%llu", v);
    buf_ += buf;
    return *this;
}

Writer& Writer::D(double v) {
    Pre();
    char buf[64];
    if (!std::isfinite(v)) v = 0;
    snprintf(buf, sizeof(buf), "%.3f", v);
    buf_ += buf;
    return *this;
}

Writer& Writer::B(bool v) { Pre(); buf_ += v ? "true" : "false"; return *this; }
Writer& Writer::Null() { Pre(); buf_ += "null"; return *this; }
Writer& Writer::Raw(const std::string& raw) { Pre(); buf_ += raw; return *this; }

// ---- Parser ---------------------------------------------------------------
namespace {
struct Parser {
    const char* p;
    const char* end;
    int depth = 0;
    bool fail = false;

    void SkipWs() {
        while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) ++p;
    }
    bool Lit(const char* s) {
        size_t n = strlen(s);
        if ((size_t)(end - p) < n || memcmp(p, s, n) != 0) return false;
        p += n;
        return true;
    }
    bool ParseValue(Value* v) {
        if (fail || ++depth > 64) { fail = true; return false; }
        SkipWs();
        if (p >= end) { fail = true; return false; }
        char c = *p;
        if (c == '{') {
            v->type = Value::Obj;
            ++p;
            SkipWs();
            if (p < end && *p == '}') { ++p; --depth; return true; }
            for (;;) {
                SkipWs();
                if (p >= end || *p != '"') { fail = true; return false; }
                std::string key;
                if (!ParseString(&key)) return false;
                SkipWs();
                if (p >= end || *p != ':') { fail = true; return false; }
                ++p;
                Value child;
                if (!ParseValue(&child)) return false;
                v->obj.emplace_back(std::move(key), std::move(child));
                SkipWs();
                if (p < end && *p == ',') { ++p; continue; }
                if (p < end && *p == '}') { ++p; break; }
                fail = true; return false;
            }
            --depth;
            return true;
        }
        if (c == '[') {
            v->type = Value::Arr;
            ++p;
            SkipWs();
            if (p < end && *p == ']') { ++p; --depth; return true; }
            for (;;) {
                Value child;
                if (!ParseValue(&child)) return false;
                v->arr.emplace_back(std::move(child));
                SkipWs();
                if (p < end && *p == ',') { ++p; continue; }
                if (p < end && *p == ']') { ++p; break; }
                fail = true; return false;
            }
            --depth;
            return true;
        }
        if (c == '"') {
            v->type = Value::Str;
            std::string s;
            if (!ParseString(&s)) return false;
            v->str = std::move(s);
            --depth;
            return true;
        }
        if (Lit("true")) { v->type = Value::Bool; v->b = true; --depth; return true; }
        if (Lit("false")) { v->type = Value::Bool; v->b = false; --depth; return true; }
        if (Lit("null")) { v->type = Value::Null; --depth; return true; }
        {
            const char* start = p;
            if (p < end && (*p == '-' || *p == '+')) ++p;
            while (p < end && ((*p >= '0' && *p <= '9') || *p == '.' || *p == 'e' || *p == 'E' ||
                               *p == '-' || *p == '+')) ++p;
            if (p == start) { fail = true; return false; }
            std::string num(start, (size_t)(p - start));
            v->type = Value::Num;
            v->num = atof(num.c_str());
            --depth;
            return true;
        }
    }
    bool ParseString(std::string* out) {
        if (p >= end || *p != '"') { fail = true; return false; }
        ++p;
        while (p < end) {
            unsigned char c = (unsigned char)*p;
            if (c == '"') { ++p; return true; }
            if (c == '\\') {
                ++p;
                if (p >= end) break;
                char e = *p++;
                switch (e) {
                    case '"': out->push_back('"'); break;
                    case '\\': out->push_back('\\'); break;
                    case '/': out->push_back('/'); break;
                    case 'b': out->push_back('\b'); break;
                    case 'f': out->push_back('\f'); break;
                    case 'n': out->push_back('\n'); break;
                    case 'r': out->push_back('\r'); break;
                    case 't': out->push_back('\t'); break;
                    case 'u': {
                        if (end - p < 4) { fail = true; return false; }
                        auto hex4 = [&](const char* q) -> unsigned {
                            unsigned v = 0;
                            for (int i = 0; i < 4; ++i) {
                                char h = q[i];
                                v <<= 4;
                                if (h >= '0' && h <= '9') v |= (unsigned)(h - '0');
                                else if (h >= 'a' && h <= 'f') v |= (unsigned)(h - 'a' + 10);
                                else if (h >= 'A' && h <= 'F') v |= (unsigned)(h - 'A' + 10);
                                else return 0xFFFFFFFFu;
                            }
                            return v;
                        };
                        unsigned cp = hex4(p);
                        if (cp == 0xFFFFFFFFu) { fail = true; return false; }
                        p += 4;
                        if (cp >= 0xD800 && cp <= 0xDBFF && end - p >= 6 && p[0] == '\\' && p[1] == 'u') {
                            unsigned lo = hex4(p + 2);
                            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                p += 6;
                            }
                        }
                        AppendUtf8(out, cp);
                        break;
                    }
                    default: fail = true; return false;
                }
                continue;
            }
            out->push_back((char)c);
            ++p;
        }
        fail = true;
        return false;
    }
};
}  // namespace

bool Parse(const std::string& text, Value* out) {
    if (!out) return false;
    *out = Value();
    Parser ps{text.c_str(), text.c_str() + text.size()};
    if (!ps.ParseValue(out)) return false;
    ps.SkipWs();
    return !ps.fail;
}

const Value* Value::Get(const char* k) const {
    if (type != Obj) return nullptr;
    for (const auto& kv : obj) {
        if (kv.first == k) return &kv.second;
    }
    return nullptr;
}

std::string Value::GetStr(const char* k, const char* def) const {
    const Value* v = Get(k);
    if (!v) return def;
    if (v->type == Str) return v->str;
    return def;
}

std::wstring Value::GetWStr(const char* k, const wchar_t* def) const {
    const Value* v = Get(k);
    if (!v || v->type != Str) return def;
    return util::Widen(v->str);
}

long long Value::GetInt(const char* k, long long def) const {
    const Value* v = Get(k);
    if (!v) return def;
    if (v->type == Num) return (long long)v->num;
    if (v->type == Str) return atoll(v->str.c_str());
    return def;
}

bool Value::GetBool(const char* k, bool def) const {
    const Value* v = Get(k);
    if (!v) return def;
    if (v->type == Bool) return v->b;
    if (v->type == Num) return v->num != 0;
    return def;
}

const Value* Value::GetArr(const char* k) const {
    const Value* v = Get(k);
    return (v && v->type == Arr) ? v : nullptr;
}

}  // namespace js
