#pragma once
#include "diagnostics.hpp"
#include <ostream>
#include <iomanip>

namespace astraeus {
inline void diagnosticHeader(std::ostream& out) {
    out<<"local_receive_time,pose_timestamp,sequence_number,session_id,device_id,origin_revision,arcore_frame_timestamp,camera_timestamp,gyro_timestamp,accel_timestamp,tracking_state,tracking_failure_reason,tracking_quality";
    for(const char* prefix:{"raw","raw_user","world","user","fused"})
        for(const char* field:{"px","py","pz","qx","qy","qz","qw"}) out<<','<<prefix<<'_'<<field;
    out<<",gyro_x,gyro_y,gyro_z,bias_x,bias_y,bias_z,accel_x,accel_y,accel_z,gyro_hz,accel_hz,arcore_hz,output_hz,position_innovation,orientation_innovation,implied_speed,residual_position,residual_angle,last_jump_position,last_jump_angle,discontinuity_count,gyro_accuracy,accel_accuracy,clock_valid,uncalibrated,discontinuity,clock_offset_ns,camera_age_ns,gyro_anomalies,tracking_anomalies,clock_anomalies,android_log_drops,heap_mb,cpu_cores\n";
}
inline void diagnosticRow(std::ostream& out,const Diagnostics& d,double now) {
    out<<std::setprecision(12)<<now<<','<<d.timestamp<<','<<d.sequence<<','<<d.session<<','<<d.device<<','<<d.revision
       <<','<<d.frameTimestamp<<','<<d.cameraTimestamp<<','<<d.gyroTimestamp<<','<<d.accelTimestamp
       <<','<<int(d.state)<<','<<trackingFailureName(d.reason)<<','<<qualityName(d.quality);
    for(const Pose* p:{&d.raw,&d.rawUser,&d.world,&d.user,&d.output}) {
        for(float v:p->position) out<<','<<v;
        for(float v:p->orientation) out<<','<<v;
    }
    for(const auto* a:{&d.gyro,&d.bias,&d.accel}) for(float v:*a) out<<','<<v;
    for(float v:d.values) out<<','<<v;
    out<<','<<d.count<<','<<d.gyroAccuracy<<','<<d.accelAccuracy<<','<<int(d.clockValid)<<','<<int(d.uncalibrated)
       <<','<<int(d.discontinuity)<<','<<d.clockOffset<<','<<d.cameraAge<<','<<d.gyroAnomalies<<','<<d.anomalies
       <<','<<d.clockAnomalies<<','<<d.logDrops<<','<<d.heapMb<<','<<d.cpu<<'\n';
}
}
