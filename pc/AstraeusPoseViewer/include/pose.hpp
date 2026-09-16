#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <optional>

namespace astraeus {
struct Pose {
    uint32_t sequence{}, device{}, revision{};
    uint64_t session{}, timestamp{};
    uint8_t type{}, source{}, state{}, flags{};
    std::array<float,3> position{}, linear{}, angular{};
    std::array<float,4> orientation{0,0,0,1};
};
inline uint64_t integer(const uint8_t* p, int bytes) {
    uint64_t value=0;
    for(int i=0;i<bytes;++i) value |= uint64_t(p[i])<<(8*i);
    return value;
}
inline std::optional<Pose> decode(const uint8_t* b, size_t n) {
    if(n!=96 || std::memcmp(b,"ASTR",4) || b[4]!=1 || b[5]!=1 || integer(b+6,2)!=96) return {};
    if(b[36]<1 || b[36]>4 || b[37]!=1 || b[38]>2 || (b[39]&~3) || integer(b+92,4)) return {};
    Pose p;
    p.sequence=uint32_t(integer(b+8,4)); p.device=uint32_t(integer(b+12,4));
    p.session=integer(b+16,8); p.timestamp=integer(b+24,8); p.revision=uint32_t(integer(b+32,4));
    p.type=b[36]; p.source=b[37]; p.state=b[38]; p.flags=b[39];
    size_t offset=40;
    auto floats=[&](auto& fields) {
        for(auto& f:fields) {
            uint32_t bits=uint32_t(integer(b+offset,4)); offset+=4;
            std::memcpy(&f,&bits,4);
            if(!std::isfinite(f)) return false;
        }
        return true;
    };
    if(!floats(p.position)||!floats(p.orientation)||!floats(p.linear)||!floats(p.angular)) return {};
    float norm=0; for(float x:p.orientation) norm+=x*x;
    if(std::abs(std::sqrt(norm)-1.f)>0.01f) return {};
    return p;
}
inline const char* trackingName(uint8_t state) {
    return state==2?"TRACKING":state==1?"PAUSED":"STOPPED";
}
}
