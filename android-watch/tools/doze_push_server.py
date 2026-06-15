#!/usr/bin/env python3
"""Host-side push server for the Wear OS inbound-without-push probe.

Listens on 0.0.0.0:9099. On each client connection (the watch's
SocketProbeService, reaching the host at 10.0.2.2:9099 from the emulator), it
pushes one line every INTERVAL seconds. This stands in for a SIP server pushing
an unsolicited INVITE down the already-registered connection.

Run on the host while the watch emulator is up:
    python3 tools/doze_push_server.py

Then drive Doze from adb and watch the RX gaps in `adb logcat -s WEARPROBE`.
"""
import socket
import threading
import time

HOST = "0.0.0.0"
PORT = 9099
INTERVAL = 10  # seconds between server pushes


def serve(conn, addr):
    print(f"[server] client connected: {addr}")
    n = 0
    try:
        conn.settimeout(INTERVAL * 3)
        while True:
            n += 1
            msg = f"tick {n} wall={time.strftime('%H:%M:%S')}\n"
            conn.sendall(msg.encode())
            print(f"[server] -> {addr} {msg.strip()}")
            time.sleep(INTERVAL)
    except Exception as e:
        print(f"[server] client {addr} ended: {e}")
    finally:
        conn.close()


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen(5)
    print(f"[server] listening on {HOST}:{PORT}, pushing every {INTERVAL}s")
    try:
        while True:
            conn, addr = s.accept()
            threading.Thread(target=serve, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\n[server] shutting down")
    finally:
        s.close()


if __name__ == "__main__":
    main()
