#include "visualization.hpp"
#include <algorithm>

namespace astraeus {
using V=std::array<float,3>;
void drawWorld(HDC dc,const RECT& area,const Pose& pose,bool valid,const Pose* raw,int mode) {
    // Orthographic 3D view: world scale remains fixed at 120 pixels per meter.
    auto project=[&](V p) -> POINT {
        double x=(area.left+area.right)/2+120*(0.866*double(p[0])+0.5*double(p[2]));
        double y=(area.top+area.bottom)/2+120*(-double(p[1])+0.25*double(p[0])-0.433*double(p[2]));
        return {LONG(std::clamp(x,-100000.0,100000.0)),LONG(std::clamp(y,-100000.0,100000.0))};
    };
    auto line=[&](V a,V b,COLORREF color,int width=1) {
        auto pen=CreatePen(PS_SOLID,width,color); auto old=SelectObject(dc,pen);
        auto p=project(a),q=project(b); MoveToEx(dc,p.x,p.y,nullptr); LineTo(dc,q.x,q.y);
        SelectObject(dc,old); DeleteObject(pen);
    };
    SaveDC(dc); IntersectClipRect(dc,area.left,area.top,area.right,area.bottom);
    for(int i=-4;i<=4;++i) {
        float x=i*0.5f;
        line({x,0,-2},{x,0,2},RGB(220,225,230)); line({-2,0,x},{2,0,x},RGB(220,225,230));
    }
    line({0,0,0},{1,0,0},RGB(220,40,40),3);
    line({0,0,0},{0,1,0},RGB(20,150,50),3);
    line({0,0,0},{0,0,1},RGB(40,90,220),3);
    const char* labels[]={"+X","+Y","+Z"};
    for(int i=0;i<3;++i) { V v{}; v[i]=1; auto p=project(v); TextOutA(dc,p.x,p.y,labels[i],2); }
    auto object=[&](const Pose& objectPose,COLORREF color) {
        auto transform=[&](V p) {
            auto q=objectPose.orientation;
            V t{2*(q[1]*p[2]-q[2]*p[1]),2*(q[2]*p[0]-q[0]*p[2]),2*(q[0]*p[1]-q[1]*p[0])};
            return V{objectPose.position[0]+p[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],
                     objectPose.position[1]+p[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2],
                     objectPose.position[2]+p[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0]};
        };
        V corners[8];
        for(int i=0;i<8;++i) corners[i]=transform({(i&1)?0.12f:-0.12f,(i&2)?0.07f:-0.07f,(i&4)?0.06f:-0.06f});
        for(int i=0;i<8;++i) for(int bit=1;bit<=4;bit*=2) if(!(i&bit)) line(corners[i],corners[i|bit],color,3);
        line(transform({0,0,0}),transform({0,0,-0.4f}),color,3);
    };
    if(raw && mode!=0) object(*raw,RGB(0,155,160));
    if(valid && mode!=1) object(pose,RGB(140,45,190));
    RestoreDC(dc,-1);
}
}
