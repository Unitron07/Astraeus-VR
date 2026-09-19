#pragma once
#include "pose.hpp"
#include "diagnostics.hpp"
#include <string>

namespace astraeus {
struct Stream {
    bool locked=false;
    Pose latest;
    std::string endpoint;
    uint64_t received=0, accepted=0, missing=0, outOfOrder=0, foreign=0, invalid=0;
    double lastReceive=0, jitterMs=0, rate=0, rateStart=0;
    uint64_t rateCount=0;
    Diagnostics diagnostics;
    bool hasDiagnostics=false;
    double diagnosticsReceive=0;
    uint64_t diagnosticsReceived=0;
    uint64_t diagnosticsMissing=0;
    bool ingestDiagnostics(const Diagnostics& d,const std::string& from,double now) {
        if(!locked || from!=endpoint || d.session!=latest.session || d.device!=latest.device) return false;
        if(hasDiagnostics) {
            uint32_t delta=d.sequence-diagnostics.sequence;
            if(delta==0 || delta>=0x80000000u || d.timestamp<=diagnostics.timestamp) return false;
            if(d.version==4 && diagnostics.version==4) diagnosticsMissing+=delta-1;
        }
        diagnostics=d; diagnosticsReceive=now; hasDiagnostics=true; ++diagnosticsReceived; return true;
    }
    bool ingest(const Pose& p,const std::string& from,double now) {
        ++received;
        if(locked && (from!=endpoint || p.session!=latest.session || p.device!=latest.device)) { ++foreign; return false; }
        if(locked) {
            uint32_t distance=p.sequence-latest.sequence;
            if(distance==0 || distance>=0x80000000u) { ++outOfOrder; return false; }
            if(p.timestamp<=latest.timestamp) { ++invalid; return false; }
            missing+=distance-1;
            double delta=(now-lastReceive)-double(p.timestamp-latest.timestamp)/1e9;
            jitterMs+=(std::abs(delta)*1000-jitterMs)/16;
        } else { endpoint=from; rateStart=now; }
        latest=p; locked=true; lastReceive=now; ++accepted; ++rateCount;
        if(now-rateStart>=1) { rate=double(rateCount)/(now-rateStart); rateStart=now; rateCount=0; }
        return true;
    }
};
}
