"""CarProjection-equivalent MD session, independent of USB and Android APIs."""
import queue
import time
from carlife_wire import CMD, VIDEO, MEDIA, TTS, TOUCH, blob, command, fields, int32, integer, md_info, parse, stream
from carlife_input import Input
from carlife_media import PcmSource, VideoSource


class Session:
    def __init__(self, log, *, fps=10, video=True, video_file=None, copy_video=False, audio='tts',
                 rate=48000, channels=1, gain=.03, audio_seconds=3, pcm_file=None):
        self.log = log
        self.fps = fps
        self.video_enabled, self.video_file = video, video_file
        self.copy_video = copy_video
        self.audio_mode, self.rate, self.channels = audio, rate, channels
        self.audio_seconds = audio_seconds
        self.pcm = PcmSource(rate, channels, gain, pcm_file) if audio != 'off' else None
        self.video = None
        self.size = None
        self.started = self.audio_started = self.audio_ended = False
        self.blocked = False
        self.next_audio = self.audio_until = 0
        self.next_video = 0
        self.pending_frame = None
        self.last_heartbeat_queued = -float('inf')
        self.last_rx = self.last_gps = None
        self.gps_count = 0
        self.input = Input(self.emit_input)
        self.last_stats = -float('inf')
        self.frames = self.audio_packets = self.audio_drops = 0

    def emit_input(self, kind, **data):
        # An observable native input outlet; a future Linux CarPlay adapter consumes it.
        self.log('input_event', kind=kind, **data)

    def send(self, bulk, packet, *, required=True):
        try:
            bulk.outgoing.put_nowait(packet)
            return True
        except queue.Full:
            if required:
                raise RuntimeError('command output budget exceeded; HU may not be reading')
            return False

    def receive(self, channel, frame, bulk, now):
        message, payload = parse(channel, frame)
        self.last_rx = now
        if message == 0x18010:
            self.gps_count = self.gps_count + 1 if self.last_gps is not None and now - self.last_gps <= 5 else 1
            self.last_gps = now
        try:
            if channel in (CMD, TOUCH) and self.input.handle(message, payload, now):
                return
            if channel != CMD:
                self.log('hu_stream', channel=channel, message=hex(message), bytes=len(payload))
                return
            if message == 0x18001:
                self.send(bulk, command(0x10002, integer(1, 1)))
                self.log('session_handshake', stage='protocol_match')
            elif message == 0x18003:
                fields(payload)  # Validate, but do not dump HU identifiers.
                self.send(bulk, command(0x10004, md_info()))
                features = [('FOCUS_UI', 1), ('AUDIO_TRANSMISSION_MODE', 0 if self.audio_mode != 'off' else 1),
                            ('MEDIA_SAMPLE_RATE', 0), ('CONTENT_ENCRYPTION', 0)]
                config = integer(1, len(features)) + b''.join(
                    blob(2, blob(1, key) + integer(2, value)) for key, value in features)
                self.send(bulk, command(0x10051, config))
                self.log('session_handshake', stage='md_info_and_features')
            elif message == 0x18007:
                values = fields(payload)
                width, height = int32(values, 1), int32(values, 2)
                hu_fps = int32(values, 3)
                if not (16 <= width <= 1920 and 16 <= height <= 1080 and width % 2 == height % 2 == 0
                        and 1 <= hu_fps <= 60):
                    raise ValueError('unsupported HU video size/rate')
                self.stop_media(bulk)
                self.size = (width, height)
                self.fps = min(self.fps, hu_fps)
                info = integer(1, width) + integer(2, height) + integer(3, self.fps)
                self.send(bulk, command(0x10008, info))
                self.send(bulk, command(0x1001b))
                self.log('session_handshake', stage='video_init_done', width=width, height=height, fps=self.fps)
            elif message == 0x18009:
                if self.size is None:
                    raise ValueError('VIDEO_START before VIDEO_INIT')
                if not self.started:
                    self.started = True
                    self.audio_started = self.audio_ended = False
                    self.last_heartbeat_queued = -float('inf')
                    self.next_video = now
                    if self.video_enabled and not self.blocked:
                        self.video = VideoSource(*self.size, self.fps, self.video_file, self.copy_video)
                    self.log('session_started', size=self.size, fps=self.fps, audio=self.audio_mode)
            elif message == 0x18027:
                fields(payload)
                for packet in (command(0x1001b), command(0x10018), command(0x1004b, integer(1, 1))):
                    self.send(bulk, packet)
                self.log('session_handshake', stage='foreground_screen_auth_result')
            elif message == 0x18052:
                values = fields(payload)
                features = {}
                for encoded in values.get(2, []):
                    if not isinstance(encoded, bytes):
                        raise ValueError('invalid feature record')
                    feature = fields(encoded)
                    key = feature.get(1, [b''])[-1]
                    if not isinstance(key, bytes):
                        raise ValueError('invalid feature key')
                    features[key.decode(errors='replace')] = int32(feature, 2)
                self.log('hu_features', values=features)
                if features.get('CONTENT_ENCRYPTION', 0) != 0:
                    self.blocked = True
                    self.stop_media(bulk)
                    self.log('unsupported_encryption', note='no encrypted media implementation; plaintext disabled')
            elif message == 0x18028:
                values = fields(payload)
                self.log('module_control', module=int32(values, 1), status=int32(values, 2))
            elif message in (0x1800a, 0x1800b):
                self.stop_media(bulk)
                if message == 0x1800b:
                    self.size = None
                self.log('video_paused_or_reset', message=hex(message))
            elif message == 0x1800c:
                fps = int32(fields(payload), 1)
                if not 1 <= fps <= 60:
                    raise ValueError('invalid frame rate')
                self.stop_media(bulk)
                self.fps = fps
                self.send(bulk, command(0x1000d, integer(1, fps)))
                self.log('video_rate_changed', fps=fps, note='waiting for VIDEO_START')
            elif message == 0x1800e:
                self.end_audio(bulk)
                self.log('hu_audio_pause')
            elif message == 0x1806b:
                values = fields(payload)
                key = values.get(1, [b''])[-1]
                self.log('hu_rsa_key', bytes=len(key) if isinstance(key, bytes) else 0,
                         note='diagnostic only, no encryption handshake')
            else:
                self.log('hu_command', message=hex(message), bytes=len(payload))
        except ValueError as error:
            # A complete outer frame was consumed: protobuf errors must not desync USB.
            self.log('session_parse_error', message=hex(message), error=str(error))
            if message == 0x18009:
                self.started = False

    def tick(self, bulk, now):
        if self.gps_count >= 3 and self.last_rx is not None and now - self.last_rx >= 8:
            raise RuntimeError('HU_SILENT_TIMEOUT after learned periodic GPS traffic')
        if not self.started or self.blocked:
            return
        timestamp = int(time.time() * 1000)
        last_write = getattr(bulk, 'last_video_write', 0)
        if now - last_write >= 1 and now - self.last_heartbeat_queued >= 1:
            if self.send(bulk, stream(VIDEO, 0x20002, timestamp_ms=timestamp), required=False):
                self.last_heartbeat_queued = now
                self.log('video_heartbeat_queued')
        if self.video and now >= self.next_video:
            if self.pending_frame is None:
                self.pending_frame = self.video.frame()
            if self.pending_frame and self.send(bulk, stream(VIDEO, 0x20001, self.pending_frame, timestamp), required=False):
                self.frames += 1
                self.pending_frame = None
                self.next_video = now + 1 / self.fps
        if self.audio_mode != 'off' and not self.audio_started and not self.audio_ended:
            channel, init = (TTS, 0x40001) if self.audio_mode == 'tts' else (MEDIA, 0x30001)
            config = integer(1, self.rate) + integer(2, self.channels) + integer(3, 16)
            self.send(bulk, stream(channel, init, config, timestamp))
            self.audio_started = True
            self.next_audio = now
            self.audio_until = now + self.audio_seconds
            self.log('audio_init', channel=channel, rate=self.rate, channels=self.channels,
                     format='PCM16LE', seconds=self.audio_seconds)
        if self.audio_started and now >= self.audio_until:
            self.end_audio(bulk)
        if self.audio_started and now >= self.next_audio:
            channel, data = (TTS, 0x40003) if self.audio_mode == 'tts' else (MEDIA, 0x30006)
            if self.send(bulk, stream(channel, data, self.pcm.chunk(), timestamp), required=False):
                self.audio_packets += 1
            else:
                self.audio_drops += 1
            self.next_audio = now + .02  # No catch-up burst after stalls.
        if now - self.last_stats >= 5:
            self.log('session_stats', video_frames=self.frames, audio_packets=self.audio_packets,
                     audio_drops=self.audio_drops, tx_pending=bulk.outgoing.qsize())
            self.last_stats = now

    def end_audio(self, bulk):
        if self.audio_started:
            channel, end = (TTS, 0x40002) if self.audio_mode == 'tts' else (MEDIA, 0x30002)
            self.send(bulk, stream(channel, end, timestamp_ms=int(time.time() * 1000)), required=False)
            self.log('audio_end', channel=channel)
        self.audio_started = False
        self.audio_ended = True

    def stop_media(self, bulk):
        if hasattr(bulk.outgoing, 'clear_media'):
            bulk.outgoing.clear_media()
        self.end_audio(bulk)
        self.started = False
        self.pending_frame = None
        if self.video:
            self.video.close()
            self.video = None

    def close(self):
        if self.video:
            self.video.close()
            self.video = None
        self.input.reset()
