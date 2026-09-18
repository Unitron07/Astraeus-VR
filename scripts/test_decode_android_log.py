import csv
import struct
import tempfile
import unittest
from pathlib import Path
from decode_android_log import convert, decode

ROOT = Path(__file__).resolve().parents[1]


class LogTest(unittest.TestCase):
    def test_fixture_decode_and_buffered_file(self):
        packets = [bytes.fromhex((ROOT / 'protocol' / n).read_text())
                   for n in ['golden_fused.hex', 'golden_diagnostics.hex']]
        packets.append(struct.pack('<4sBBHQ6fii', b'AIMU', 1, 1, 48, 123, 0, .5, 0, 0, 0, 0, 3, 0))
        packets.append(struct.pack('<4sBBH3Q4B11f', b'ARAW', 1, 1, 80, 10, 20, 30, 1, 2, 1, 0,
                                   1, 2, 3, 0, 0, 0, 1, 0, 0, 0, 1))
        kind, raw = decode(packets[-1])
        self.assertEqual(kind, 'raw')
        self.assertEqual(raw['tracking_failure_reason'], 'INSUFFICIENT_LIGHT')
        self.assertEqual(raw['raw_pz'], 3)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sample.bin'
            path.write_bytes(b''.join(struct.pack('>I', len(p)) + p for p in packets))
            prefix = Path(directory) / 'decoded'
            self.assertEqual(convert(path, prefix), dict(poses=1, diagnostics=1, imu=1, raw=1))
            with Path(f'{prefix}.diagnostics.csv').open() as stream:
                row = next(csv.DictReader(stream))
                self.assertEqual(row['tracking_quality'], 'RECOVERING')
                self.assertEqual(float(row['world_px']), -1)
                self.assertEqual(float(row['fused_px']), 1)
            path.write_bytes(b'\x00\x00\x00\x70broken')
            with self.assertRaises(ValueError):
                convert(path, prefix)

    def test_unknown_schema_rejected(self):
        with self.assertRaises(ValueError):
            decode(bytes(48))


if __name__ == '__main__':
    unittest.main()
