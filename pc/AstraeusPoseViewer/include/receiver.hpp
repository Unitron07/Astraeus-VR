#pragma once
#include "stream.hpp"
#include <winsock2.h>
#include <ws2tcpip.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <fstream>

namespace astraeus {
double monotonicSeconds();
class Receiver {
    SOCKET socket_=INVALID_SOCKET;
    std::atomic<bool> running_{false};
    std::thread worker_;
    std::mutex mutex_;
    Stream stream_;
    std::ofstream log_;
    std::string error_;
    void run();
public:
    ~Receiver();
    void start(uint16_t port);
    uint16_t boundPort() const;
    Stream snapshot();
    void reset();
    bool toggleLogging();
    std::string diagnostic();
};
}
