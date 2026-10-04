#!/usr/bin/env python3
"""Experimental Linux MD gadget: AOA 1.0 negotiation and first CarLife commands.

No media, input, authentication, heartbeat or CarPlay receiver is implemented.
Only run over Wi-Fi/Ethernet SSH: temporarily replaces USB serial if requested.
"""
import argparse
import errno
import fcntl
import json
import os
from pathlib import Path
import queue
import select
import signal
import struct
import subprocess
import tempfile
import threading
import time

GADGET = Path('/sys/kernel/config/usb_gadget/carlife-probe')
FFS = Path('/run/carlife-ffs')
MAX_FRAME = 1024 * 1024
LOG_FILE = None


def descriptors():
    interface = bytes((9, 4, 0, 0, 2, 255, 255, 0, 1))
    def speed(size):
        return interface + b''.join(struct.pack('<BBBBHB', 7, 5, addr, 2, size, 0)
                                    for addr in (1, 0x82))
    body = struct.pack('<II', 3, 3) + speed(64) + speed(512)
    # Forward vendor requests addressed to device, including before configuration.
    return struct.pack('<III', 3, 12 + len(body), 1 | 2 | 64 | 128) + body


def strings():
    body = struct.pack('<H', 0x409) + b'CarLife AOA probe\0'
    return struct.pack('<IIII', 2, 16 + len(body), 1, 1) + body


def varint(value):
    output = bytearray()
    while value >= 128:
        output.append((value & 127) | 128)
        value >>= 7
    return bytes(output) + bytes((value,))


def md_info():
    # Original Android prototype identity fields; isolated compatibility profile.
    fields = {1: 'Android', 2: 'sun8i-h3', 11: 'carlife-h3', 12: 'linux-probe',
              16: 'linux-probe', 19: '10', 20: '29'}
    output = b''
    for field, value in fields.items():
        data = value.encode()
        output += varint(field * 8 + 2) + varint(len(data)) + data
    return output + varint(21 * 8) + varint(29)


def command(message, payload=b''):
    inner = struct.pack('>HHI', len(payload), 0, message) + payload
    return struct.pack('>II', 1, len(inner)) + inner


class Frames:
    """USB reads are chunks, not CarLife message boundaries."""
    def __init__(self):
        self.buffer = bytearray()

    def feed(self, chunk):
        self.buffer.extend(chunk)
        result = []
        while len(self.buffer) >= 8:
            channel, size = struct.unpack_from('>II', self.buffer)
            if channel not in range(1, 7) or size > MAX_FRAME or size < 8:
                raise ValueError(f'invalid CarLife frame channel={channel} size={size}')
            if len(self.buffer) < 8 + size:
                break
            frame = bytes(self.buffer[8:8 + size])
            del self.buffer[:8 + size]
            result.append((channel, frame))
        return result


def reply(channel, frame):
    if channel != 1:
        return None
    length, _, message = struct.unpack_from('>HHI', frame)
    if length != len(frame) - 8:
        raise ValueError('CarLife command length mismatch')
    if message == 0x18001:
        # Same match-status response as Android reference. Compatibility is unverified.
        return command(0x10002, b'\x08\x01')
    if message == 0x18003:
        return command(0x10004, md_info())
    return None


def log(event, **values):
    line = json.dumps(dict(time=time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                           event=event, **values), ensure_ascii=False)
    if LOG_FILE:
        LOG_FILE.write(line + '\n')
        LOG_FILE.flush()
        os.fsync(LOG_FILE.fileno())
    try:
        print(line, flush=True)
    except OSError as error:
        if error.errno not in (errno.EPIPE, errno.EIO):
            raise
        # Direct file logging continues when the SSH stdout pipe/terminal disappears.


def run(*args, timeout=15, check=True):
    log('system_command', command=list(args))
    # subprocess.run kills then waits without a deadline on timeout. A modprobe
    # stuck in kernel D state can therefore hang the Python caller indefinitely.
    # Do not let a stuck child inherit the logging pipe and keep tee alive either.
    with tempfile.TemporaryFile() as output:
        process = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except BaseException as error:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                log('system_command_stuck', pid=process.pid,
                    note='may be in uninterruptible kernel sleep; inspect ps and /proc/PID/stack')
            if isinstance(error, subprocess.TimeoutExpired):
                raise TimeoutError(f'command timed out: {args}; child PID={process.pid}') from error
            raise
        output.seek(0)
        detail = output.read(4096).decode(errors='replace').strip()
        if detail:
            log('system_command_output', output=detail)
        if check and code:
            raise subprocess.CalledProcessError(code, args)
        return code


def serial_users():
    """Report only process names/PIDs, not command lines or other FD targets."""
    users = []
    deadline = time.monotonic() + 2
    for process in Path('/proc').iterdir():
        if time.monotonic() > deadline or len(users) >= 8:
            break
        if not process.name.isdigit():
            continue
        try:
            for fd in (process / 'fd').iterdir():
                if time.monotonic() > deadline:
                    break
                try:
                    target = os.readlink(fd)
                except OSError:
                    continue
                if target.startswith('/dev/ttyGS0'):
                    users.append(dict(pid=int(process.name), name=(process / 'comm').read_text().strip()))
                    break
        except OSError:
            continue
    return users


def put(path, value):
    path.write_text(value + '\n')


class Gadget:
    def __init__(self, release_serial):
        self.release_serial = release_serial
        self.serial_released = False
        self.created = False
        self.mounted = False
        self.fds = []
        self.udc = None
        self.lock = None
        self.stage = 'not_started'
        self.getty_was_active = False
        self.bulk = None

    def progress(self, stage):
        self.stage = stage
        log('startup_stage', stage=stage)

    def open(self):
        self.progress('acquire_lock')
        self.lock = open('/run/carlife-gadget.lock', 'w')
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        run('modprobe', 'libcomposite')
        run('modprobe', 'usb_f_fs')
        if not os.path.ismount('/sys/kernel/config'):
            raise RuntimeError('configfs is not mounted at /sys/kernel/config')
        if GADGET.exists() or os.path.ismount(FFS):
            raise RuntimeError('probe paths already exist; refusing to modify an existing gadget')
        controllers = list(Path('/sys/class/udc').iterdir())
        if len(controllers) != 1:
            raise RuntimeError(f'expected one UDC, found {len(controllers)}')
        self.udc = controllers[0].name
        log('udc_found', udc=self.udc)
        # Do not unbind other configfs gadgets, even with --release-g-serial.
        for path in GADGET.parent.glob('*/UDC'):
            if path.read_text().strip():
                raise RuntimeError(f'UDC owned by {path.parent}; refusing to unbind it')
        if Path('/sys/module/g_serial').exists():
            if not self.release_serial:
                raise RuntimeError('g_serial occupies UDC; use --release-g-serial over network SSH')
            if 'ttyGS0' in Path('/proc/consoles').read_text().split():
                raise RuntimeError('ttyGS0 is an active kernel console; disable USB console at boot before probing')
            self.progress('stop_usb_serial_getty')
            self.getty_was_active = run('systemctl', 'is-active', '--quiet',
                                       'serial-getty@ttyGS0.service', timeout=5, check=False) == 0
            if self.getty_was_active:
                run('systemctl', 'stop', 'serial-getty@ttyGS0.service', timeout=5)
            users = serial_users()
            if users:
                log('usb_serial_busy', processes=users)
                raise RuntimeError('ttyGS0 is open; close the listed USB serial sessions first')
            self.progress('release_usb_serial')
            run('modprobe', '-r', 'g_serial')
            self.serial_released = True
            log('usb_serial_released')
        self.progress('create_gadget')
        GADGET.mkdir()
        self.created = True
        # Lab-only initial Android-like identity; HU recognition needs physical testing.
        put(GADGET / 'idVendor', '0x18d1')
        put(GADGET / 'idProduct', '0x4ee7')
        put(GADGET / 'bcdUSB', '0x0200')
        text = GADGET / 'strings/0x409'
        text.mkdir()
        for key, value in dict(manufacturer='CarLife Linux experiment',
                               product='H3 MD probe', serialnumber='carlife-h3-probe').items():
            put(text / key, value)
        config = GADGET / 'configs/c.1'
        config.mkdir()
        put(config / 'MaxPower', '250')
        (GADGET / 'functions/ffs.carlife').mkdir()
        FFS.mkdir(exist_ok=True)
        run('mount', '-t', 'functionfs', 'carlife', str(FFS))
        self.mounted = True
        self.progress('open_ep0')
        self.ep0 = os.open(FFS / 'ep0', os.O_RDWR)
        self.fds.append(self.ep0)
        self.progress('write_descriptors')
        os.write(self.ep0, descriptors())
        self.progress('write_strings')
        os.write(self.ep0, strings())
        self.progress('open_bulk_endpoints')
        self.rx = os.open(FFS / 'ep1', os.O_RDWR | os.O_NONBLOCK)
        self.tx = os.open(FFS / 'ep2', os.O_RDWR | os.O_NONBLOCK)
        self.fds.extend((self.rx, self.tx))
        (config / 'ffs.carlife').symlink_to(GADGET / 'functions/ffs.carlife')
        self.progress('bind_udc')
        put(GADGET / 'UDC', self.udc)
        log('gadget_bound', udc=self.udc, mode='initial')

    def accessory(self):
        log('usb_transition', stage='unbind_initial')
        put(GADGET / 'UDC', '')
        time.sleep(0.5)
        log('usb_transition', stage='set_accessory_identity')
        put(GADGET / 'idProduct', '0x2d00')
        log('usb_transition', stage='bind_accessory')
        put(GADGET / 'UDC', self.udc)
        log('accessory_reenumeration', vid='18d1', pid='2d00')

    def close(self):
        errors = []
        def cleanup(call):
            try:
                call()
            except TimeoutError:
                raise  # Whole-cleanup alarm: stop rather than trying more USB operations.
            except Exception as error:
                errors.append(str(error))
        if self.bulk:
            self.bulk.stop.set()
            self.bulk.enabled.set()
        if self.created:
            log('cleanup_stage', stage='unbind_udc')
            cleanup(lambda: put(GADGET / 'UDC', ''))
            if errors:
                log('cleanup_error', error=errors[-1], note='leaving gadget and descriptors intact; do not restart probe')
                return False
        if self.bulk and not self.bulk.join():
            log('cleanup_error', error='bulk worker still in kernel IO after unbind; leaving descriptors intact; do not restart probe')
            return False
        log('cleanup_stage', stage='close_endpoints')
        for fd in reversed(self.fds):
            cleanup(lambda fd=fd: os.close(fd))
        if self.created:
            link = GADGET / 'configs/c.1/ffs.carlife'
            if link.is_symlink():
                cleanup(link.unlink)
        if self.mounted:
            cleanup(lambda: run('umount', str(FFS)))
        if self.created:
            for path in ('functions/ffs.carlife', 'configs/c.1', 'strings/0x409'):
                item = GADGET / path
                if item.exists():
                    cleanup(item.rmdir)
            cleanup(GADGET.rmdir)
        if self.serial_released:
            cleanup(lambda: run('modprobe', 'g_serial'))
            log('usb_serial_restore', success=not errors)
        if self.getty_was_active:
            cleanup(lambda: run('systemctl', 'start', 'serial-getty@ttyGS0.service', timeout=5))
        if self.lock:
            self.lock.close()
        for error in errors:
            log('cleanup_error', error=error)
        return not errors


class BulkIO:
    """Synchronous FunctionFS IO may block despite O_NONBLOCK.

    Keep it off the ep0/control loop. Unbind before joining/closing endpoints.
    This isolates userspace waits; it cannot repair a UDC/kernel lockup.
    """
    def __init__(self, rx, tx):
        self.rx, self.tx = rx, tx
        self.stop = threading.Event()
        self.enabled = threading.Event()
        self.received = queue.Queue(maxsize=16)
        self.outgoing = queue.Queue(maxsize=16)
        self.errors = queue.Queue(maxsize=2)
        self.threads = [threading.Thread(target=self.reader, daemon=True, name='carlife-rx'),
                        threading.Thread(target=self.writer, daemon=True, name='carlife-tx')]
        for thread in self.threads:
            thread.start()

    def reader(self):
        while not self.stop.is_set():
            if not self.enabled.wait(0.1) or self.stop.is_set():
                continue
            try:
                data = os.read(self.rx, 16384)
                if data:
                    while not self.stop.is_set():
                        try:
                            self.received.put(data, timeout=0.1)
                            break
                        except queue.Full:
                            pass
                else:
                    self.stop.wait(0.1)
            except OSError as error:
                if error.errno in (errno.EAGAIN, errno.ESHUTDOWN, errno.ENODEV, errno.EINTR):
                    self.stop.wait(0.1)
                else:
                    self.errors.put(error)
                    return

    def writer(self):
        while not self.stop.is_set():
            try:
                data = self.outgoing.get(timeout=0.1)
            except queue.Empty:
                continue
            while data and not self.stop.is_set():
                if not self.enabled.wait(0.1) or self.stop.is_set():
                    continue
                try:
                    count = os.write(self.tx, data)
                    if count <= 0:
                        raise OSError(errno.EIO, 'zero-length bulk write')
                    data = data[count:]
                    log('carlife_tx', bytes=count)
                except OSError as error:
                    if error.errno in (errno.EAGAIN, errno.EINTR):
                        self.stop.wait(0.1)
                    else:
                        self.errors.put(error)
                        return

    def join(self):
        deadline = time.monotonic() + 2
        for thread in self.threads:
            thread.join(timeout=max(0, deadline - time.monotonic()))
        return not any(thread.is_alive() for thread in self.threads)


def serve(gadget, duration, phase='bind'):
    deadline = time.monotonic() + duration
    mode = 'initial'
    enabled = False
    accessory_enabled = False
    suspended = False
    frames = Frames()
    seen = set()
    rx_bytes = 0
    rx_frames = 0
    last_heartbeat = -float('inf')
    event_names = {0: 'bind', 1: 'unbind', 2: 'enable', 3: 'disable',
                   4: 'setup', 5: 'suspend', 6: 'resume'}
    while (now := time.monotonic()) < deadline:
        if now - last_heartbeat >= 5:
            log('probe_alive', phase=phase, mode=mode, enabled=enabled,
                rx_bytes=rx_bytes, rx_frames=rx_frames)
            last_heartbeat = now
        ready, _, _ = select.select([gadget.ep0], [], [], 0.1)
        if ready:
            data = os.read(gadget.ep0, 12 * 16)
            if len(data) % 12:
                raise RuntimeError('invalid FunctionFS event length')
            for offset in range(0, len(data), 12):
                event = data[offset:offset + 12]
                kind = event[8]
                log('functionfs_event', type=kind, name=event_names.get(kind, 'unknown'), mode=mode)
                if kind in (1, 3, 5):
                    log('usb_session_state', state=event_names[kind], rx_bytes=rx_bytes,
                        rx_frames=rx_frames, buffered_bytes=len(frames.buffer))
                if kind == 2:
                    enabled = True
                    suspended = False
                    if mode == 'accessory':
                        accessory_enabled = True
                elif kind in (1, 3):
                    enabled = False
                    frames = Frames()
                    if gadget.bulk:
                        gadget.bulk.enabled.clear()
                    if mode == 'accessory' and accessory_enabled:
                        log('host_session_ended', note='probe exits after accessory disable; reconnect starts a new run')
                        return
                elif kind == 5:
                    suspended = True
                    if gadget.bulk:
                        gadget.bulk.enabled.clear()
                elif kind == 6:
                    suspended = False
                elif kind == 4:
                    request_type, request, value, index, length = struct.unpack('<BBHHH', event[:8])
                    log('control_request', request_type=request_type, request=request,
                        index=index, length=length)
                    if phase != 'bind' and mode == 'initial' and request_type == 0xc0 and request == 51 and value == 0 and index == 0 and length == 2:
                        os.write(gadget.ep0, b'\x01\x00')
                        log('aoa_protocol', version=1)
                    elif phase != 'bind' and mode == 'initial' and request_type == 0x40 and request == 52 and value == 0 and index < 6 and 0 < length <= 256:
                        value_bytes = os.read(gadget.ep0, length)
                        seen.add(index)
                        log('aoa_identity_string', index=index, bytes=len(value_bytes))
                    elif phase != 'bind' and mode == 'initial' and request_type == 0x40 and request == 53 and value == 0 and index == 0 and length == 0:
                        os.read(gadget.ep0, 0)  # Complete the control request before disconnect.
                        log('aoa_start', received_string_indices=sorted(seen))
                        enabled = False
                        gadget.accessory()
                        mode = 'accessory'
                    else:
                        # Opposite-direction operation stalls unsupported requests.
                        try:
                            if request_type & 0x80:
                                os.read(gadget.ep0, 0)
                            else:
                                os.write(gadget.ep0, b'')
                        except OSError as error:
                            if error.errno not in (errno.EL2HLT, errno.EPIPE):
                                raise
        if phase != 'session' or mode != 'accessory' or not enabled or suspended:
            continue
        if gadget.bulk is None:
            log('bulk_workers_start')
            gadget.bulk = BulkIO(gadget.rx, gadget.tx)
        gadget.bulk.enabled.set()
        if not gadget.bulk.errors.empty():
            raise gadget.bulk.errors.get_nowait()
        try:
            chunk = gadget.bulk.received.get_nowait()
        except queue.Empty:
            chunk = b''
        if chunk:
            rx_bytes += len(chunk)
            if rx_bytes == len(chunk):
                log('bulk_first_rx', bytes=len(chunk))
        for channel, frame in frames.feed(chunk):
            rx_frames += 1
            message = struct.unpack_from('>I', frame, 4)[0] if channel == 1 else None
            log('carlife_rx', channel=channel, bytes=len(frame), message=message)
            response = reply(channel, frame)
            if response:
                try:
                    gadget.bulk.outgoing.put_nowait(response)
                except queue.Full:
                    raise RuntimeError('TX queue exceeded limit')
                log('carlife_tx_queued', bytes=len(response))
            elif message == 0x18007:
                log('milestone_video_init', note='HU reached video negotiation; media not implemented')
    log('probe_timeout', seconds=duration, mode=mode, rx_bytes=rx_bytes,
        rx_frames=rx_frames, buffered_bytes=len(frames.buffer))


def main():
    global LOG_FILE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-g-serial', action='store_true')
    parser.add_argument('--duration', type=int, default=120)
    parser.add_argument('--phase', choices=('bind', 'aoa', 'session'), default='bind',
                        help='bind: enumeration only (default); aoa: switch only; session: experimental bulk IO')
    parser.add_argument('--log-file', help='append and fsync JSON logs directly to this file')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('run with sudo, using network SSH')
    if not 1 <= args.duration <= 600:
        parser.error('duration must be between 1 and 600 seconds')
    if args.log_file:
        fd = os.open(args.log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
        LOG_FILE = os.fdopen(fd, 'a')
    def interrupt(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGHUP, signal.SIG_IGN if LOG_FILE else interrupt)
    gadget = Gadget(args.release_g_serial)
    log('probe_start', duration=args.duration, phase=args.phase, release_g_serial=args.release_g_serial)
    def startup_timeout(*_):
        raise TimeoutError(f'USB probe timed out at {gadget.stage}')
    signal.signal(signal.SIGALRM, startup_timeout)
    status = 0
    try:
        signal.setitimer(signal.ITIMER_REAL, 30)
        gadget.open()
        signal.setitimer(signal.ITIMER_REAL, 0)
        log('waiting_for_host', note='connect OTG data port to CarLife HU or a test USB host')
        gadget.stage = 'serve_control_loop'
        signal.setitimer(signal.ITIMER_REAL, args.duration + 5)
        serve(gadget, args.duration, args.phase)
    except KeyboardInterrupt:
        log('probe_stopped')
    except Exception as error:
        log('probe_error', error=str(error))
        status = 1
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        gadget.stage = 'cleanup'
        signal.setitimer(signal.ITIMER_REAL, 20)
        try:
            if not gadget.close():
                status = 1
        except Exception as error:
            log('cleanup_error', error=str(error), note='do not restart until old gadget and processes are checked')
            status = 1
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            if LOG_FILE:
                LOG_FILE.close()
                LOG_FILE = None
    return status


if __name__ == '__main__':
    raise SystemExit(main())
