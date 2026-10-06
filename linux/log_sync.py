#!/usr/bin/env python3
"""Periodically sync named logs in a separate process, without touching USB."""
import argparse
import json
import os
import signal
import stat
import time


def sync_file(path, directory=False):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return 'not_created'
    try:
        kind = os.fstat(fd).st_mode
        if directory and not stat.S_ISDIR(kind):
            raise ValueError('log parent is not a directory')
        if not directory and not stat.S_ISREG(kind):
            raise ValueError('log is not a regular file')
        os.fsync(fd)
    finally:
        os.close(fd)
    return 'synced'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--interval', type=float, default=2)
    parser.add_argument('--duration', type=float, default=250)
    parser.add_argument('files', nargs='+')
    args = parser.parse_args()
    if not .05 <= args.interval <= 60 or not 0 < args.duration <= 600:
        parser.error('invalid sync interval/duration')
    def interrupt(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupt)
    print('Sync ready: independent periodic fsync, interval=' + str(args.interval), flush=True)
    deadline = time.monotonic() + args.duration
    # New file names and newly-created run directories need directory fsync
    # too; syncing data alone does not guarantee their names survive reset.
    from pathlib import Path
    pending_dirs = {str(parent) for path in args.files
                    for parent in list(Path(path).absolute().parents)[:3]}
    try:
        while time.monotonic() < deadline:
            results = {}
            for path in sorted(pending_dirs):
                try:
                    results[path] = sync_file(path, directory=True)
                    if results[path] == 'synced':
                        pending_dirs.remove(path)
                except (OSError, ValueError) as error:
                    results[path] = str(error)
            for path in args.files:
                try:
                    results[path] = sync_file(path)
                except (OSError, ValueError) as error:
                    results[path] = str(error)
            print(json.dumps(dict(event='log_sync', time=time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                                  results=results)), flush=True)
            # Do not accumulate missed cycles if an SD commit is slow.
            time.sleep(min(args.interval, max(0, deadline - time.monotonic())))
    except KeyboardInterrupt:
        print('Sync stopped', flush=True)
    print('Sync finished', flush=True)


if __name__ == '__main__':
    main()
