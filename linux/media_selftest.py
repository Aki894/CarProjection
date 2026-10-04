#!/usr/bin/env python3
"""Exercise the real H.264 source and mock HU session without touching USB."""
import shutil
import struct
import subprocess
import tempfile
import time
from types import SimpleNamespace
from carlife_media import VideoSource
from carlife_session import Session
from carlife_tx import TxQueue
from carlife_wire import fields, integer, parse


def main():
    if not shutil.which('ffmpeg') or not shutil.which('ffprobe'):
        raise SystemExit('Install ffmpeg first: sudo apt-get install ffmpeg')
    source = VideoSource(800, 480, 10)
    frames = []
    try:
        deadline = time.monotonic() + 12
        while len(frames) < 12 and time.monotonic() < deadline:
            frame = source.frame()
            if frame:
                frames.append(frame)
            else:
                time.sleep(.01)
    finally:
        source.close()
    if len(frames) < 12:
        raise RuntimeError('encoder did not produce 12 frames in 12 seconds')
    with tempfile.TemporaryDirectory() as directory:
        path = directory + '/test.h264'
        with open(path, 'wb') as output:
            output.write(b''.join(frames))
        # Decode actual packets, not merely their header shapes.
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin',
                        '-i', path, '-f', 'null', '-'], check=True, timeout=15)
        result = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                                 '-show_entries', 'stream=width,height,codec_name',
                                 '-of', 'csv=p=0', path], check=True, capture_output=True,
                                text=True, timeout=10)
        if 'h264,800,480' not in result.stdout:
            raise RuntimeError(f'unexpected encoded video: {result.stdout}')
        passthrough = VideoSource(800, 480, 10, path, copy=True)
        copied = []
        try:
            deadline = time.monotonic() + 10
            while len(copied) < 18 and time.monotonic() < deadline:
                frame = passthrough.frame()
                if frame:
                    copied.append(frame)
                else:
                    time.sleep(.01)
        finally:
            passthrough.close()
        if len(copied) < 18:
            raise RuntimeError('H.264 passthrough failed')
        copy_path = directory + '/copy.h264'
        with open(copy_path, 'wb') as output:
            output.write(b''.join(copied))
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin',
                        '-i', copy_path, '-f', 'null', '-'], check=True, timeout=15)
    # Run complete negotiation and audio ticks against a byte-level fake HU.
    logs = []
    session = Session(lambda event, **data: logs.append((event, data)), video=False)
    bulk = SimpleNamespace(outgoing=TxQueue(), last_video_write=-float('inf'))
    for message, payload in [(0x18001, b''), (0x18003, b''), (0x18027, b''),
                             (0x18007, integer(1, 800) + integer(2, 480) + integer(3, 30)),
                             (0x18009, b'')]:
        session.receive(1, struct.pack('>HHI', len(payload), 0, message) + payload, bulk, 1)
    session.tick(bulk, 1)
    messages = []
    while bulk.outgoing.qsize():
        packet = bulk.outgoing.get(timeout=0)
        channel = struct.unpack_from('>I', packet)[0]
        messages.append((channel, *parse(channel, packet[8:])))
    expected = {0x10002, 0x10004, 0x10051, 0x1001b, 0x10018, 0x1004b, 0x10008,
                0x20002, 0x40001, 0x40003}
    if not expected.issubset({message for _, message, _ in messages}):
        raise RuntimeError('mock HU handshake/audio incomplete')
    session.close()
    print(f'PASS: {len(frames)} encoded and {len(copied)} passthrough H.264 frames decoded at 800x480; mock HU handshake, heartbeat and PCM16')
    print('This validates userspace only; USB/UDC/real-HU compatibility still needs hardware testing.')


if __name__ == '__main__':
    main()
