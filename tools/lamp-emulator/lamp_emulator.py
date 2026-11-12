#!/usr/bin/env python3
"""Emulates Yeelight lamps in the LAN, for testing the app without real lamps.

Each lamp from the settings file answers the search (SSDP, UDP port 1982) and takes commands on
its own TCP port, as described in docs/Yeelight_Inter-Operation_Spec.pdf.

Usage: python3 lamp_emulator.py lamps.conf
"""

import json
import signal
import sys
import threading
import tkinter

from lamp import Lamp
from network import serve_lamp, serve_search
from window import LampWindow


def main():
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 lamp_emulator.py lamps.conf")

    with open(sys.argv[1], encoding="utf-8") as file:
        lamps = [Lamp(config) for config in json.load(file)["lamps"]]

    threading.Thread(target=serve_search, args=(lamps,), daemon=True).start()
    for lamp in lamps:
        threading.Thread(target=serve_lamp, args=(lamp,), daemon=True).start()

    root = tkinter.Tk()

    root.withdraw()  # Only the lamps' windows show
    for lamp in lamps:
        LampWindow(root, lamp)
    signal.signal(signal.SIGINT, lambda signum, frame: root.quit())  # Ctrl+C in the console
    root.mainloop()


if __name__ == "__main__":
    main()
