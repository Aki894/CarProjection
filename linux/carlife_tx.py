"""Bounded USB output: commands/heartbeat cannot queue behind many pictures."""
from collections import deque
import queue
import struct
import threading
import time


class TxQueue:
    def __init__(self):
        self.groups = [deque() for _ in range(4)]
        self.bytes = 0
        self.condition = threading.Condition()

    @staticmethod
    def group(packet):
        channel = struct.unpack_from('>I', packet)[0] if len(packet) >= 8 else 1
        if channel in (1, 6):
            return 0
        if channel == 2:
            service = struct.unpack_from('>I', packet, 16)[0]
            return 1 if service == 0x20002 else 3
        return 2

    def put_nowait(self, packet):
        group = self.group(packet)
        with self.condition:
            if group == 1 and self.groups[1]:
                self.bytes -= len(self.groups[1].popleft())
            if len(self.groups[group]) >= (32, 1, 8, 2)[group] or self.bytes + len(packet) > 2 * 1024 * 1024:
                raise queue.Full
            self.groups[group].append(packet)
            self.bytes += len(packet)
            self.condition.notify()

    def get(self, timeout):
        deadline = time.monotonic() + timeout
        with self.condition:
            while not any(self.groups):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise queue.Empty
                self.condition.wait(remaining)
            for group in self.groups:
                if group:
                    packet = group.popleft()
                    self.bytes -= len(packet)
                    return packet

    def qsize(self):
        with self.condition:
            return sum(map(len, self.groups))

    def clear_media(self):
        with self.condition:
            for group in self.groups[1:]:
                self.bytes -= sum(map(len, group))
                group.clear()
