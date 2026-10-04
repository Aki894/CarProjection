import struct
import subprocess
import queue
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from aoa_probe import BulkIO, Frames, Gadget, command, descriptors, md_info, reply, run, serve, strings


class ProbeTests(unittest.TestCase):
    def test_kernel_stuck_child_does_not_cause_unbounded_timeout_wait(self):
        process = Mock(pid=43210)
        process.wait.side_effect = [subprocess.TimeoutExpired('modprobe', 15),
                                    subprocess.TimeoutExpired('modprobe', 2)]
        with patch('aoa_probe.subprocess.Popen', return_value=process) as spawn, \
                patch('aoa_probe.os.killpg') as kill, patch('aoa_probe.log') as logs:
            with self.assertRaisesRegex(TimeoutError, '43210'):
                run('modprobe', '-r', 'g_serial')
        self.assertEqual([call.kwargs['timeout'] for call in process.wait.call_args_list], [15, 2])
        kill.assert_called_once()
        self.assertIsNotNone(spawn.call_args.kwargs['stdout'])
        self.assertTrue(any(call.args[0] == 'system_command_stuck' for call in logs.call_args_list))

    def test_nonzero_command_can_be_queried_without_aborting(self):
        process = Mock()
        process.wait.return_value = 3
        with patch('aoa_probe.subprocess.Popen', return_value=process), patch('aoa_probe.log'):
            self.assertEqual(run('systemctl', 'is-active', '--quiet', 'unused', check=False), 3)
            with self.assertRaises(subprocess.CalledProcessError):
                run('modprobe', 'missing')

    def test_aoa_control_sequence_reenumerates_then_handles_bulk(self):
        def event(request_type, request, index=0, length=0):
            return struct.pack('<BBHHH', request_type, request, 0, index, length) + b'\x04\0\0\0'
        gadget = SimpleNamespace(ep0=10, rx=11, tx=12, accessory=Mock(), bulk=None)
        reads = [event(0xc0, 51, length=2), event(0x40, 52, index=0, length=6),
                 b'Baidu\0', event(0x40, 53), b'', b'\0' * 8 + b'\x02\0\0\0',
                 ]
        captured = []
        def write(_fd, data):
            captured.append((_fd, bytes(data)))
            return len(data)
        bulk = SimpleNamespace(enabled=threading.Event(), received=queue.Queue(),
                               outgoing=queue.Queue(), errors=queue.Queue())
        bulk.received.put(command(0x18001))
        with patch('aoa_probe.time.monotonic', side_effect=[0, 0, 0, 0, 0, 121]), \
                patch('aoa_probe.select.select', return_value=([10], [], [])), \
                patch('aoa_probe.os.read', side_effect=reads), \
                patch('aoa_probe.os.write', side_effect=write) as writes, \
                patch('aoa_probe.BulkIO', return_value=bulk), \
                patch('aoa_probe.log'):
            serve(gadget, 120, 'session')
        gadget.accessory.assert_called_once()
        self.assertEqual(writes.call_args_list[0].args, (10, b'\x01\x00'))
        self.assertEqual(bulk.outgoing.get_nowait(), command(0x10002, b'\x08\x01'))

    def test_bind_phase_never_reenumerates_or_starts_bulk(self):
        gadget = SimpleNamespace(ep0=10, rx=11, tx=12, accessory=Mock(), bulk=None)
        setup = struct.pack('<BBHHH', 0x40, 53, 0, 0, 0) + b'\x04\0\0\0'
        with patch('aoa_probe.time.monotonic', side_effect=[0, 0, 121]), \
                patch('aoa_probe.select.select', return_value=([10], [], [])), \
                patch('aoa_probe.os.read', return_value=setup), \
                patch('aoa_probe.os.write'), patch('aoa_probe.BulkIO') as bulk, \
                patch('aoa_probe.log'):
            serve(gadget, 120)
        gadget.accessory.assert_not_called()
        bulk.assert_not_called()

    def test_blocked_bulk_read_does_not_block_control_loop_deadline(self):
        entered, release = threading.Event(), threading.Event()
        events = [struct.pack('<BBHHH', 0x40, 53, 0, 0, 0) + b'\x04\0\0\0',
                  b'\0' * 8 + b'\x02\0\0\0']
        def blocked_read(fd, size):
            if fd == 10:
                return b'' if size == 0 else events.pop(0)
            entered.set()
            release.wait(2)
            return b''
        def select_events(*_args):
            if events:
                return [10], [], []
            time.sleep(0.005)
            return [], [], []
        with patch('aoa_probe.os.read', side_effect=blocked_read), patch('aoa_probe.log'):
            gadget = SimpleNamespace(ep0=10, rx=11, tx=12, accessory=Mock(), bulk=None)
            started = time.monotonic()
            try:
                with patch('aoa_probe.select.select', side_effect=select_events):
                    serve(gadget, 0.08, 'session')
                self.assertLess(time.monotonic() - started, 0.5)
                self.assertTrue(entered.is_set())
            finally:
                bulk = gadget.bulk
                bulk.stop.set()
                bulk.enabled.set()
                release.set()
                self.assertTrue(bulk.join())

    def test_bulk_writer_handles_partial_writes(self):
        finished = threading.Event()
        chunks = []
        def write(_fd, data):
            count = min(2, len(data))
            chunks.append(data[:count])
            if b''.join(chunks) == b'abcdef':
                finished.set()
            return count
        with patch('aoa_probe.os.read', return_value=b''), \
                patch('aoa_probe.os.write', side_effect=write), patch('aoa_probe.log'):
            bulk = BulkIO(11, 12)
            bulk.enabled.set()
            try:
                bulk.outgoing.put(b'abcdef')
                self.assertTrue(finished.wait(1))
            finally:
                bulk.stop.set()
                bulk.enabled.set()
                self.assertTrue(bulk.join())
        self.assertEqual(b''.join(chunks), b'abcdef')

    def test_cleanup_does_not_close_live_worker_descriptors_or_restore_driver(self):
        gadget = Gadget(True)
        gadget.created = True
        gadget.serial_released = True
        gadget.fds = [10, 11, 12]
        gadget.bulk = SimpleNamespace(stop=threading.Event(), enabled=threading.Event(),
                                      join=Mock(return_value=False))
        with patch('aoa_probe.put') as put, patch('aoa_probe.os.close') as close, \
                patch('aoa_probe.run') as run, patch('aoa_probe.log'):
            self.assertFalse(gadget.close())
        put.assert_called_once()
        close.assert_not_called()
        run.assert_not_called()
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
