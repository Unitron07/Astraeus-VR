#include "receiver.hpp"
#include <chrono>
#include <filesystem>
#include <iostream>
#include <vector>
#include <stdexcept>

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
        check(!receiver.toggleLogging(),"disable CSV");
        auto diagnostic=receiver.diagnostic(); auto end=diagnostic.find(" | CSV");
        check(diagnostic.rfind("CSV: ",0)==0,"CSV filename diagnostic");
        auto path=diagnostic.substr(5,end-5); std::ifstream log(path);
        std::string line; int lines=0; while(std::getline(log,line)) ++lines;
        check(lines==5,"CSV header plus four selected-stream packets"); log.close();
        std::filesystem::remove(path); // Only the test-owned file returned by this receiver.
        closesocket(sender);
        std::cout<<"UDP loopback, duplicates, gaps, malformed input, session lock/reset, recenter revision and CSV passed\n";
        return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
