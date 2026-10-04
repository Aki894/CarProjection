import struct
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from aoa_probe import Frames, command, descriptors, md_info, reply, serve, strings


class ProbeTests(unittest.TestCase):
    def test_aoa_control_sequence_reenumerates_then_handles_bulk(self):
        def event(request_type, request, index=0, length=0):
            return struct.pack('<BBHHH', request_type, request, 0, index, length) + b'\x04\0\0\0'
        gadget = SimpleNamespace(ep0=10, rx=11, tx=12, accessory=Mock())
        reads = [event(0xc0, 51, length=2), event(0x40, 52, index=0, length=6),
                 b'Baidu\0', event(0x40, 53), b'', b'\0' * 8 + b'\x02\0\0\0',
                 command(0x18001)]
        captured = []
        def write(_fd, data):
            captured.append((_fd, bytes(data)))
            return len(data)
        with patch('aoa_probe.time.monotonic', side_effect=[0, 0, 0, 0, 0, 121]), \
                patch('aoa_probe.select.select', return_value=([10], [], [])), \
                patch('aoa_probe.os.read', side_effect=reads), \
                patch('aoa_probe.os.write', side_effect=write) as writes, \
                patch('aoa_probe.log'):
            serve(gadget, 120)
        gadget.accessory.assert_called_once()
        self.assertEqual(writes.call_args_list[0].args, (10, b'\x01\x00'))
        self.assertEqual(captured[1], (12, command(0x10002, b'\x08\x01')))
    def test_descriptors_match_functionfs_and_usb_layout(self):
        data = descriptors()
        magic, length, flags, fs_count, hs_count = struct.unpack_from('<IIIII', data)
        self.assertEqual((magic, length, flags, fs_count, hs_count), (3, len(data), 195, 3, 3))
        position = 20
        for packet_size in (64, 512):
            self.assertEqual(data[position:position + 9], bytes((9, 4, 0, 0, 2, 255, 255, 0, 1)))
            position += 9
            for address in (1, 0x82):
                self.assertEqual(struct.unpack_from('<BBBBHB', data, position),
                                 (7, 5, address, 2, packet_size, 0))
                position += 7
        self.assertEqual(position, len(data))
        text = strings()
        self.assertEqual(struct.unpack_from('<IIII', text), (2, len(text), 1, 1))
        self.assertEqual(struct.unpack_from('<H', text, 16)[0], 0x409)
        self.assertTrue(text.endswith(b'\0'))

    def test_fragmented_and_coalesced_usb_transfers(self):
        stream = command(0x18001, b'\x08\x01\x10\x00') + command(0x18003)
        for split in range(len(stream) + 1):
            parser = Frames()
            result = parser.feed(stream[:split]) + parser.feed(stream[split:])
            self.assertEqual([struct.unpack_from('>I', frame, 4)[0] for _, frame in result],
                             [0x18001, 0x18003])
            self.assertEqual(parser.buffer, b'')

    def test_initial_replies_match_android_wire_format(self):
        frame = command(0x18001)[8:]
        self.assertEqual(reply(1, frame), bytes.fromhex('000000010000000a00020000000100020801'))
        response = reply(1, command(0x18003)[8:])
        self.assertEqual(response, command(0x10004, md_info()))
        self.assertIn(b'Android', response)
        self.assertIsNone(reply(1, command(0x18007)[8:]))
        self.assertIsNone(reply(2, frame))

    def test_malformed_frames_fail_before_allocating_large_payloads(self):
        for header in (struct.pack('>II', 0, 8), struct.pack('>II', 1, 0),
                       struct.pack('>II', 1, 0x7fffffff)):
            with self.assertRaises(ValueError):
                Frames().feed(header)
        with self.assertRaises(ValueError):
            reply(1, struct.pack('>HHI', 5, 0, 0x18001))


if __name__ == '__main__':
    unittest.main()
