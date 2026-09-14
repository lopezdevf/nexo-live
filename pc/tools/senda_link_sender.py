# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 Senda Studio contributors
"""
Emisor de prueba del protocolo Senda Link: hace de «Senda Studio PC» sin capturar la pantalla.

Envía al móvil el vídeo H.264 de un MP4 (en bucle, al ritmo real) y un tono de audio, responde a
las peticiones de hora y de fotograma clave, y muestra el retraso de ida y vuelta. Sirve para
probar la fuente PC de la app sin compilar la aplicación de Windows.

    python senda_link_sender.py --discover
    python senda_link_sender.py 192.168.1.10 --code 4821 --video grabacion.mp4
"""

import argparse
import math
import socket
import struct
import threading
import time

MAGIC = b"SNL1"
DISCOVERY_PORT = 9750
VIDEO_FORMAT, VIDEO_FRAME, AUDIO_PCM, TIME_REPLY, LATENCY_REPORT = 0x01, 0x02, 0x03, 0x05, 0x07
KEYFRAME_REQUEST, TIME_REQUEST, FRAME_SHOWN = 0x81, 0x82, 0x83


def now_us():
    return time.perf_counter_ns() // 1000


# ---- MP4 → unidades de acceso Annex-B ---------------------------------------------------------

def boxes(data, start, end):
    while start < end:
        size, kind = struct.unpack(">I4s", data[start:start + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[start + 8:start + 16])[0]
            header = 16
        elif size == 0:
            size = end - start
        yield kind.decode("latin1"), start + header, start + size
        start += size


def find(data, start, end, path):
    for kind, s, e in boxes(data, start, end):
        if kind == path[0]:
            if len(path) == 1:
                yield s, e
            else:
                yield from find(data, s, e, path[1:])


def load_h264(path):
    data = open(path, "rb").read()
    for trak_s, trak_e in find(data, 0, len(data), ["moov", "trak"]):
        hdlr = next(find(data, trak_s, trak_e, ["mdia", "hdlr"]))
        if data[hdlr[0] + 8:hdlr[0] + 12] != b"vide":
            continue
        stbl_s, stbl_e = next(find(data, trak_s, trak_e, ["mdia", "minf", "stbl"]))
        mdhd = next(find(data, trak_s, trak_e, ["mdia", "mdhd"]))
        version = data[mdhd[0]]
        timescale, duration = (struct.unpack(">II", data[mdhd[0] + 12:mdhd[0] + 20]) if version == 0
                               else (struct.unpack(">I", data[mdhd[0] + 20:mdhd[0] + 24])[0],
                                     struct.unpack(">Q", data[mdhd[0] + 24:mdhd[0] + 32])[0]))
        stsd_s, stsd_e = next(find(data, stbl_s, stbl_e, ["stsd"]))
        entry = data[stsd_s + 8:stsd_e]
        width, height = struct.unpack(">HH", entry[8 + 24:8 + 28])
        avcc = entry[entry.find(b"avcC") + 4:]
        length_size = (avcc[4] & 3) + 1
        pos, params = 6, []
        for _ in range(avcc[5] & 0x1F):
            n = struct.unpack(">H", avcc[pos:pos + 2])[0]
            params.append(avcc[pos + 2:pos + 2 + n])
            pos += 2 + n
        for _ in range(avcc[pos]):
            n = struct.unpack(">H", avcc[pos + 1:pos + 3])[0]
            params.append(avcc[pos + 3:pos + 3 + n])
            pos += 2 + n
        header = b"".join(b"\x00\x00\x00\x01" + p for p in params)

        s, _ = next(find(data, stbl_s, stbl_e, ["stsz"]))
        fixed, count = struct.unpack(">II", data[s + 4:s + 12])
        sizes = [fixed] * count if fixed else list(struct.unpack(">%dI" % count, data[s + 12:s + 12 + 4 * count]))
        offsets = []
        for kind in ("stco", "co64"):
            for s, _ in find(data, stbl_s, stbl_e, [kind]):
                n = struct.unpack(">I", data[s + 4:s + 8])[0]
                fmt = ">%dI" % n if kind == "stco" else ">%dQ" % n
                offsets = list(struct.unpack(fmt, data[s + 8:s + 8 + (4 if kind == "stco" else 8) * n]))
        s, _ = next(find(data, stbl_s, stbl_e, ["stsc"]))
        n = struct.unpack(">I", data[s + 4:s + 8])[0]
        stsc = [struct.unpack(">III", data[s + 8 + 12 * i:s + 20 + 12 * i]) for i in range(n)]
        sync = set()
        for s, _ in find(data, stbl_s, stbl_e, ["stss"]):
            n = struct.unpack(">I", data[s + 4:s + 8])[0]
            sync = set(struct.unpack(">%dI" % n, data[s + 8:s + 8 + 4 * n]))

        frames, sample = [], 0
        for chunk_index, offset in enumerate(offsets, start=1):
            per_chunk = next(spc for first, spc, _ in reversed(stsc) if first <= chunk_index)
            for _ in range(per_chunk):
                if sample >= len(sizes):
                    break
                raw = data[offset:offset + sizes[sample]]
                offset += sizes[sample]
                out, p = bytearray(), 0
                while p + length_size <= len(raw):
                    nal_len = int.from_bytes(raw[p:p + length_size], "big")
                    out += b"\x00\x00\x00\x01" + raw[p + length_size:p + length_size + nal_len]
                    p += length_size + nal_len
                key = (sample + 1) in sync if sync else True
                frames.append((key, (header + bytes(out)) if key else bytes(out)))
                sample += 1
        fps = round(len(frames) / (duration / timescale))
        return width, height, fps, frames
    raise SystemExit("El MP4 no tiene pista de vídeo H.264")


# ---- Protocolo ----------------------------------------------------------------------------------

def discover(timeout=1.5):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(0.3)
    sock.sendto(b"SNL1?", ("255.255.255.255", DISCOVERY_PORT))
    found, end = [], time.time() + timeout
    while time.time() < end:
        try:
            data, addr = sock.recvfrom(1024)
        except socket.timeout:
            continue
        if not data.startswith(b"SNL1!"):
            continue
        port = struct.unpack(">H", data[5:7])[0]
        n = struct.unpack(">H", data[7:9])[0]
        device = data[9:9 + n].decode()
        m = struct.unpack(">H", data[9 + n:11 + n])[0]
        source = data[11 + n:11 + n + m].decode()
        found.append((addr[0], port, device, source))
    return found


class Sender:
    def __init__(self, host, port, code, name):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.lock = threading.Lock()
        self.keyframe_wanted = threading.Event()
        self.latencies = []  # ms: captura → mostrado en el móvil → aviso de vuelta
        self.sent_bytes = 0
        self.send_wait = 0.0  # segundos bloqueado enviando: la red no da abasto
        name_bytes = name.encode()
        self.sock.sendall(MAGIC + struct.pack(">HH", code, len(name_bytes)) + name_bytes)
        reply = self._recv(5)
        if reply[:4] != MAGIC:
            raise SystemExit("El móvil no respondió con Senda Link")
        n = struct.unpack(">H", self._recv(2))[0]
        device = self._recv(n).decode()
        if reply[4] != 0:
            raise SystemExit("«%s» rechazó la conexión (resultado %d: código incorrecto?)" % (device, reply[4]))
        print("Conectado a «%s»" % device)
        self.sock.settimeout(None)
        threading.Thread(target=self._read, daemon=True).start()

    def _recv(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("conexión cerrada")
            buf += chunk
        return buf

    def _read(self):
        try:
            while True:
                kind, length = struct.unpack(">BI", self._recv(5))
                payload = self._recv(length)
                if kind == TIME_REQUEST:
                    self.send(TIME_REPLY, payload[:8] + struct.pack(">Q", now_us()))
                elif kind == KEYFRAME_REQUEST:
                    self.keyframe_wanted.set()
                elif kind == FRAME_SHOWN and length >= 8:
                    capture = struct.unpack(">Q", payload[:8])[0]
                    self.latencies.append((now_us() - capture) / 1000)
        except (ConnectionError, OSError):
            print("El móvil cerró la conexión")

    def send(self, kind, payload):
        data = struct.pack(">BI", kind, len(payload)) + payload
        with self.lock:
            start = time.perf_counter()
            self.sock.sendall(data)
            self.send_wait += time.perf_counter() - start
            self.sent_bytes += len(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("host", nargs="?")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--code", type=int, default=0)
    parser.add_argument("--video", help="MP4 con vídeo H.264")
    parser.add_argument("--seconds", type=float, default=30)
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--no-audio", action="store_true", help="no envía el tono de audio")
    args = parser.parse_args()

    if args.discover or not args.host:
        for ip, port, device, source in discover():
            print("%s:%d  %s · %s" % (ip, port, device, source))
        return

    width, height, fps, frames = load_h264(args.video)
    keyframes = [i for i, (key, _) in enumerate(frames) if key]
    print("Vídeo %dx%d a %d fps, %d fotogramas" % (width, height, fps, len(frames)))
    sender = Sender(args.host, args.port, args.code, socket.gethostname())
    sender.send(VIDEO_FORMAT, struct.pack(">HHB", width, height, fps))

    def audio():
        rate, chunk, phase = 48000, 480, 0.0
        start = time.perf_counter()
        sent = 0
        while time.perf_counter() - start < args.seconds:
            pcm = bytearray()
            for _ in range(chunk):
                v = int(6000 * math.sin(phase))
                phase += 2 * math.pi * 440 / rate
                pcm += struct.pack("<hh", v, v)
            sender.send(AUDIO_PCM, struct.pack(">IBQ", rate, 2, now_us()) + bytes(pcm))
            sent += chunk
            time.sleep(max(0.0, start + sent / rate - time.perf_counter()))

    if not args.no_audio:
        threading.Thread(target=audio, daemon=True).start()

    interval, index, start = 1.0 / fps, keyframes[0], time.perf_counter()
    sent_frames = 0
    last_report, report_frames = start, 0
    while time.perf_counter() - start < args.seconds:
        now = time.perf_counter()
        if now - last_report >= 5:
            lat = sorted(sender.latencies)
            sender.latencies.clear()
            if lat:
                sender.send(LATENCY_REPORT, struct.pack(">I", int(sum(lat) / len(lat))))
            window = now - last_report
            print("%5.0fs  %4.1f fps  %5.1f Mbps  bloqueo %3.0f%%  retraso %s" % (
                now - start, (sent_frames - report_frames) / window, sender.sent_bytes * 8 / window / 1e6,
                100 * sender.send_wait / window,
                "media %.0f ms, mín %.0f, p90 %.0f, máx %.0f (%d)" % (sum(lat) / len(lat), lat[0], lat[int(len(lat) * 0.9)], lat[-1], len(lat)) if lat else "sin datos"),
                flush=True)
            sender.sent_bytes, sender.send_wait, report_frames, last_report = 0, 0.0, sent_frames, now
        if sender.keyframe_wanted.is_set():
            sender.keyframe_wanted.clear()
            index = next((k for k in keyframes if k >= index), keyframes[0])
        key, payload = frames[index]
        sender.send(VIDEO_FRAME, struct.pack(">BQ", 1 if key else 0, now_us()) + payload)
        index = (index + 1) % len(frames)
        if index == 0:
            index = keyframes[0]
        sent_frames += 1
        time.sleep(max(0.0, start + sent_frames * interval - time.perf_counter()))
    print("Enviados %d fotogramas" % sent_frames)


if __name__ == "__main__":
    main()
