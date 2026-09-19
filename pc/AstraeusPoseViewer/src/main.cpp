#include "receiver.hpp"
#include "visualization.hpp"
#include <sstream>
#include <iomanip>
#include <memory>
#include <stdexcept>

using namespace astraeus;
static std::unique_ptr<Receiver> receiver;
static uint16_t port=4242;
static int viewMode=2;
static LRESULT CALLBACK windowProc(HWND window,UINT message,WPARAM wParam,LPARAM lParam) {
    switch(message) {
    case WM_CREATE:
        CreateWindowA("BUTTON","Toggle CSV logging",WS_VISIBLE|WS_CHILD,16,12,165,30,window,(HMENU)1,nullptr,nullptr);
        CreateWindowA("BUTTON","Reset stream",WS_VISIBLE|WS_CHILD,190,12,120,30,window,(HMENU)2,nullptr,nullptr);
        CreateWindowA("BUTTON","View: Both",WS_VISIBLE|WS_CHILD,320,12,170,30,window,(HMENU)3,nullptr,nullptr);
        SetTimer(window,1,33,nullptr); return 0;
    case WM_COMMAND:
        if(LOWORD(wParam)==1) receiver->toggleLogging();
        if(LOWORD(wParam)==2) receiver->reset();
        if(LOWORD(wParam)==3) {
            viewMode=(viewMode+1)%3;
            SetWindowTextA(GetDlgItem(window,3),viewMode==0?"View: Astraeus":viewMode==1?"View: Raw ARCore":"View: Both");
        }
        return 0;
    case WM_TIMER: InvalidateRect(window,nullptr,FALSE); return 0;
    case WM_ERASEBKGND: return 1;
    case WM_PAINT: {
        PAINTSTRUCT paint{}; HDC target=BeginPaint(window,&paint); RECT area{}; GetClientRect(window,&area);
        HDC dc=CreateCompatibleDC(target); HBITMAP bitmap=CreateCompatibleBitmap(target,area.right,area.bottom);
        auto old=SelectObject(dc,bitmap); FillRect(dc,&area,(HBRUSH)(COLOR_WINDOW+1)); SetBkMode(dc,TRANSPARENT);
        auto s=receiver->snapshot(); double age=s.locked?monotonicSeconds()-s.lastReceive:0;
        std::ostringstream text; text<<std::fixed<<std::setprecision(4);
        text<<"Astraeus | UDP port "<<port<<" | "<<(!s.locked?"WAITING":age>1?"STALE":"RECEIVING")<<"\r\n";
        text<<"Endpoint: "<<s.endpoint<<" | Session: "<<s.latest.session<<" | Origin: "<<s.latest.revision<<"\r\n";
        text<<"Rate: "<<(age>1?0:s.rate)<<" Hz | Received: "<<s.received<<" | Accepted: "<<s.accepted<<"\r\n";
        text<<"Sequence gaps: "<<s.missing<<" | Duplicate/old: "<<s.outOfOrder<<" | Invalid: "<<s.invalid<<" | Other streams: "<<s.foreign<<"\r\n";
        text<<"Receive age: "<<age*1000<<" ms | Interarrival jitter: "<<s.jitterMs<<" ms | One-way latency: unavailable\r\n";
        text<<"Phone timestamp: "<<s.latest.timestamp<<" ns | Sequence: "<<s.latest.sequence<<" | "<<trackingName(s.latest.state)<<"\r\n";
        text<<"tracking_failure_reason: "<<trackingFailureName(s.latest.trackingFailureReason)<<"\r\n";
        text<<"Astraeus quality: "<<qualityName(s.latest.quality)<<" | Pose t: "<<s.latest.timestamp
            <<" | gyro t: "<<s.latest.gyroTimestamp<<"\r\n";
        bool diagnosticFresh=s.hasDiagnostics && monotonicSeconds()-s.diagnosticsReceive<1 && s.diagnostics.revision==s.latest.revision;
        if(diagnosticFresh) {
            const auto& d=s.diagnostics;
            text<<"Source Hz: ARCore "<<d.values[2]<<" | gyro "<<d.values[0]<<" | accel "<<d.values[1]<<" | output "<<d.values[3]<<"\r\n";
            text<<"Discontinuities: "<<d.count<<" | last "<<d.values[9]<<" m / "<<d.values[10]*57.29578<<" deg | residual "<<d.values[7]<<" m / "<<d.values[8]*57.29578<<" deg\r\n";
            text<<"Clock valid: "<<int(d.clockValid)<<" | gyro accuracy: "<<d.gyroAccuracy<<" | heap "<<d.heapMb<<" MB | CPU "<<d.cpu*100<<"% of one core\r\n";
            text<<"Raw ARCore XYZ: "<<d.raw.position[0]<<"  "<<d.raw.position[1]<<"  "<<d.raw.position[2]<<"\r\n";
            if(d.version==4) {
                text<<"Anchor #"<<d.anchorId<<": "<<trackingName(d.anchorState)<<" | Event: "<<eventName(d.event)<<" | diagnostic gaps: "<<s.diagnosticsMissing<<"\r\n";
                auto poseLine=[&](const char* label,const Pose& p) {
                    text<<label<<" XYZ: "; for(float v:p.position) text<<v<<' ';
                    text<<" | XYZW: "; for(float v:p.orientation) text<<v<<' '; text<<"\r\n";
                };
                text<<"Raw world XYZW: "; for(float v:d.raw.orientation) text<<v<<' '; text<<"\r\n";
                poseLine("Anchor world",d.anchorWorld); poseLine("Camera / anchor",d.cameraAnchor);
                text<<"Steps m/deg: raw "<<d.steps[0]<<'/'<<d.steps[1]*57.29578<<" | anchor "<<d.steps[2]<<'/'<<d.steps[3]*57.29578
                    <<" | relative "<<d.steps[4]<<'/'<<d.steps[5]*57.29578<<" | valid bits "<<int(d.stepFlags)<<"\r\n";
                text<<"Events: world "<<d.rawWorldUpdates<<" | relative "<<d.relativeDiscontinuities<<" | reacquire "<<d.reacquisitions<<" | anchor loss "<<d.anchorLosses<<"\r\n";
            }
        } else text<<"Layer diagnostics: waiting/stale (v1/v2 senders do not provide these)\r\n";
        text<<"Position XYZ (m): "; for(auto x:s.latest.position) text<<x<<"  "; text<<"\r\nQuaternion XYZW: ";
        for(auto x:s.latest.orientation) { text<<x<<"  "; }
        text<<"\r\nLinear velocity (m/s): ";
        for(auto x:s.latest.linear) { text<<x<<"  "; }
        text<<" | valid="<<bool(s.latest.flags&1)<<"\r\nAngular velocity (rad/s): ";
        for(auto x:s.latest.angular) { text<<x<<"  "; }
        text<<" | valid="<<bool(s.latest.flags&2)<<"\r\n";
        text<<receiver->diagnostic()<<"\r\nGrid: 0.5 m | Purple: Astraeus | Cyan: raw through user recenter ONLY | Rod points forward (-Z).";
        RECT content{16,52,area.right-16,540}; auto value=text.str(); DrawTextA(dc,value.c_str(),-1,&content,DT_LEFT|DT_NOPREFIX);
        RECT world{16,545,area.right-16,area.bottom-10};
        const Pose* raw=diagnosticFresh && s.diagnostics.state==2?&s.diagnostics.rawUser:nullptr;
        drawWorld(dc,world,s.latest,s.locked&&age<1&&s.latest.quality!=0,raw,viewMode);
        BitBlt(target,0,0,area.right,area.bottom,dc,0,0,SRCCOPY); SelectObject(dc,old); DeleteObject(bitmap); DeleteDC(dc);
        EndPaint(window,&paint); return 0;
    }
    case WM_DESTROY: KillTimer(window,1); PostQuitMessage(0); return 0;
    }
    return DefWindowProcA(window,message,wParam,lParam);
}
int WINAPI WinMain(HINSTANCE instance,HINSTANCE,LPSTR command,int show) {
    try {
        if(command && *command) {
            std::string arg(command); size_t end=0; int value=std::stoi(arg,&end);
            if(end!=arg.size() || value<1 || value>65535) throw std::runtime_error("Usage: AstraeusPoseViewer.exe [UDP port 1-65535]");
            port=uint16_t(value);
        }
        receiver=std::make_unique<Receiver>(); receiver->start(port);
        WNDCLASSA klass{}; klass.lpfnWndProc=windowProc; klass.hInstance=instance; klass.lpszClassName="AstraeusPoseViewer";
        klass.hCursor=LoadCursor(nullptr,IDC_ARROW); RegisterClassA(&klass);
        HWND window=CreateWindowA(klass.lpszClassName,"Astraeus Pose Viewer",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,1120,850,nullptr,nullptr,instance,nullptr);
        if(!window) throw std::runtime_error("Window creation failed");
        ShowWindow(window,show); MSG message{};
        while(GetMessage(&message,nullptr,0,0)>0) { TranslateMessage(&message); DispatchMessage(&message); }
        receiver.reset(); return 0;
    } catch(const std::exception& e) { MessageBoxA(nullptr,e.what(),"Astraeus error",MB_OK|MB_ICONERROR); receiver.reset(); return 1; }
}
