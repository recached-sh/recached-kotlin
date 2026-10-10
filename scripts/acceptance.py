#!/usr/bin/env python3
"""Drive the demo app on a device and check the four things the SDK promises.

    scripts/acceptance.py            # needs: a device on adb, engine/ staged
    scripts/acceptance.py --keep-app # leave the demo installed afterwards

It builds and installs the demo, starts a real recached-server on this
machine, and reaches it from the phone through `adb reverse` and a small TCP
relay. Killing the relay takes the app offline while the server stays up, so
the script can change things on the server behind the app's back. Then, through
the app's own UI (uiautomator):

1. Reactive updates: a write made on the server shows up in the app.
2. Durable queued writes: an item added offline survives the app being
   force-stopped, and reaches the server exactly once after reconnecting.
3. Local reads: after the force-stop, with no connection, the app still shows
   everything it had.
4. Reconnect recovery: an item deleted and one added on the server while the
   app was offline are gone and present after reconnect, without restarting
   the app.

Works on an emulator too (adb reverse is the same). The screen must be on and
unlocked.
"""

import argparse
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "dev.recached.demo"
APK = ROOT / "demo/build/outputs/apk/debug/demo-debug.apk"
SERVER = ROOT / "engine/host/recached-server"
WS_PORT, TCP_PORT, RELAY_PORT = 6490, 6489, 6491
TIMEOUT = 60  # seconds; covers the client's reconnect backoff

SERVER_ITEM = "From the server"
OFFLINE_ITEM = "Bought offline"
AWAY_ITEM = "Added while you were away"


ADB = shutil.which("adb") or str(
    Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")) / "platform-tools/adb"
)


def adb(*args, check=True, capture=True):
    result = subprocess.run([ADB, *args], capture_output=capture, text=True)
    if check and result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout if capture else ""


# ── the server, and a relay that can cut the app off it ─────────────────────


class Relay:
    """Forwards RELAY_PORT to the server's WebSocket port until stopped.
    Stopping closes every connection through it, as a lost network would."""

    def __init__(self):
        self.listener = None
        self.conns = []

    def start(self):
        self.listener = socket.create_server(("127.0.0.1", RELAY_PORT), reuse_port=False)
        threading.Thread(target=self._accept, args=(self.listener,), daemon=True).start()

    def stop(self):
        if self.listener:
            self.listener.close()
            self.listener = None
        for c in self.conns:
            try:
                c.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            c.close()
        self.conns = []

    def _accept(self, listener):
        while True:
            try:
                client, _ = listener.accept()
            except OSError:
                return
            try:
                upstream = socket.create_connection(("127.0.0.1", WS_PORT))
            except OSError:
                client.close()
                continue
            self.conns += [client, upstream]
            for a, b in ((client, upstream), (upstream, client)):
                threading.Thread(target=self._pipe, args=(a, b), daemon=True).start()

    @staticmethod
    def _pipe(src, dst):
        try:
            while data := src.recv(65536):
                dst.sendall(data)
        except OSError:
            pass
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def resp_command(*parts):
    out = f"*{len(parts)}\r\n"
    for p in parts:
        out += f"${len(p.encode())}\r\n{p}\r\n"
    return out.encode()


def read_reply(f):
    line = f.readline().decode().rstrip("\r\n")
    kind, rest = line[0], line[1:]
    if kind in "+-:":
        return rest if kind != ":" else int(rest)
    if kind == "$":
        n = int(rest)
        if n < 0:
            return None
        data = f.read(n + 2)[:-2]
        return data.decode()
    if kind == "*":
        return [read_reply(f) for _ in range(int(rest))]
    raise ValueError(f"unexpected reply {line!r}")


def server(*parts):
    """One command on the server's RESP port, as a backend would send it."""
    with socket.create_connection(("127.0.0.1", TCP_PORT)) as s, s.makefile("rb") as f:
        s.sendall(resp_command(*parts))
        return read_reply(f)


def server_titles():
    keys = server("KEYS", "todo:*") or []
    titles = []
    for key in keys:
        value = server("GET", key)
        match = re.search(r'"title"\s*:\s*"([^"]*)"', value or "")
        titles.append(match.group(1) if match else value)
    return titles


# ── the app's UI, through uiautomator ───────────────────────────────────────


def screen():
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
    start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
    if start < 0 or end < 0:
        return None
    return ET.fromstring(raw[start : end + len("</hierarchy>")])


def texts(root):
    return {n.get("text") for n in root.iter("node") if n.get("text")} if root is not None else set()


def by_tag(root, tag):
    for n in root.iter("node"):
        if n.get("resource-id", "").endswith(tag):
            return n
    return None


def tag_text(root, tag):
    node = by_tag(root, tag) if root is not None else None
    if node is None:
        return None
    if node.get("text"):
        return node.get("text")
    return next((c.get("text") for c in node.iter("node") if c.get("text")), None)


def tap(root, tag):
    node = by_tag(root, tag)
    if node is None:
        fail(f"no view tagged {tag!r} on screen")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def wait_for(description, condition):
    deadline = time.monotonic() + TIMEOUT
    root = None
    while time.monotonic() < deadline:
        root = screen()
        if root is not None and condition(root):
            return root
        time.sleep(0.5)
    visible = sorted(texts(root)) if root is not None else "nothing (dump failed)"
    fail(f"timed out waiting for {description}; on screen: {visible}")


def fail(message):
    print(f"\nFAIL: {message}", file=sys.stderr)
    sys.exit(1)


def step(message):
    print(f"• {message}", flush=True)


def check(message):
    print(f"  ✓ {message}", flush=True)


def launch():
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", "--es", "server", f"ws://127.0.0.1:{RELAY_PORT}")


def connection(root):
    return tag_text(root, "connection")


def pending(root):
    return tag_text(root, "pending")


# ── the run ─────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--keep-app", action="store_true", help="leave the demo installed afterwards")
    parser.add_argument("--no-build", action="store_true", help="install the already-built debug APK")
    args = parser.parse_args()

    if not SERVER.is_file():
        sys.exit(f"no {SERVER}; run scripts/build-engine.sh first")
    # A wireless device reconnects through mDNS a few seconds after the adb
    # server starts.
    adb("start-server", check=False)
    deadline = time.monotonic() + 60
    while not re.search(r"\tdevice$", adb("devices"), re.M):
        if time.monotonic() > deadline:
            sys.exit("no device on adb")
        time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if "isKeyguardShowing=true" in adb("shell", "dumpsys", "window"):
        sys.exit("the device is locked; unlock it and keep the screen on, then run again")

    if not args.no_build:
        subprocess.run([str(ROOT / "gradlew"), ":demo:assembleDebug", "--console=plain", "-q"], cwd=ROOT, check=True)

    workdir = tempfile.mkdtemp(prefix="recached-acceptance-")
    env = dict(
        os.environ,
        RECACHED_BIND="127.0.0.1",
        RECACHED_PORT=str(TCP_PORT),
        RECACHED_WS_PORT=str(WS_PORT),
        RECACHED_METRICS_PORT="0",
        RECACHED_SAVE_INTERVAL="0",
    )
    log = open(Path(workdir) / "server.log", "w")
    server_process = subprocess.Popen([str(SERVER)], cwd=workdir, env=env, stdout=log, stderr=log)
    relay = Relay()
    try:
        deadline = time.monotonic() + 10
        while True:
            try:
                socket.create_connection(("127.0.0.1", WS_PORT)).close()
                break
            except OSError:
                if time.monotonic() > deadline:
                    sys.exit(f"recached-server did not start; see {workdir}/server.log")
                time.sleep(0.2)
        relay.start()
        adb("reverse", f"tcp:{RELAY_PORT}", f"tcp:{RELAY_PORT}")

        step("Install the demo with no data, and open it")
        adb("install", "-r", str(APK))
        adb("shell", "pm", "clear", PACKAGE)
        launch()
        wait_for("the app to connect", lambda r: connection(r) == "Connected")
        check("connected")

        step("1. Reactive updates: a backend writes an item")
        server("SET", "todo:0000000000001-0000", f'{{"title":"{SERVER_ITEM}","done":false}}')
        wait_for(f"{SERVER_ITEM!r} to appear", lambda r: SERVER_ITEM in texts(r))
        check("it appears in the app without a refresh")

        step("2. Durable queued writes: go offline and add an item")
        relay.stop()
        wait_for("the app to notice it is offline", lambda r: connection(r) == "Offline, retrying")
        root = screen()
        tap(root, "new-item")
        adb("shell", "input", "text", shlex.quote(OFFLINE_ITEM.replace(" ", "%s")))
        tap(screen(), "add")
        wait_for("the item and its queued write", lambda r: OFFLINE_ITEM in texts(r) and pending(r) == "1 unsynced")
        check("shown at once, 1 write queued")
        assert OFFLINE_ITEM not in server_titles(), "the server cannot have it yet"

        step("3. Local reads: force-stop the app and reopen it, still offline")
        adb("shell", "am", "force-stop", PACKAGE)
        launch()
        wait_for(
            "the saved items and queued write after a cold start",
            lambda r: {SERVER_ITEM, OFFLINE_ITEM} <= texts(r) and pending(r) == "1 unsynced",
        )
        check("everything is still there, read from the device, and the write is still queued")

        step("   Back online: the queued write reaches the server")
        relay.start()
        wait_for("the queued write to be acknowledged", lambda r: pending(r) == "Synced")
        titles = server_titles()
        if titles.count(OFFLINE_ITEM) != 1:
            fail(f"expected {OFFLINE_ITEM!r} on the server exactly once, found {titles}")
        check("on the server exactly once")

        step("4. Reconnect recovery: change the server while the app is offline")
        relay.stop()
        wait_for("the app to notice it is offline", lambda r: connection(r) == "Offline, retrying")
        server("DEL", "todo:0000000000001-0000")
        server("SET", "todo:0000000000002-0000", f'{{"title":"{AWAY_ITEM}","done":false}}')
        root = screen()
        if SERVER_ITEM not in texts(root) or AWAY_ITEM in texts(root):
            fail("the app saw the changes while offline; the relay did not cut it off")
        relay.start()
        wait_for(
            "the deletion and the new item after reconnecting",
            lambda r: SERVER_ITEM not in texts(r) and {AWAY_ITEM, OFFLINE_ITEM} <= texts(r),
        )
        check("the deleted item is gone and the new one is there, without restarting the app")

        print("\nPASS: local reads, durable queued writes, reconnect recovery and live updates")
    finally:
        relay.stop()
        adb("reverse", "--remove", f"tcp:{RELAY_PORT}", check=False)
        server_process.kill()
        server_process.wait()
        log.close()
        if not args.keep_app:
            adb("uninstall", PACKAGE, check=False)


if __name__ == "__main__":
    main()
