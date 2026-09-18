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
    uint8_t trackingFailureReason=255;
    uint8_t quality=0;
    uint64_t gyroTimestamp=0, visualTimestamp=0;
    std::array<float,3> position{}, linear{}, angular{};
    std::array<float,4> orientation{0,0,0,1};
};
inline uint64_t integer(const uint8_t* p, int bytes) {
    uint64_t value=0;
    for(int i=0;i<bytes;++i) value |= uint64_t(p[i])<<(8*i);
    return value;
}
inline std::optional<Pose> decode(const uint8_t* b, size_t n) {
    if(n<8 || std::memcmp(b,"ASTR",4) || b[5]!=1) return {};
    bool fusion=b[4]==3;
    if((fusion ? n!=112 : (n!=96 || (b[4]!=1 && b[4]!=2))) || integer(b+6,2)!=n) return {};
    if(b[36]<1 || b[36]>4 || b[37]!=1 || b[38]>2 || (b[39]&~3)) return {};
    if(fusion ? (b[93]>4 || integer(b+94,2)) : integer(b+93,3)!=0) return {};
    if(b[4]==1 ? b[92]!=0 : (b[92]>5 && b[92]!=255)) return {};
    Pose p;
    p.sequence=uint32_t(integer(b+8,4)); p.device=uint32_t(integer(b+12,4));
    p.session=integer(b+16,8); p.timestamp=integer(b+24,8); p.revision=uint32_t(integer(b+32,4));
    p.type=b[36]; p.source=b[37]; p.state=b[38]; p.flags=b[39];
    p.trackingFailureReason=b[4]>=2?b[92]:255;
    p.quality=fusion?b[93]:(p.state==2?1:0);
    if(fusion) { p.gyroTimestamp=integer(b+96,8); p.visualTimestamp=integer(b+104,8); }
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
inline const char* trackingFailureName(uint8_t reason) {
    switch(reason) {
    case 0: return "NONE";
    case 1: return "BAD_STATE";
    case 2: return "INSUFFICIENT_LIGHT";
    case 3: return "EXCESSIVE_MOTION";
    case 4: return "INSUFFICIENT_FEATURES";
    case 5: return "CAMERA_UNAVAILABLE";
    default: return "UNKNOWN";
    }
}
inline const char* qualityName(uint8_t quality) {
    switch(quality) {
    case 1: return "FULL_6DOF";
    case 2: return "INERTIAL_ONLY";
    case 3: return "RECOVERING";
    case 4: return "DEGRADED";
    default: return "UNAVAILABLE";
    }
}
}
