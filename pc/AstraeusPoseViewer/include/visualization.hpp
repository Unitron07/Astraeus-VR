#pragma once
#include "pose.hpp"
#include <windows.h>
namespace astraeus {
void drawWorld(HDC dc,const RECT& area,const Pose& pose,bool valid,const Pose* raw=nullptr,int mode=0);
}
