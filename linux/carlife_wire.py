"""Bounded CarLife wire helpers using the proto fields already in this repository."""
import struct

CMD, VIDEO, MEDIA, TTS, VR, TOUCH = range(1, 7)


def varint(value):
    value &= (1 << 64) - 1
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out) + bytes((value,))


def integer(field, value):
    return varint(field << 3) + varint(value)


def blob(field, value):
    if isinstance(value, str):
        value = value.encode()
    return varint((field << 3) | 2) + varint(len(value)) + value


def fields(data):
    if len(data) > 65535:
        raise ValueError('protobuf too large')
    cursor = 0
    def number():
        nonlocal cursor
        value = 0
        for shift in range(0, 70, 7):
            if cursor >= len(data):
                raise ValueError('truncated varint')
            byte = data[cursor]
            cursor += 1
            if shift == 63 and byte > 1:
                raise ValueError('varint overflow')
            value |= (byte & 127) << shift
            if not byte & 128:
                return value
        raise ValueError('varint overflow')
    result = {}
    while cursor < len(data):
        tag = number()
        field, wire = tag >> 3, tag & 7
        if not 0 < field < (1 << 29):
            raise ValueError('invalid protobuf field')
        if wire == 0:
            value = number()
        elif wire in (1, 2, 5):
            size = number() if wire == 2 else (8 if wire == 1 else 4)
            if size > len(data) - cursor:
                raise ValueError('truncated protobuf field')
            value = data[cursor:cursor + size]
            cursor += size
        else:
            raise ValueError('unsupported protobuf wire type')
        result.setdefault(field, []).append(value)
    return result


def int32(values, field):
    value = values.get(field, [None])[-1]
    if not isinstance(value, int):
        raise ValueError(f'missing integer field {field}')
    value &= 0xffffffff
    return value - (1 << 32) if value & (1 << 31) else value


def command(message, payload=b'', channel=CMD):
    if len(payload) > 65535 or channel not in (CMD, TOUCH):
        raise ValueError('command length/channel')
    inner = struct.pack('>HHI', len(payload), 0, message) + payload
    return struct.pack('>II', channel, len(inner)) + inner


def stream(channel, message, payload=b'', timestamp_ms=0):
    if channel not in (VIDEO, MEDIA, TTS, VR) or len(payload) > 1024 * 1024 - 12:
        raise ValueError('stream length/channel')
    inner = struct.pack('>III', len(payload), timestamp_ms & 0xffffffff, message) + payload
    return struct.pack('>II', channel, len(inner)) + inner


def parse(channel, frame):
    header = 8 if channel in (CMD, TOUCH) else 12
    if len(frame) < header:
        raise ValueError('truncated CarLife header')
    if header == 8:
        size, _, message = struct.unpack_from('>HHI', frame)
    else:
        size, _, message = struct.unpack_from('>III', frame)
    if size != len(frame) - header:
        raise ValueError('CarLife payload length mismatch')
    return message, frame[header:]


def md_info():
    # Keep the Android compatibility identity used by the known-working MD.
    values = {1: 'Android', 2: 'sdm845', 11: 'c4-miui-ota-bd47.bj',
              12: 'QKQ1.190828.002', 16: 'unknown', 19: '10', 20: '29'}
    return b''.join(blob(field, value) for field, value in values.items()) + integer(21, 29)
