"""Headless H.264 test/file source and PCM16 source for Linux MD validation."""
from collections import deque
from array import array
import math
import json
import os
from pathlib import Path
import re
import select
import signal
import struct
import subprocess
import tempfile
import sys

AUD = re.compile(b'\x00\x00(?:\x00)?\x01\x09')
MAX_AU = 1024 * 1024 - 12


class AccessUnits:
    """Split Annex B on explicit access unit delimiters, never on USB chunks."""
    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data, eof=False):
        self.buffer.extend(data)
        starts = [match.start() for match in AUD.finditer(self.buffer)]
        result = []
        consumed = 0
        for boundary in starts[1:]:
            result.append(bytes(self.buffer[consumed:boundary]))
            consumed = boundary
        if consumed:
            del self.buffer[:consumed]
        if len(self.buffer) > MAX_AU or any(len(frame) > MAX_AU for frame in result):
            raise ValueError('H.264 access unit exceeds wire budget')
        if eof and self.buffer:
            if not AUD.search(self.buffer):
                raise ValueError('H.264 stream has no access unit delimiters')
            result.append(bytes(self.buffer))
            self.buffer.clear()
        return result


class VideoSource:
    def __init__(self, width, height, fps, video_file=None, copy=False):
        raw_h264 = False
        if copy:
            from fractions import Fraction
            info = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                                   '-show_entries', 'stream=codec_name,width,height,r_frame_rate,avg_frame_rate:format=format_name',
                                   '-of', 'json', str(video_file)], check=True, capture_output=True,
                                  text=True, timeout=8)
            metadata = json.loads(info.stdout)
            tracks = metadata.get('streams', [])
            if not tracks or tracks[0].get('codec_name') != 'h264':
                raise ValueError('copy-video requires H.264 input')
            track = tracks[0]
            if (track.get('width'), track.get('height')) != (width, height):
                raise ValueError('copy-video dimensions must match negotiated HU size')
            raw_h264 = metadata.get('format', {}).get('format_name') == 'h264'
            # Raw Annex B has no container timestamps: its playback rate is supplied
            # explicitly. ffprobe's demuxer defaults/guessed tbr are not authoritative.
            rate = track.get('avg_frame_rate', '0/1')
            if rate == '0/0':
                rate = track.get('r_frame_rate', '0/1')
            if not raw_h264 and abs(float(Fraction(rate)) - fps) > .01:
                raise ValueError('copy-video frame rate must match negotiated fps')
        self.errors = tempfile.TemporaryFile()
        self.units = AccessUnits()
        self.ready = deque()
        args = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-re']
        if video_file:
            args += ['-stream_loop', '-1']
            if raw_h264:
                args += ['-framerate', str(fps)]
            args += ['-i', str(video_file)]
            if not copy:
                args += ['-vf', f'scale={width}:{height}:force_original_aspect_ratio=decrease,'
                         f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,fps={fps},format=yuv420p']
        else:
            args += ['-f', 'lavfi', '-i', f'testsrc2=size={width}x{height}:rate={fps}']
        if copy:
            args += ['-an', '-c:v', 'copy', '-bsf:v', 'h264_mp4toannexb,h264_metadata=aud=insert']
        else:
            args += ['-an', '-c:v', 'libx264', '-threads', '1', '-preset', 'ultrafast',
                     '-tune', 'zerolatency', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p',
                     '-x264-params', f'keyint={fps}:scenecut=0:repeat-headers=1:aud=1', '-b:v', '600k']
        args += ['-f', 'h264', 'pipe:1']
        try:
            self.process = subprocess.Popen(args, stdin=subprocess.DEVNULL,
                                            stdout=subprocess.PIPE, stderr=self.errors,
                                            start_new_session=True)
        except BaseException:
            self.errors.close()
            raise
        os.set_blocking(self.process.stdout.fileno(), False)

    def frame(self):
        if self.ready:
            return self.ready.popleft()
        ready, _, _ = select.select([self.process.stdout], [], [], 0)
        if not ready:
            return None
        data = os.read(self.process.stdout.fileno(), 65536)
        self.ready.extend(self.units.feed(data, eof=not data))
        if self.ready:
            return self.ready.popleft()
        if not data:
            self.errors.seek(0)
            detail = self.errors.read(2048).decode(errors='replace')
            raise RuntimeError(f'video encoder ended: {detail}')
        return None

    def close(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
        try:
            self.process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(self.process.pid, signal.SIGKILL)
            try:
                self.process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                raise RuntimeError(f'encoder stuck PID={self.process.pid}')
        finally:
            self.process.stdout.close()
            self.errors.close()


class PcmSource:
    def __init__(self, rate, channels, gain, path=None):
        self.rate, self.channels = rate, channels
        self.offset = 0
        self.file_data = None
        if path:
            if Path(path).stat().st_size > 16 * 1024 * 1024:
                raise ValueError('PCM file exceeds 16 MiB test-source limit')
            raw = Path(path).read_bytes()
            if not raw or len(raw) % (2 * channels):
                raise ValueError('PCM file must be aligned little-endian PCM16')
            values = array('h')
            values.frombytes(raw)
            if sys.byteorder != 'little':
                values.byteswap()
            for index, value in enumerate(values):
                values[index] = round(value * gain)
            if sys.byteorder != 'little':
                values.byteswap()
            self.file_data = values.tobytes()
        else:
            # A one-second 440 Hz tone with silent gaps, at the requested wire rate.
            values = [round(32767 * gain * math.sin(2 * math.pi * 440 * i / rate))
                      if i < rate * .5 else 0 for i in range(rate)]
            self.file_data = b''.join(struct.pack('<h', value) * channels for value in values)

    def chunk(self):
        size = self.rate // 50 * self.channels * 2  # 20 ms, sample-aligned.
        out = bytearray()
        while len(out) < size:
            amount = min(size - len(out), len(self.file_data) - self.offset)
            out.extend(self.file_data[self.offset:self.offset + amount])
            self.offset = (self.offset + amount) % len(self.file_data)
        return bytes(out)
