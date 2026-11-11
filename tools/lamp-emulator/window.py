"""The window of a lamp: its color as the lamp shows it, and a status line."""

import time
import tkinter

FRAME_MS = 33  # How often a window redraws its lamp


class LampWindow:
    """The window of one lamp; redraws it on the tkinter thread, reading it under lamp.lock."""

    def __init__(self, root, lamp):
        self.lamp = lamp
        self.window = tkinter.Toplevel(root)
        self.window.title(f"{lamp.name} :{lamp.port}")
        self.window.geometry("360x220")
        self.window.protocol("WM_DELETE_WINDOW", root.quit)  # Closing one window ends it all
        self.color = tkinter.Frame(self.window)
        self.color.pack(fill="both", expand=True)
        self.status = tkinter.Label(self.window, anchor="w", font=("monospace", 9))
        self.status.pack(fill="x")
        self.redraw()

    def redraw(self):
        """Shows the lamp's state, then again in FRAME_MS."""
        with self.lamp.lock:
            now = time.monotonic()
            red, green, blue = self.lamp.shown_color(now)
            status = self.lamp.status(now)

        self.color.configure(bg=f"#{red:02x}{green:02x}{blue:02x}")
        self.status.configure(text=status)
        self.window.after(FRAME_MS, self.redraw)
