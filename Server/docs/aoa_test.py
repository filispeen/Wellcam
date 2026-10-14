import argparse
import struct
import sys
import time

import usb.core
import usb.util

GOOGLE_VID = 0x18D1
ACCESSORY_PIDS = (0x2D00, 0x2D01)
STRINGS = ["Filispeen", "Wellcam", "Wellcam", "1", "https://example.invalid", "0"]
HEADER = struct.Struct(">4sBBHHHH")
PACKET = struct.Struct(">BBQI")


def find_accessory():
    for pid in ACCESSORY_PIDS:
        dev = usb.core.find(idVendor=GOOGLE_VID, idProduct=pid)
        if dev is not None:
            return dev
    return None


def switch_to_accessory(vid, pid):
    dev = usb.core.find(idVendor=vid, idProduct=pid)
    if dev is None:
        raise SystemExit("phone %04x:%04x not found" % (vid, pid))
    raw = dev.ctrl_transfer(0xC0, 51, 0, 0, 2)
    protocol = struct.unpack("<H", bytes(raw))[0]
    print("AOA protocol version:", protocol)
    if protocol < 1:
        raise SystemExit("AOA not supported")
    for index, text in enumerate(STRINGS):
        dev.ctrl_transfer(0x40, 52, 0, index, text.encode("utf-8") + b"\0")
    dev.ctrl_transfer(0x40, 53, 0, 0, None)
    usb.util.dispose_resources(dev)


def wait_accessory(seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        dev = find_accessory()
        if dev is not None:
            return dev
        time.sleep(0.2)
    raise SystemExit("accessory device did not appear")


def open_bulk_in(dev):
    try:
        dev.set_configuration()
    except usb.core.USBError:
        pass
    cfg = dev.get_active_configuration()
    for intf in cfg:
        ep_in = None
        ep_out = None
        for ep in intf:
            kind = usb.util.endpoint_type(ep.bmAttributes)
            if kind != usb.util.ENDPOINT_TYPE_BULK:
                continue
            if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
                ep_in = ep
            else:
                ep_out = ep
        if ep_in is not None and ep_out is not None:
            usb.util.claim_interface(dev, intf.bInterfaceNumber)
            return ep_in, ep_out
    raise SystemExit("bulk endpoints not found")


class StreamReader:
    def __init__(self, endpoint):
        self.endpoint = endpoint
        self.buffer = bytearray()

    def fill(self, count):
        while len(self.buffer) < count:
            try:
                chunk = self.endpoint.read(16384, timeout=1000)
            except usb.core.USBTimeoutError:
                continue
            self.buffer += bytes(chunk)

    def take(self, count):
        self.fill(count)
        out = bytes(self.buffer[:count])
        del self.buffer[:count]
        return out

    def sync(self):
        skipped = 0
        while True:
            self.fill(HEADER.size)
            index = self.buffer.find(b"WCAM\x02")
            if index < 0:
                drop = len(self.buffer) - 4
                skipped += drop
                del self.buffer[:drop]
                self.fill(HEADER.size + 1)
                continue
            skipped += index
            del self.buffer[:index]
            self.fill(HEADER.size)
            if self.buffer[5] in (1, 2):
                return skipped
            skipped += 1
            del self.buffer[:1]


def run(reader):
    skipped = reader.sync()
    if skipped:
        print("skipped %d stale bytes" % skipped)
    magic, version, codec, width, height, fps, rotation = HEADER.unpack(reader.take(HEADER.size))
    if magic != b"WCAM":
        raise SystemExit("bad magic: %r" % magic)
    print("version=%d codec=%d %dx%d fps=%d rotation=%d" % (version, codec, width, height, fps, rotation))
    frames = 0
    keys = 0
    total = 0
    mark = time.time()
    while True:
        kind, flags, pts, size = PACKET.unpack(reader.take(PACKET.size))
        reader.take(size)
        total += size
        if kind == 1:
            frames += 1
            if flags & 1:
                keys += 1
        else:
            print("config packet, %d bytes" % size)
        now = time.time()
        if now - mark >= 1.0:
            print("frames=%d keyframes=%d mbps=%.2f pts=%d" % (frames, keys, total * 8 / (now - mark) / 1e6, pts))
            frames = 0
            keys = 0
            total = 0
            mark = now


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--vid", type=lambda v: int(v, 16))
    parser.add_argument("--pid", type=lambda v: int(v, 16))
    args = parser.parse_args()
    dev = find_accessory()
    if dev is None:
        if args.vid is None or args.pid is None:
            raise SystemExit("phone is not in accessory mode, pass --vid and --pid in hex")
        switch_to_accessory(args.vid, args.pid)
        dev = wait_accessory(10)
    print("accessory %04x:%04x" % (dev.idVendor, dev.idProduct))
    ep_in, ep_out = open_bulk_in(dev)
    ep_out.write(b"HELO", timeout=2000)
    run(StreamReader(ep_in))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
