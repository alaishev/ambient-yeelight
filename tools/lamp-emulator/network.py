"""The lamps' network side: answers to the search, control connections and music mode.

Every connection runs on a thread of its own and changes its lamp under lamp.lock.
"""

import json
import socket
import struct
import threading
import time

from lamp import CONNECTION_QUOTA, MAX_CONNECTIONS, CommandError, Quota, check_range

SSDP_GROUP = "239.255.255.250"  # The lamps' multicast group
SSDP_PORT = 1982
LINE_END = b"\r\n"  # Ends every command, reply and notification
MUSIC_CONNECT_TIMEOUT_S = 3  # How long the lamp tries to reach the app's music server
NOTIFIED_PROPS = ["power", "bright", "color_mode", "rgb", "ct", "hue", "sat"]


def log(name, message):
    print(f"{time.strftime('%H:%M:%S')} {name}: {message}", flush=True)


def serve_search(lamps):
    """Answers the search requests, multicast and sent straight to us, for all the lamps."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    membership = struct.pack("4s4s", socket.inet_aton(SSDP_GROUP), socket.inet_aton("0.0.0.0"))

    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", SSDP_PORT))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
    log("search", f"listening on {SSDP_GROUP}:{SSDP_PORT}")
    while True:
        data, address = sock.recvfrom(1024)

        if b"M-SEARCH" not in data or b"wifi_bulb" not in data:
            continue

        host = local_address(address[0])

        log("search", f"request from {address[0]}:{address[1]}")
        for lamp in lamps:
            with lamp.lock:
                answer = lamp.search_answer(host)
            sock.sendto(answer, address)


def local_address(remote):
    """Our address on the interface that reaches [remote]; no packet is sent."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect((remote, SSDP_PORT))
        return probe.getsockname()[0]


def serve_lamp(lamp):
    """Accepts the control connections of [lamp] on its port, each on a thread of its own, at most
    MAX_CONNECTIONS at a time."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("", lamp.port))
    server.listen()
    log(lamp.name, f"control port {lamp.port}")
    while True:
        connection, address = server.accept()
        peer = f"{address[0]}:{address[1]}"

        # Checked and added at once, so two connections coming together cannot both pass
        with lamp.lock:
            accepted = len(lamp.connections) < MAX_CONNECTIONS
            if accepted:
                lamp.connections.append(connection)

        if not accepted:
            log(lamp.name, f"rejected {peer}: {MAX_CONNECTIONS} connections open")
            connection.close()
            continue

        thread = threading.Thread(
            target=serve_connection,
            args=(lamp, connection, address),
            daemon=True,
        )

        thread.start()


def serve_connection(lamp, connection, address):
    """Reads the commands of one control connection, already in lamp.connections, until the app
    closes it."""
    peer = f"{address[0]}:{address[1]}"
    quota = Quota(CONNECTION_QUOTA)

    log(lamp.name, f"connected {peer}")
    try:
        for line in connection.makefile("rb"):
            run_command(lamp, connection, quota, line)
    except OSError as e:
        log(lamp.name, f"{peer}: {e}")
    finally:
        with lamp.lock:
            lamp.connections.remove(connection)
        connection.close()
        log(lamp.name, f"disconnected {peer}")


def parse_command(lamp, line):
    """The (id, method, params) of a command line; None, logged, if the line is not a command."""
    try:
        command = json.loads(line)
        return command["id"], command["method"], command.get("params", [])
    except (ValueError, KeyError, TypeError) as e:
        log(lamp.name, f"bad command {line!r}: {e}")
        return None


def run_command(lamp, connection, quota, line):
    """Runs a command line within the connection's [quota], sends the reply on [connection], then
    the change to every connection."""
    command = parse_command(lamp, line)

    if command is None:
        return

    command_id, method, params = command

    with lamp.lock:
        before = lamp.props(NOTIFIED_PROPS)
        try:
            lamp.take_quota(quota, time.monotonic())
            reply = {"id": command_id, "result": run_lamp_command(lamp, method, params)}
        except CommandError as e:
            reply = {"id": command_id, "error": {"code": -1, "message": str(e)}}
        except (IndexError, TypeError):
            reply = {"id": command_id, "error": {"code": -1, "message": "invalid params"}}
        after = lamp.props(NOTIFIED_PROPS)

    log(lamp.name, f"{method} {params} -> {reply.get('result', reply.get('error'))}")
    connection.sendall(json.dumps(reply).encode("ascii") + LINE_END)
    notify(lamp, before, after)


def run_lamp_command(lamp, method, params):
    """Runs a command on [lamp]; set_music is handled here, as it opens a connection."""
    if method == "set_music" and lamp.music_mode:
        return set_music(lamp, params)
    return lamp.handle(method, params)


def notify(lamp, before, after):
    """Sends the changed properties to every connection, as a lamp does after a change; nothing in
    music mode, as the spec says."""
    changed = {
        name: value
        for name, old, value in zip(NOTIFIED_PROPS, before, after)
        if old != value
    }

    if not changed:
        return

    line = json.dumps({"method": "props", "params": changed}).encode("ascii") + LINE_END

    with lamp.lock:
        if lamp.music_connection is not None:
            return

        connections = list(lamp.connections)
    for connection in connections:
        try:
            connection.sendall(line)
        except OSError:
            pass  # The connection's own thread sees the error and closes it


def set_music(lamp, params):
    """Starts music mode, [1, host, port], or stops it, [0]. Called under lamp.lock."""
    if params[0] == 1:
        host = params[1]
        port = check_range(params[2], 1, 65535)
        thread = threading.Thread(target=serve_music, args=(lamp, host, port), daemon=True)

        thread.start()
    else:
        stop_music(lamp)
    return ["ok"]


def stop_music(lamp):
    """Ends music mode: its thread sees the connection end and clears music_connection. Called
    under lamp.lock."""
    if lamp.music_connection is None:
        return

    try:
        lamp.music_connection.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass  # Closed by the app already


def serve_music(lamp, host, port):
    """Connects to the app's music server at host:port and runs the commands it sends until either
    side closes the connection. A newer music connection replaces an older one."""
    try:
        connection = socket.create_connection((host, port), timeout=MUSIC_CONNECT_TIMEOUT_S)
    except OSError as e:
        log(lamp.name, f"music mode: cannot connect to {host}:{port}: {e}")
        return

    connection.settimeout(None)  # The timeout was for connecting; reading waits for the app
    with lamp.lock:
        stop_music(lamp)
        lamp.music_connection = connection
    log(lamp.name, f"music mode on, {host}:{port}")
    try:
        for line in connection.makefile("rb"):
            run_music_command(lamp, line)
    except OSError as e:
        log(lamp.name, f"music mode: {e}")
    finally:
        with lamp.lock:
            if lamp.music_connection is connection:  # Not replaced by a newer one
                lamp.music_connection = None
        connection.close()
        log(lamp.name, "music mode off")


def run_music_command(lamp, line):
    """Runs a command line from the music mode connection: no quota, no reply, no notification."""
    command = parse_command(lamp, line)

    if command is None:
        return

    command_id, method, params = command

    with lamp.lock:
        try:
            run_lamp_command(lamp, method, params)
            result = "ok"
        except CommandError as e:
            result = str(e)
        except (IndexError, TypeError):
            result = "invalid params"

    log(lamp.name, f"music: {method} {params} -> {result}")
