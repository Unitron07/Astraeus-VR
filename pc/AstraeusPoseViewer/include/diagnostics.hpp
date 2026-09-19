#pragma once
#include "pose.hpp"

namespace astraeus {
inline const char* eventName(uint8_t value) {
    static const char* names[]={"NONE","RAW_WORLD_UPDATE_COMPENSATED","ANCHOR_RELATIVE_DISCONTINUITY",
        "TRACKING_REACQUISITION","ANCHOR_LOST","CLOCK_ANOMALY","SENSOR_ANOMALY","ANCHOR_CREATED","ANCHOR_REPLACED"};
    return value<9?names[value]:"UNKNOWN";
}
struct Diagnostics {
    uint8_t version=3,anchorState=0,event=0,stepFlags=0;
    uint32_t anchorId=0,rawWorldUpdates=0,relativeDiscontinuities=0,reacquisitions=0,anchorLosses=0,eventMask=0;
    Pose anchorWorld,cameraAnchor,alignment;
    std::array<float,6> steps{};
    uint32_t sequence{},device{},revision{},count{},gyroAnomalies{},anomalies{},clockAnomalies{},logDrops{};
    uint64_t session{},timestamp{},frameTimestamp{},cameraTimestamp{},gyroTimestamp{},accelTimestamp{};
    int32_t gyroAccuracy{},accelAccuracy{};
    int64_t clockOffset{},cameraAge{};
    uint8_t state{},reason{},quality{},clockValid{},uncalibrated{},discontinuity{};
    Pose raw,rawUser,world,user,output;
    std::array<float,3> gyro{},bias{},accel{};
    // gyro/accel/AR/output Hz, innovations (m/rad), speed, residuals, last jump (m/rad).
    std::array<float,11> values{};
    float heapMb{},cpu{};
};
inline std::optional<Diagnostics> decodeDiagnostics(const uint8_t* b,size_t n) {
    if(n<8 || std::memcmp(b,"ASTR",4) || b[5]!=2 || integer(b+6,2)!=n ||
        !((b[4]==3 && n==352) || (b[4]==4 && n==488))) return {};
    if(b[36]!=1 || b[37]!=1 || b[38]>2 || (b[39]&~3) || b[276]>1 || b[277]>1 || b[278]>1 || b[279]>4 ||
        (b[348]>5 && b[348]!=255) || b[349]!=b[279] || integer(b+350,2)) return {};
    Diagnostics d;
    d.sequence=uint32_t(integer(b+8,4)); d.device=uint32_t(integer(b+12,4)); d.session=integer(b+16,8);
    d.timestamp=integer(b+24,8); d.revision=uint32_t(integer(b+32,4)); d.state=b[38];
    d.frameTimestamp=integer(b+40,8); d.cameraTimestamp=integer(b+48,8);
    d.gyroTimestamp=integer(b+56,8); d.accelTimestamp=integer(b+64,8);
    size_t offset=72;
    auto fields=[&](auto& array) {
        for(auto& f:array) { uint32_t bits=uint32_t(integer(b+offset,4)); offset+=4; std::memcpy(&f,&bits,4); if(!std::isfinite(f)) return false; }
        return true;
    };
    auto pose=[&](Pose& p) {
        if(!fields(p.position) || !fields(p.orientation)) return false;
        float norm=0; for(float v:p.orientation) norm+=v*v;
        return std::abs(std::sqrt(norm)-1.f)<=0.01f;
    };
    if(!pose(d.raw)||!pose(d.rawUser)||!pose(d.world)||!pose(d.user)||!fields(d.gyro)||!fields(d.bias)||!fields(d.accel)||!fields(d.values)) return {};
    d.count=uint32_t(integer(b+264,4));
    uint32_t ga=uint32_t(integer(b+268,4)),aa=uint32_t(integer(b+272,4));
    std::memcpy(&d.gyroAccuracy,&ga,4); std::memcpy(&d.accelAccuracy,&aa,4);
    d.clockValid=b[276]; d.uncalibrated=b[277]; d.discontinuity=b[278]; d.quality=b[279]; d.reason=b[348];
    uint64_t co=integer(b+280,8),ca=integer(b+288,8);
    std::memcpy(&d.clockOffset,&co,8); std::memcpy(&d.cameraAge,&ca,8);
    d.gyroAnomalies=uint32_t(integer(b+296,4)); d.anomalies=uint32_t(integer(b+300,4));
    d.clockAnomalies=uint32_t(integer(b+304,4)); d.logDrops=uint32_t(integer(b+308,4));
    offset=312; std::array<float,2> perf{}; if(!fields(perf)) return {};
    d.heapMb=perf[0]; d.cpu=perf[1]; if(!pose(d.output)) return {};
    d.version=b[4];
    if(d.version==4) {
        offset=352;
        if(!pose(d.anchorWorld)||!pose(d.cameraAnchor)||!pose(d.alignment)||!fields(d.steps)) return {};
        d.anchorId=uint32_t(integer(b+460,4)); d.anchorState=b[464]; d.event=b[465]; d.stepFlags=b[466];
        d.rawWorldUpdates=uint32_t(integer(b+468,4)); d.relativeDiscontinuities=uint32_t(integer(b+472,4));
        d.reacquisitions=uint32_t(integer(b+476,4)); d.anchorLosses=uint32_t(integer(b+480,4));
        d.eventMask=uint32_t(integer(b+484,4));
        if(d.anchorState>2 || d.event>8 || d.stepFlags>7 || b[467] || (d.eventMask&~0x1feu)) return {};
        for(float v:d.steps) if(v<0) return {};
    }
    return d;
}
}
