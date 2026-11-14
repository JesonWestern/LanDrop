// json.h -- 极简 JSON 读写（零依赖，够用于本协议的扁平行报文）
#pragma once

#include <string>
#include <utility>
#include <vector>

namespace js {

std::string Escape(const std::string& s);
void AppendUtf8(std::string* out, unsigned int cp);

class Writer {
public:
    Writer& Obj();
    Writer& Arr();
    Writer& End();                 // 关闭当前容器
    Writer& Key(const char* k);
    Writer& S(const std::string& v);
    Writer& WS(const std::wstring& v);
    Writer& I(long long v);
    Writer& U(unsigned long long v);
    Writer& D(double v);
    Writer& B(bool v);
    Writer& Null();
    Writer& Raw(const std::string& raw);

    const std::string& Str() const { return buf_; }
    std::string Take() { return std::move(buf_); }

private:
    void Pre();
    std::string buf_;
    std::vector<bool> first_;
    std::vector<char> open_;
    bool pendingKey_ = false;
};

struct Value {
    enum Type { Null, Bool, Num, Str, Arr, Obj } type = Null;
    bool b = false;
    double num = 0;
    std::string str;
    std::vector<Value> arr;
    std::vector<std::pair<std::string, Value>> obj;

    const Value* Get(const char* k) const;
    std::string GetStr(const char* k, const char* def = "") const;
    std::wstring GetWStr(const char* k, const wchar_t* def = L"") const;
    long long GetInt(const char* k, long long def = 0) const;
    bool GetBool(const char* k, bool def = false) const;
    const Value* GetArr(const char* k) const;
};

bool Parse(const std::string& text, Value* out);

}  // namespace js
