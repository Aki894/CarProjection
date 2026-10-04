#!/usr/bin/env python3
"""Read-only, bounded-duration H3 kernel/counter recorder. No USB/network changes."""
import argparse
import json
import os
from pathlib import Path
import select
import subprocess
import time


def read(path):
    try:
        with open(path) as source:
            return source.read(32768)
    except OSError as error:
        return str(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--duration', type=int, default=180)
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('run with sudo to read kernel messages')
    if not 1 <= args.duration <= 600:
        parser.error('duration must be 1..600 seconds')
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    kernel = None
    with os.fdopen(fd, 'w') as output:
        def record(event, **data):
            output.write(json.dumps(dict(time=time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                                         event=event, **data), ensure_ascii=False) + '\n')
            output.flush()
            os.fsync(output.fileno())
        record('recorder_start', duration=args.duration, uname=list(os.uname()),
               cmdline=read('/proc/cmdline'), consoles=read('/proc/consoles'),
               modules=read('/proc/modules'))
        env = read('/boot/armbianEnv.txt')
        record('boot_options', lines=[line for line in env.splitlines()
               if line.split('=', 1)[0] in ('overlays', 'user_overlays', 'fdtfile',
                                            'console', 'verbosity', 'extraargs')])
        try:
            kernel = subprocess.Popen(['dmesg', '--follow', '--color=never'],
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, start_new_session=True)
            os.set_blocking(kernel.stdout.fileno(), False)
            print(f'Recorder ready: {args.output}', flush=True)
            deadline = time.monotonic() + args.duration
            next_snapshot = 0
            kernel_exit_reported = False
            while (now := time.monotonic()) < deadline:
                if output.tell() >= 16 * 1024 * 1024:
                    record('recorder_limit', bytes=output.tell())
                    break
                if now >= next_snapshot:
                    counters = {name: read('/proc/' + name) for name in
                                ('uptime', 'stat', 'meminfo', 'interrupts', 'softirqs', 'net/dev')}
                    controllers = {str(path): read(path) for pattern in
                                   ('/sys/class/udc/*/state', '/sys/class/udc/*/current_speed',
                                    '/sys/class/thermal/thermal_zone*/temp')
                                   for path in Path('/').glob(pattern.lstrip('/'))}
                    record('snapshot', counters=counters, controllers=controllers)
                    next_snapshot = now + 2
                if kernel.poll() is None:
                    ready, _, _ = select.select([kernel.stdout], [], [], 0.2)
                    if ready:
                        chunk = os.read(kernel.stdout.fileno(), 65536)
                        if chunk:
                            record('kernel', text=chunk.decode(errors='replace'))
                else:
                    if not kernel_exit_reported:
                        chunk = os.read(kernel.stdout.fileno(), 65536)
                        record('kernel_reader_exit', code=kernel.returncode,
                               text=chunk.decode(errors='replace'))
                        kernel_exit_reported = True
                    time.sleep(0.2)
            record('recorder_end')
        except KeyboardInterrupt:
            record('recorder_stopped')
        finally:
            if kernel:
                if kernel.poll() is None:
                    kernel.kill()
                try:
                    kernel.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    record('kernel_reader_stuck', pid=kernel.pid)
                kernel.stdout.close()


if __name__ == '__main__':
    main()
