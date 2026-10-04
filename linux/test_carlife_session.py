import queue
import struct
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from aoa_probe import Frames
from carlife_input import Pad
from carlife_media import AccessUnits, PcmSource
from carlife_session import Session
from carlife_tx import TxQueue
from carlife_wire import CMD, VIDEO, MEDIA, TTS, TOUCH, blob, command, fields, int32, integer, md_info, parse, stream


def inner(packet):
    channel, size = struct.unpack_from('>II', packet)
    assert len(packet) == size + 8
    return channel, *parse(channel, packet[8:])


class SessionTests(unittest.TestCase):
    def setUp(self):
        self.logs = Mock()
        self.session = Session(self.logs, video=False, audio='off')
        self.bulk = SimpleNamespace(outgoing=TxQueue(), last_video_write=-float('inf'))

    def receive(self, message, payload=b'', channel=CMD, now=1):
        packet = command(message, payload, channel)
        self.session.receive(channel, packet[8:], self.bulk, now)

    def drain(self):
        packets = []
        while True:
            try:
                packets.append(inner(self.bulk.outgoing.get(timeout=0)))
            except queue.Empty:
                return packets

    def test_full_handshake_matches_android_order_and_sizes(self):
        self.receive(0x18001, integer(1, 1) + integer(2, 0))
        self.receive(0x18003)
        replies = self.drain()
        self.assertEqual([p[1] for p in replies], [0x10002, 0x10004, 0x10051])
        self.assertEqual(replies[0][2], b'\x08\x01')
        self.assertEqual(replies[1][2], md_info())
        features = fields(replies[2][2])
        self.assertEqual(int32(features, 1), 4)
        self.receive(0x18007, integer(1, 1280) + integer(2, 480) + integer(3, 30))
        replies = self.drain()
        self.assertEqual([p[1] for p in replies], [0x10008, 0x1001b])
        self.assertEqual([int32(fields(replies[0][2]), i) for i in (1, 2, 3)], [1280, 480, 10])
        self.receive(0x18027)
        self.assertEqual([p[1] for p in self.drain()], [0x1001b, 0x10018, 0x1004b])
        self.receive(0x18009)
        self.session.tick(self.bulk, 2)
        heartbeat = self.bulk.outgoing.get(timeout=0)
        self.assertEqual(inner(heartbeat)[:2], (VIDEO, 0x20002))
        self.assertEqual(len(heartbeat), 20)

    def test_audio_init_data_end_with_no_catchup_burst(self):
        self.session = Session(self.logs, video=False, audio='tts', audio_seconds=.1)
        self.session.size = (800, 480)
        self.receive(0x18009, now=1)
        self.session.tick(self.bulk, 1)
        packets = self.drain()
        audio = [p for p in packets if p[0] == TTS]
        self.assertEqual([p[1] for p in audio], [0x40001, 0x40003])
        self.assertEqual([int32(fields(audio[0][2]), i) for i in (1, 2, 3)], [48000, 1, 16])
        self.assertEqual(len(audio[1][2]), 1920)
        self.session.tick(self.bulk, 1.08)
        self.assertEqual(len([p for p in self.drain() if p[1] == 0x40003]), 1)
        self.session.tick(self.bulk, 1.2)
        self.assertEqual([p[1] for p in self.drain()], [0x40002])

    def test_priority_and_backpressure_preserve_control(self):
        self.bulk.outgoing.put_nowait(stream(VIDEO, 0x20001, b'first'))
        self.bulk.outgoing.put_nowait(stream(VIDEO, 0x20001, b'second'))
        with self.assertRaises(queue.Full):
            self.bulk.outgoing.put_nowait(stream(VIDEO, 0x20001, b'third'))
        self.bulk.outgoing.put_nowait(stream(TTS, 0x40003, b'pcm'))
        self.bulk.outgoing.put_nowait(stream(VIDEO, 0x20002, timestamp_ms=1))
        self.bulk.outgoing.put_nowait(stream(VIDEO, 0x20002, timestamp_ms=2))
        self.bulk.outgoing.put_nowait(command(0x10018))
        self.assertEqual([p[1] for p in self.drain()], [0x10018, 0x20002, 0x40003, 0x20001, 0x20001])
        self.assertEqual(self.bulk.outgoing.bytes, 0)

    def test_unknown_and_bad_protobuf_do_not_desynchronize_next_frame(self):
        packets = command(0x18007, b'\x08\x80') + command(0x18001)
        reader = Frames()
        for part in (packets[:7], packets[7:13], packets[13:]):
            for channel, frame in reader.feed(part):
                self.session.receive(channel, frame, self.bulk, 1)
        self.assertEqual([p[1] for p in self.drain()], [0x10002])
        self.assertTrue(any(call.args[0] == 'session_parse_error' for call in self.logs.call_args_list))

    def test_signed_touch_delta_and_native_back_mapping(self):
        self.receive(0x1005a, integer(1, 10), TOUCH)
        self.receive(0x1005b, integer(1, 20) + integer(2, -48) + integer(3, 0), TOUCH)
        self.receive(0x68008, integer(1, 0xe), TOUCH)
        events = [call.kwargs for call in self.logs.call_args_list if call.args[0] == 'input_event']
        self.assertTrue(any(event.get('dx') == -48 for event in events))
        self.assertTrue(any(event.get('kind') == 'knob' and event.get('b') == -1 for event in events))
        self.assertTrue(any(event.get('kind') == 'knob' and event.get('a') == 4 for event in events))

    def test_encryption_feature_blocks_plaintext_media(self):
        payload = integer(1, 1) + blob(2, blob(1, 'CONTENT_ENCRYPTION') + integer(2, 1))
        self.receive(0x18052, payload)
        self.assertTrue(self.session.blocked)
        self.session.started = True
        self.session.tick(self.bulk, 2)
        self.assertEqual(self.drain(), [])

    def test_liveness_learns_periodic_traffic_before_timeout(self):
        self.session.tick(self.bulk, 100)  # No learned cadence: quiet HU is allowed.
        for now in (1, 2, 3):
            self.receive(0x18010, now=now)
        with self.assertRaisesRegex(RuntimeError, 'HU_SILENT_TIMEOUT'):
            self.session.tick(self.bulk, 12)

    def test_pause_and_reinit_release_encoder_and_queued_media(self):
        self.session.started = True
        self.session.video = Mock()
        encoder = self.session.video
        self.bulk.outgoing.put_nowait(stream(VIDEO, 0x20001, b'frame'))
        self.receive(0x1800a)
        encoder.close.assert_called_once()
        self.assertFalse(self.session.started)
        self.assertEqual(self.drain(), [])


class MediaInputTests(unittest.TestCase):
    def test_double_tap_click_deduplicates_mechanical_click_and_drag_never_clicks(self):
        events = []
        pad = Pad(lambda kind, **data: events.append((kind, data)))
        pad.down(0); pad.up(.05); pad.down(.15); pad.up(.2); pad.click(.25)
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0], ('knob', dict(a=1, b=0, c=0)))
        events.clear()
        pad = Pad(lambda kind, **data: events.append((kind, data)))
        pad.down(0); pad.up(.05); pad.down(.15); pad.move(96, 0); pad.up(.2)
        self.assertEqual([kind for kind, _ in events], ['wheel', 'wheel'])

    def test_annexb_split_at_every_usb_boundary_preserves_access_units(self):
        first = b'\0\0\0\1\x09\xf0\0\0\0\1\x67sps\0\0\1\x68pps\0\0\1\x65picture'
        second = b'\0\0\1\x09\xf0\0\0\1\x41second'
        data = first + second
        for cut in range(len(data) + 1):
            parser = AccessUnits()
            frames = parser.feed(data[:cut]) + parser.feed(data[cut:], eof=True)
            self.assertEqual(frames, [first, second])

    def test_pcm_gain_and_sample_alignment(self):
        pcm = PcmSource(48000, 1, .03)
        chunk = pcm.chunk()
        self.assertEqual(len(chunk), 1920)
        self.assertLessEqual(max(abs(value) for value in struct.unpack('<960h', chunk)), 984)
        self.assertEqual(len(PcmSource(44100, 2, .03).chunk()), 3528)

    def test_protobuf_signed_int32_and_truncation(self):
        self.assertEqual(int32(fields(integer(1, -123)), 1), -123)
        for data in (b'\x08\x80', b'\x12\x05x', b'\0', b'\x08' + b'\xff' * 10):
            with self.assertRaises(ValueError):
                fields(data)


if __name__ == '__main__':
    unittest.main()
