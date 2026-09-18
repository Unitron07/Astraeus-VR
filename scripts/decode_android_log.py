"""Decode Astraeus length-prefixed Android binary logs without external packages."""
import argparse
import csv
import struct
from contextlib import ExitStack
from pathlib import Path

QUALITY = ['UNAVAILABLE', 'FULL_6DOF', 'INERTIAL_ONLY', 'RECOVERING', 'DEGRADED']
REASON = ['NONE', 'BAD_STATE', 'INSUFFICIENT_LIGHT', 'EXCESSIVE_MOTION',
          'INSUFFICIENT_FEATURES', 'CAMERA_UNAVAILABLE']


def reason(value):
    return REASON[value] if value < len(REASON) else 'UNKNOWN'


def pose_fields(data, offset, prefix):
    return dict(zip((prefix + '_' + k for k in ['px', 'py', 'pz', 'qx', 'qy', 'qz', 'qw']),
                    struct.unpack_from('<7f', data, offset)))


def decode(data):
    if len(data) < 8:
        raise ValueError('Short record')
    magic, version, kind, size = struct.unpack_from('<4sBBH', data)
    if size != len(data):
        raise ValueError('Record length mismatch')
    if magic == b'ARAW' and version == 1 and kind == 1 and size == 80:
        frame, camera, arrival = struct.unpack_from('<3Q', data, 8)
        row = dict(arcore_frame_timestamp=frame, camera_timestamp=camera, arrival_timestamp=arrival,
                   tracking_state=data[32], tracking_failure_reason=reason(data[33]), clock_valid=data[34])
        row.update(pose_fields(data, 36, 'raw'))
        row.update(zip(['sensor_qx', 'sensor_qy', 'sensor_qz', 'sensor_qw'], struct.unpack_from('<4f', data, 64)))
        return 'raw', row
    if magic == b'AIMU' and version == 1 and kind in (1, 2) and size == 48:
        t, x, y, z, bx, by, bz, accuracy, uncalibrated = struct.unpack_from('<Q6fii', data, 8)
        return 'imu', dict(sensor='gyro' if kind == 1 else 'accel', timestamp=t,
                           x=x, y=y, z=z, bias_x=bx, bias_y=by, bias_z=bz,
                           accuracy=accuracy, uncalibrated=uncalibrated)
    if magic != b'ASTR' or version != 3 or (kind, size) not in ((1, 112), (2, 352)):
        raise ValueError('Unsupported record schema')
    seq, device, session, timestamp, revision = struct.unpack_from('<IIQQI', data, 8)
    row = dict(sequence_number=seq, device_id=device, session_id=session,
               pose_timestamp=timestamp, origin_revision=revision, tracking_state=data[38])
    if kind == 1:
        row.update(pose_fields(data, 40, 'fused'))
        row.update(zip(['vx', 'vy', 'vz', 'wx', 'wy', 'wz'], struct.unpack_from('<6f', data, 68)))
        row.update(velocity_flags=data[39], tracking_failure_reason=reason(data[92]),
                   tracking_quality=QUALITY[data[93]], gyro_timestamp=struct.unpack_from('<Q', data, 96)[0],
                   visual_timestamp=struct.unpack_from('<Q', data, 104)[0])
        return 'poses', row
    row.update(zip(['arcore_frame_timestamp', 'camera_timestamp', 'gyro_timestamp', 'accel_timestamp'],
                   struct.unpack_from('<4Q', data, 40)))
    for offset, prefix in [(72, 'raw'), (100, 'raw_user'), (128, 'world'), (156, 'user'), (320, 'fused')]:
        row.update(pose_fields(data, offset, prefix))
    row.update(zip(['gyro_x', 'gyro_y', 'gyro_z', 'bias_x', 'bias_y', 'bias_z', 'accel_x', 'accel_y', 'accel_z'],
                   struct.unpack_from('<9f', data, 184)))
    row.update(zip(['gyro_hz', 'accel_hz', 'arcore_hz', 'output_hz', 'position_innovation',
                    'orientation_innovation', 'implied_speed', 'residual_position', 'residual_angle',
                    'last_jump_position', 'last_jump_angle'], struct.unpack_from('<11f', data, 220)))
    row.update(zip(['discontinuity_count', 'gyro_accuracy', 'accel_accuracy'], struct.unpack_from('<Iii', data, 264)))
    row.update(clock_valid=data[276], uncalibrated=data[277], discontinuity=data[278],
               tracking_quality=QUALITY[data[279]], tracking_failure_reason=reason(data[348]))
    row.update(zip(['clock_offset_ns', 'camera_age_ns'], struct.unpack_from('<qq', data, 280)))
    row.update(zip(['gyro_anomalies', 'tracking_anomalies', 'clock_anomalies', 'android_log_drops'],
                   struct.unpack_from('<4I', data, 296)))
    row.update(zip(['heap_mb', 'cpu_cores'], struct.unpack_from('<2f', data, 312)))
    return 'diagnostics', row


def convert(source, prefix):
    counts = {}
    with ExitStack() as stack:
        input_file = stack.enter_context(Path(source).open('rb'))
        writers = {}
        while length := input_file.read(4):
            if len(length) != 4:
                raise ValueError('Truncated record length')
            size = struct.unpack('>I', length)[0]  # DataOutputStream envelope is big-endian.
            if size not in (48, 80, 112, 352):
                raise ValueError(f'Invalid record size {size}')
            data = input_file.read(size)
            if len(data) != size:
                raise ValueError('Truncated record payload')
            kind, row = decode(data)
            if kind not in writers:
                output = stack.enter_context(Path(f'{prefix}.{kind}.csv').open('w', newline='', encoding='utf-8'))
                writers[kind] = csv.DictWriter(output, fieldnames=list(row))
                writers[kind].writeheader()
            writers[kind].writerow(row)
            counts[kind] = counts.get(kind, 0) + 1
    return counts


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--prefix', type=Path, help='Output CSV prefix; defaults to input without extension')
    args = parser.parse_args()
    print(convert(args.input, args.prefix or args.input.with_suffix('')))
