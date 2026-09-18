#include "diagnostic_csv.hpp"
#include "stream.hpp"
#include <fstream>
#include <iostream>
#include <sstream>
#include <vector>
#include <stdexcept>
static void check(bool value) { if(!value) throw std::runtime_error("Fusion protocol check failed"); }
static std::vector<uint8_t> fixture(const std::string& path) {
    std::ifstream in(path); check(bool(in)); std::string hex; std::vector<uint8_t> bytes;
    while(in>>hex) bytes.push_back(uint8_t(std::stoul(hex,nullptr,16)));
    return bytes;
}
int main(int argc,char** argv) {
    try {
        check(argc==2); auto p=fixture(std::string(argv[1])+"/golden_fused.hex");
        auto d=fixture(std::string(argv[1])+"/golden_diagnostics.hex");
        auto pose=astraeus::decode(p.data(),p.size()); auto diag=astraeus::decodeDiagnostics(d.data(),d.size());
        check(bool(pose)&&bool(diag)); check(pose->quality==3 && pose->gyroTimestamp==999000000 && pose->visualTimestamp==970000000);
        check(diag->raw.position[0]==2 && diag->world.position[0]==-1 && diag->output.position[0]==1);
        check(diag->values[0]==200 && diag->values[3]==120 && diag->count==1 && diag->discontinuity==1);
        for(size_t n=0;n<d.size();++n) check(!astraeus::decodeDiagnostics(d.data(),n));
        for(size_t n=0;n<p.size();++n) check(!astraeus::decode(p.data(),n));
        auto bad=d; bad[349]=9; check(!astraeus::decodeDiagnostics(bad.data(),bad.size()));
        bad=d; bad[99]=0; check(!astraeus::decodeDiagnostics(bad.data(),bad.size()));
        bad=d; bad[223]=0x7f; bad[222]=0xc0; check(!astraeus::decodeDiagnostics(bad.data(),bad.size()));
        auto badPose=p; badPose[93]=5; check(!astraeus::decode(badPose.data(),badPose.size()));
        astraeus::Stream stream;
        check(!stream.ingestDiagnostics(*diag,"phone",1)); check(stream.ingest(*pose,"phone",1));
        check(stream.ingestDiagnostics(*diag,"phone",1.01)); check(!stream.ingestDiagnostics(*diag,"phone",1.02));
        check(stream.received==1 && stream.accepted==1 && stream.missing==0);
        std::ostringstream csv; astraeus::diagnosticHeader(csv); astraeus::diagnosticRow(csv,*diag,1.0);
        check(csv.str().find("RECOVERING")!=std::string::npos && csv.str().find("residual_position")!=std::string::npos);
        std::cout<<"v3 public/diagnostic fixtures, lengths, validity, stream ordering and CSV passed\n";
        return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
