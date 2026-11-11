"""One emulated lamp: its state, the commands that change it and the color it shows.

Knows nothing of the network and the windows; network.py and window.py use it.
"""

import collections
import colorsys
import math
import threading
import time

# Limits of a lamp, Yeelight Inter-Operation Specification
MAX_CONNECTIONS = 4
CONNECTION_QUOTA = 60  # Commands per minute on one connection
LAMP_QUOTA = 144  # Commands per minute on all the connections of a lamp
QUOTA_PERIOD_S = 60


class CommandError(Exception):
    """A command the lamp refuses; the message goes to the app in the error reply."""


class Quota:
    """Counts the commands of the last QUOTA_PERIOD_S against a limit."""

    def __init__(self, limit):
        self.limit = limit
        self.times = collections.deque()  # time.monotonic() of each command counted, oldest first

    def count(self, now):
        """The number of commands counted in the period before [now]."""
        while self.times and self.times[0] <= now - QUOTA_PERIOD_S:
            self.times.popleft()
        return len(self.times)

    def full(self, now):
        return self.count(now) >= self.limit

    def add(self, now):
        self.times.append(now)


class Lamp:
    """One emulated lamp: its settings, its state and the commands that change it."""

    def __init__(self, config):
        self.id = config["id"]
        self.name = config.get("name", "")
        self.model = config.get("model", "color")
        self.port = config.get("port", 55443)
        self.music_mode = config.get("music_mode", False)  # Supports set_music
        self.power = config.get("power", "on") == "on"
        self.bright = config.get("bright", 100)
        self.color_mode = config.get("color_mode", 1)  # 1 rgb, 2 color temperature, 3 hsv
        self.rgb = config.get("rgb", 0xFFFFFF)
        self.ct = config.get("ct", 4000)
        self.hue = config.get("hue", 0)
        self.sat = config.get("sat", 0)
        self.music_connection = None  # The connection to the app in music mode; None if off
        self.connections = []  # Open control connections, for the notifications
        self.quota = Quota(LAMP_QUOTA)  # The commands of all the connections
        self.lock = threading.Lock()  # The connection threads change the state
        # The change of the shown color the last command started: from which color, when, how long
        self.transition_from = (0, 0, 0)
        self.transition_start = 0.0  # time.monotonic(), s
        self.transition_s = 0.0

    def methods(self):
        """The methods the lamp reports in the search answer."""
        methods = ["get_prop", "set_power", "set_rgb", "set_ct_abx", "set_hsv", "set_bright"]

        if self.music_mode:
            methods.append("set_music")
        return methods

    def props(self, names):
        """The values of the properties [names], as strings; "" for an unknown one, as a lamp."""
        values = {
            "power": "on" if self.power else "off",
            "bright": str(self.bright),
            "color_mode": str(self.color_mode),
            "rgb": str(self.rgb),
            "ct": str(self.ct),
            "hue": str(self.hue),
            "sat": str(self.sat),
            "name": self.name,
            "flowing": "0",
            "delayoff": "0",
        }

        if self.music_mode:
            values["music_on"] = "0" if self.music_connection is None else "1"
        return [values.get(name, "") for name in names]

    def search_answer(self, host):
        """The answer to a search request; [host] is our address the app can reach."""
        lines = [
            "HTTP/1.1 200 OK",
            "Cache-Control: max-age=3600",
            "Date: ",
            "Ext: ",
            f"Location: yeelight://{host}:{self.port}",
            "Server: POSIX UPnP/1.0 YGLC/1",
            f"id: {self.id}",
            f"model: {self.model}",
            "fw_ver: 18",
            f"support: {' '.join(self.methods())}",
            f"power: {'on' if self.power else 'off'}",
            f"bright: {self.bright}",
            f"color_mode: {self.color_mode}",
            f"ct: {self.ct}",
            f"rgb: {self.rgb}",
            f"hue: {self.hue}",
            f"sat: {self.sat}",
            f"name: {self.name}",
        ]

        return "".join(f"{line}\r\n" for line in lines).encode("ascii")

    def handle(self, method, params):
        """Runs one command and returns its result; raises CommandError if the lamp refuses it.
        set_music is not here: it opens a connection, so network.py handles it."""
        match method:
            case "get_prop":
                return self.props(params)
            case "set_power":
                power = params[0] == "on"

                self.start_transition(params[1:3])
                self.power = power
            case "set_rgb":
                self.check_on()
                rgb = check_range(params[0], 0, 0xFFFFFF)

                self.start_transition(params[1:3])
                self.rgb = rgb
                self.color_mode = 1
            case "set_ct_abx":
                self.check_on()
                ct = check_range(params[0], 1700, 6500)

                self.start_transition(params[1:3])
                self.ct = ct
                self.color_mode = 2
            case "set_hsv":
                self.check_on()
                hue = check_range(params[0], 0, 359)
                sat = check_range(params[1], 0, 100)

                self.start_transition(params[2:4])
                self.hue = hue
                self.sat = sat
                self.color_mode = 3
            case "set_bright":
                self.check_on()
                bright = check_range(params[0], 1, 100)

                self.start_transition(params[1:3])
                self.bright = bright
            case _:
                raise CommandError("method not supported")
        return ["ok"]

    def take_quota(self, connection_quota, now):
        """Counts a command against the connection's and the lamp's quotas; raises CommandError
        and counts nothing if either is used up."""
        if connection_quota.full(now):
            raise CommandError(f"client quota exceeded, {CONNECTION_QUOTA} per minute")
        if self.quota.full(now):
            raise CommandError(f"total quota exceeded, {LAMP_QUOTA} per minute")

        connection_quota.add(now)
        self.quota.add(now)

    def check_on(self):
        """The spec: a lamp takes the color and brightness commands only while it is on."""
        if not self.power:
            raise CommandError("the lamp is off")

    def start_transition(self, effect):
        """Starts the change to a new state from the color shown now. [effect] is the command's
        effect and duration, ms: ("smooth", 300) changes gradually, ("sudden", ...) at once."""
        now = time.monotonic()
        smooth = len(effect) == 2 and effect[0] == "smooth"

        self.transition_from = self.shown_color(now)
        self.transition_start = now
        self.transition_s = effect[1] / 1000 if smooth else 0.0

    def target_color(self):
        """The color the lamp shows once the transition ends, (r, g, b), with the brightness."""
        if not self.power:
            return (0, 0, 0)

        match self.color_mode:
            case 1:
                # A lamp takes only the hue from rgb, how bright it shines is bright: the
                # brightest channel goes up to 255, the others in proportion
                red = (self.rgb >> 16) & 0xFF
                green = (self.rgb >> 8) & 0xFF
                blue = self.rgb & 0xFF
                top = max(red, green, blue, 1)
                color = (red * 255 / top, green * 255 / top, blue * 255 / top)
            case 2:
                color = ct_to_rgb(self.ct)
            case _:
                rgb = colorsys.hsv_to_rgb(self.hue / 360, self.sat / 100, 1.0)
                color = tuple(channel * 255 for channel in rgb)
        return tuple(round(channel * self.bright / 100) for channel in color)

    def shown_color(self, now):
        """The color the lamp shows at [now], time.monotonic(): on the way to the target one."""
        target = self.target_color()
        elapsed = now - self.transition_start

        if elapsed >= self.transition_s:
            return target

        part = elapsed / self.transition_s

        return tuple(
            round(old + (new - old) * part) for old, new in zip(self.transition_from, target)
        )

    def status(self, now):
        """A line about the lamp for its window."""
        match self.color_mode:
            case 1:
                color = f"rgb #{self.rgb:06X}"
            case 2:
                color = f"ct {self.ct}K"
            case _:
                color = f"hsv {self.hue},{self.sat}"
        power = "on" if self.power else "off"
        connections = len(self.connections)
        commands = self.quota.count(now)
        music = "" if self.music_connection is None else " · music"

        return (
            f"{power} · {color} · bright {self.bright}% · connections {connections}"
            f" · commands {commands}/{LAMP_QUOTA}{music}"
        )


def check_range(value, low, high):
    if not isinstance(value, int) or not low <= value <= high:
        raise CommandError(f"invalid value {value}, must be {low}..{high}")
    return value


def ct_to_rgb(kelvin):
    """The approximate color of white light of [kelvin], 1700..6500 (Tanner Helland's fit)."""
    t = kelvin / 100
    green = 99.4708025861 * math.log(t) - 161.1195681661
    blue = 0 if t <= 19 else 138.5177312231 * math.log(t - 10) - 305.0447927307

    return (255, min(255, max(0, green)), min(255, max(0, blue)))
