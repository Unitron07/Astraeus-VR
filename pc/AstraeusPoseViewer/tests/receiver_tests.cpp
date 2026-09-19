#include "receiver.hpp"
#include <chrono>
#include <filesystem>
#include <iostream>
#include <vector>
#include <stdexcept>
#include <sstream>

using namespace astraeus;
static void check(bool value,const char* message) { if(!value) throw std::runtime_error(message); }
int main(int argc,char** argv) {
    try {
        check(argc==2,"fixture argument missing");
        std::ifstream fixture(argv[1]); check(bool(fixture),"fixture missing");
        std::vector<uint8_t> bytes; std::string h;
        while(fixture>>h) for(size_t i=0;i<h.size();i+=2) bytes.push_back(uint8_t(std::stoul(h.substr(i,2),nullptr,16)));
        Receiver receiver; receiver.start(0);
        SOCKET sender=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
        check(sender!=INVALID_SOCKET,"sender socket");
        sockaddr_in destination{}; destination.sin_family=AF_INET;
        destination.sin_addr.s_addr=htonl(INADDR_LOOPBACK); destination.sin_port=htons(receiver.boundPort());
        check(receiver.toggleLogging(),"enable CSV");
        auto send=[&]() {
            check(sendto(sender,reinterpret_cast<const char*>(bytes.data()),int(bytes.size()),0,
                reinterpret_cast<sockaddr*>(&destination),sizeof(destination))==int(bytes.size()),"UDP send");
        };
        auto waitFor=[&](auto predicate) {
            auto deadline=monotonicSeconds()+2;
            while(!predicate(receiver.snapshot()) && monotonicSeconds()<deadline) std::this_thread::sleep_for(std::chrono::milliseconds(5));
            check(predicate(receiver.snapshot()),"receiver wait timeout");
        };
        send(); waitFor([](const Stream& s){return s.accepted==1;});
        send(); waitFor([](const Stream& s){return s.outOfOrder==1;});
        bytes[8]=45;
        uint64_t timestamp=1010000000; for(int i=0;i<8;++i) bytes[24+i]=uint8_t(timestamp>>(8*i));
        bytes[32]=1;
        send(); waitFor([](const Stream& s){return s.accepted==2;});
        check(receiver.snapshot().missing==2,"gap count"); check(receiver.snapshot().latest.revision==1,"origin revision");
        bytes[4]=9; send(); waitFor([](const Stream& s){return s.invalid==1;}); bytes[4]=1;
        bytes[16]=8; send(); waitFor([](const Stream& s){return s.foreign==1;});
        receiver.reset(); send(); waitFor([](const Stream& s){return s.accepted==1;});
        check(receiver.snapshot().latest.session==8,"reset stream selection");
        const char* reasons[]={"NONE","BAD_STATE","INSUFFICIENT_LIGHT","EXCESSIVE_MOTION","INSUFFICIENT_FEATURES","CAMERA_UNAVAILABLE"};
        bytes[4]=2; bytes[38]=1;
        for(uint8_t reason=0;reason<6;++reason) {
            ++bytes[8]; timestamp+=10000000;
            for(int i=0;i<8;++i) bytes[24+i]=uint8_t(timestamp>>(8*i));
            bytes[92]=reason; send();
            waitFor([&](const Stream& s){return s.accepted==uint64_t(reason)+2;});
            check(receiver.snapshot().latest.trackingFailureReason==reason,"failure reason delivered");
        }
        check(!receiver.toggleLogging(),"disable CSV");
        auto diagnostic=receiver.diagnostic(); auto end=diagnostic.find(" | CSV");
        check(diagnostic.rfind("CSV: ",0)==0,"CSV filename diagnostic");
        auto path=diagnostic.substr(5,end-5); std::ifstream log(path);
        std::string line; check(bool(std::getline(log,line)),"CSV header");
        check(line.find(",tracking_failure_reason,tracking_quality,")!=std::string::npos,"CSV reason/quality columns");
        int rows=0;
        while(std::getline(log,line)) {
            check(rows<10,"unexpected CSV row");
            std::istringstream row(line); std::string reason;
            for(int column=0;column<=22;++column) std::getline(row,reason,',');
            check(reason==(rows<4?"UNKNOWN":reasons[rows-4]),"CSV reason value");
            ++rows;
        }
        check(rows==10,"CSV legacy samples plus all six failure reasons"); log.close();
        std::filesystem::remove(path); // Only the test-owned file returned by this receiver.
        std::filesystem::remove(path.substr(0,path.size()-4)+"-diagnostics.csv");
        auto load=[&](const char* name) {
            std::ifstream in(std::filesystem::path(argv[1]).parent_path()/name);
            check(bool(in),"fusion fixture missing"); bytes.clear();
            while(in>>h) bytes.push_back(uint8_t(std::stoul(h,nullptr,16)));
        };
        receiver.reset(); check(receiver.toggleLogging(),"enable fusion CSV");
        load("golden_fused.hex"); send(); waitFor([](const Stream& s){return s.accepted==1;});
        load("golden_diagnostics.hex"); send(); waitFor([](const Stream& s){return s.diagnosticsReceived==1;});
        check(receiver.snapshot().latest.quality==3,"public recovering quality");
        check(receiver.snapshot().diagnostics.world.position[0]==-1,"world compensation diagnostic");
        load("golden_anchor_diagnostics.hex"); bytes[8]=43;
        timestamp=1033000000; for(int i=0;i<8;++i) bytes[24+i]=uint8_t(timestamp>>(8*i));
        send(); waitFor([](const Stream& s){return s.diagnosticsReceived==2;});
        check(receiver.snapshot().diagnostics.anchorId==1 && receiver.snapshot().diagnostics.event==7,"anchor diagnostic delivered");
        check(!receiver.toggleLogging(),"close fusion CSV");
        diagnostic=receiver.diagnostic(); end=diagnostic.find(" | CSV"); path=diagnostic.substr(5,end-5);
        auto diagnosticPath=path.substr(0,path.size()-4)+"-diagnostics.csv";
        std::ifstream fusionLog(diagnosticPath); std::string header, data;
        check(bool(std::getline(fusionLog,header)) && bool(std::getline(fusionLog,data)),"fusion diagnostic CSV rows");
        check(header.find("raw_px")!=std::string::npos && header.find("fused_qw")!=std::string::npos,"raw/fused columns");
        check(data.find("RECOVERING")!=std::string::npos,"logged quality");
        check(bool(std::getline(fusionLog,data)) && data.find("ANCHOR_CREATED")!=std::string::npos,"logged anchor event");
        fusionLog.close(); std::filesystem::remove(path); std::filesystem::remove(diagnosticPath);
        closesocket(sender);
        std::cout<<"UDP loopback, duplicates, gaps, malformed input, session lock/reset, recenter revision and CSV passed\n";
        return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
