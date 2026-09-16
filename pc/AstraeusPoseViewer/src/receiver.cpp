#include "receiver.hpp"
#include <chrono>
#include <iomanip>
#include <stdexcept>

namespace astraeus {
double monotonicSeconds() {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
void Receiver::start(uint16_t port) {
    WSADATA data{};
    if(WSAStartup(MAKEWORD(2,2),&data)) throw std::runtime_error("WSAStartup failed");
    socket_=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);
    if(socket_==INVALID_SOCKET) { WSACleanup(); throw std::runtime_error("UDP socket failed"); }
    sockaddr_in address{}; address.sin_family=AF_INET; address.sin_port=htons(port); address.sin_addr.s_addr=INADDR_ANY;
    if(bind(socket_,reinterpret_cast<sockaddr*>(&address),sizeof(address))==SOCKET_ERROR) {
        auto code=WSAGetLastError(); closesocket(socket_); socket_=INVALID_SOCKET; WSACleanup();
        throw std::runtime_error("Bind failed, Winsock error "+std::to_string(code));
    }
    DWORD timeout=100;
    setsockopt(socket_,SOL_SOCKET,SO_RCVTIMEO,reinterpret_cast<const char*>(&timeout),sizeof(timeout));
    running_=true; worker_=std::thread(&Receiver::run,this);
}
Receiver::~Receiver() {
    running_=false;
    if(worker_.joinable()) worker_.join();
    if(socket_!=INVALID_SOCKET) { closesocket(socket_); WSACleanup(); }
}
uint16_t Receiver::boundPort() const {
    sockaddr_in address{}; int length=sizeof(address);
    if(getsockname(socket_,reinterpret_cast<sockaddr*>(&address),&length)) throw std::runtime_error("getsockname failed");
    return ntohs(address.sin_port);
}
Stream Receiver::snapshot() { std::lock_guard<std::mutex> lock(mutex_); return stream_; }
void Receiver::reset() { std::lock_guard<std::mutex> lock(mutex_); stream_=Stream{}; }
std::string Receiver::diagnostic() {
    std::lock_guard<std::mutex> lock(mutex_);
    return error_+(log_.is_open()?" | CSV logging ON":" | CSV logging OFF");
}
bool Receiver::toggleLogging() {
    std::lock_guard<std::mutex> lock(mutex_);
    if(log_.is_open()) { log_.close(); return false; }
    auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    std::string path="astraeus-"+std::to_string(ns)+".csv";
    log_.clear(); log_.open(path);
    if(!log_) { error_="Cannot open CSV in current directory"; return false; }
    error_="CSV: "+path;
    log_<<"local_receive_time,phone_timestamp,sequence_number,session_id,device_id,origin_revision,px,py,pz,qx,qy,qz,qw,vx,vy,vz,wx,wy,wz,velocity_flags,tracking_state,accepted\n";
    return true;
}
void Receiver::run() {
    while(running_) {
        uint8_t bytes[2048]; sockaddr_in from{}; int size=sizeof(from);
        int count=recvfrom(socket_,reinterpret_cast<char*>(bytes),sizeof(bytes),0,reinterpret_cast<sockaddr*>(&from),&size);
        double now=monotonicSeconds();
        std::lock_guard<std::mutex> lock(mutex_);
        if(count==SOCKET_ERROR) {
            int code=WSAGetLastError();
            if(code==WSAEMSGSIZE) ++stream_.invalid;
            else if(code!=WSAETIMEDOUT && code!=WSAEWOULDBLOCK) error_="Receive error "+std::to_string(code);
            continue;
        }
        auto pose=decode(bytes,size_t(count));
        if(!pose) { ++stream_.invalid; continue; }
        char address[INET_ADDRSTRLEN]{}; inet_ntop(AF_INET,&from.sin_addr,address,sizeof(address));
        std::string endpoint=std::string(address)+":"+std::to_string(ntohs(from.sin_port));
        bool accepted=stream_.ingest(*pose,endpoint,now);
        // Record locked-stream out-of-order samples too; foreign streams are excluded.
        if(log_.is_open() && pose->session==stream_.latest.session && pose->device==stream_.latest.device && endpoint==stream_.endpoint) {
            log_<<std::setprecision(12)<<now<<','<<pose->timestamp<<','<<pose->sequence<<','<<pose->session<<','<<pose->device<<','<<pose->revision;
            for(float v:pose->position) log_<<','<<v;
            for(float v:pose->orientation) log_<<','<<v;
            for(float v:pose->linear) log_<<','<<v;
            for(float v:pose->angular) log_<<','<<v;
            log_<<','<<int(pose->flags)<<','<<int(pose->state)<<','<<accepted<<'\n';
            if(stream_.received%60==0) log_.flush();
            if(!log_) { log_.close(); error_="CSV write failed; logging stopped"; }
        }
    }
}
}
