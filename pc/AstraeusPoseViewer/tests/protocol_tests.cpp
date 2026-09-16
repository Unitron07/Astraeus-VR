#include "stream.hpp"
#include <fstream>
#include <iostream>
#include <vector>
#include <stdexcept>

static int checks=0;
static void check(bool condition) { ++checks; if(!condition) throw std::runtime_error("Failed check "+std::to_string(checks)); }
int main(int argc,char** argv) {
    try {
        check(argc==2); std::ifstream input(argv[1]); check(bool(input));
        std::vector<uint8_t> b; std::string hex;
        while(input>>hex) { for(size_t i=0;i<hex.size();i+=2) b.push_back(uint8_t(std::stoul(hex.substr(i,2),nullptr,16))); }
        auto p=astraeus::decode(b.data(),b.size()); check(bool(p));
        check(p->sequence==42 && p->session==7 && p->timestamp==1000000000);
        check(p->position[0]==1 && p->position[1]==2 && p->position[2]==-3 && p->orientation[3]==1);
        for(size_t n=0;n<96;++n) check(!astraeus::decode(b.data(),n));
        auto bad=b; bad[4]=2; check(!astraeus::decode(bad.data(),bad.size()));
        bad=b; bad[55]=0x7f; bad[54]=0xc0; check(!astraeus::decode(bad.data(),bad.size()));
        bad=b; bad[92]=1; check(!astraeus::decode(bad.data(),bad.size()));
        bad=b; bad[67]=0; check(!astraeus::decode(bad.data(),bad.size()));
        astraeus::Stream s; check(s.ingest(*p,"phone",1)); check(!s.ingest(*p,"phone",1.01));
        p->sequence+=3; p->timestamp+=10000000; check(s.ingest(*p,"phone",1.02)); check(s.missing==2 && s.outOfOrder==1);
        auto other=*p; other.session++; check(!s.ingest(other,"phone",1.03)); check(s.foreign==1);
        p->sequence++; check(!s.ingest(*p,"phone",1.04)); check(s.invalid==1);
        s={}; p->sequence=0xffffffff; check(s.ingest(*p,"phone",2)); p->sequence=0; p->timestamp++; check(s.ingest(*p,"phone",2.1)); check(s.missing==0);
        std::cout<<checks<<" checks passed\n"; return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
