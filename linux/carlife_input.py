"""Remote Touch gestures and native input mappings matching the Android bridge."""
import math
import struct
from carlife_wire import fields, int32


class Pad:
    def __init__(self, emit):
        self.emit = emit
        self.last_tap = self.down_at = self.last_double = None
        self.second = self.moved = self.mechanical = False
        self.travel = self.x = self.y = 0

    def down(self, now):
        self.second = self.last_tap is not None and 0 <= now - self.last_tap <= .45
        self.last_tap = None
        self.down_at = now
        self.travel = self.x = self.y = 0
        self.moved = self.mechanical = False

    def move(self, dx, dy):
        if self.down_at is not None:
            self.travel += math.hypot(dx, dy)
            self.moved |= self.travel >= 6
        if not self.moved:
            return
        self.x += dx
        self.y += dy
        horizontal = abs(self.x) >= abs(self.y)
        axis = self.x if horizontal else self.y
        steps = min(4, int(abs(axis) / 48))
        sign = 1 if axis > 0 else -1
        if horizontal:
            self.x -= sign * steps * 48
        else:
            self.y -= sign * steps * 48
        for _ in range(steps):
            self.emit('wheel' if self.second else 'knob',
                      a=sign if self.second else 0,
                      b=0 if self.second or not horizontal else sign,
                      c=0 if self.second or horizontal else sign)

    def up(self, now):
        tap = (self.down_at is not None and not self.moved and not self.mechanical
               and 0 <= now - self.down_at <= .5)
        if tap and self.second:
            self.emit('knob', a=1, b=0, c=0)
            self.last_double = now
        self.last_tap = now if tap and not self.second else None
        self.down_at = None
        self.second = self.moved = self.mechanical = False
        self.travel = self.x = self.y = 0

    def click(self, now):
        self.mechanical = True
        self.last_tap = None
        self.second = False
        if self.last_double is None or now - self.last_double > .15:
            self.emit('knob', a=1, b=0, c=0)


class Input:
    def __init__(self, emit):
        self.emit = emit
        self.pad = Pad(emit)
        self.touch_down = False

    def handle(self, message, payload, now):
        if message not in {0x68001, 0x68002, 0x68003, 0x68004, 0x68005,
                           0x68006, 0x68007, 0x68008, 0x1005a, 0x1005b,
                           0x1005c, 0x1005d, 0x1005e}:
            return False
        data = fields(payload)
        if message == 0x1005a:
            self.pad.down(now)
            self.emit('pad_down', timestamp=int32(data, 1))
        elif message == 0x1005b:
            dx, dy = int32(data, 2), int32(data, 3)
            self.emit('pad_move', dx=dx, dy=dy, timestamp=int32(data, 1))
            self.pad.move(dx, dy)
        elif message == 0x1005c:
            self.pad.up(now)
            self.emit('pad_up', timestamp=int32(data, 1))
        elif message == 0x1005d:
            value = data.get(1, [b''])[-1]
            if not isinstance(value, bytes) or len(value) != 4:
                raise ValueError('pinch float')
            self.emit('pinch', scale=struct.unpack('<f', value)[0])
        elif message == 0x1005e:
            self.emit('focus', timestamp=int32(data, 1))
        elif message == 0x68008:
            key = int32(data, 1)
            self.emit('hard_key', key=key)
            if key == 0x14:
                self.pad.click(now)
            elif key in (1, 0xe):
                self.emit('knob', a=2 if key == 1 else 4, b=0, c=0)
            elif key in (6, 7):
                self.emit('wheel', a=1 if key == 6 else -1, b=0, c=0)
            elif key in (0xf, 0x10):
                self.emit('media_key', a=5 if key == 0xf else 4, b=0, c=0)
        elif message == 0x68001:
            self.touch(int32(data, 1), int32(data, 2), int32(data, 3))
        elif message in (0x68002, 0x68003, 0x68004):
            self.touch({0x68002: 0, 0x68003: 1, 0x68004: 2}[message],
                       int32(data, 1), int32(data, 2))
        else:
            self.emit('point_gesture', message=message, x=int32(data, 1), y=int32(data, 2))
        return True

    def touch(self, action, x, y):
        if action == 0:
            self.touch_down = True
        elif not self.touch_down:
            return
        if action == 1:
            self.touch_down = False
        self.emit('touch', a=action, b=x, c=y)

    def reset(self):
        self.touch_down = False
        self.pad = Pad(self.emit)
        self.emit('reset', a=0, b=0, c=0)
