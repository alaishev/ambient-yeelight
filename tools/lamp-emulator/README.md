# Yeelight lamp emulator

**English** | [Русский](README.ru.md)

Replaces real lamps when testing the app: answers the search, takes commands over the local
network and shows the color of every lamp in its own window. The protocol follows
`docs/Yeelight_Inter-Operation_Spec.pdf`.

## Running

You need Python 3.10 or later with `tkinter` (on Ubuntu: `sudo apt install python3-tk`).

    python3 lamp_emulator.py lamps.conf

Closing any window or Ctrl+C in the console stops the emulator.

The computer and the device with the app (a TV or a phone) must be in the same network. If a
firewall is on, open the UDP search port and the lamps' TCP ports:

    sudo ufw allow 1982/udp
    sudo ufw allow 55443:55444/tcp

The Android emulator on the same computer doesn't fit: it's behind NAT, and the search doesn't
reach it.

## Settings

`lamps.conf` is the list of lamps, in JSON:

| Field | What |
|---|---|
| `id` | the lamp's id, the app recognizes the lamp by it |
| `name`, `model` | the name and the model, as in the answer to the search |
| `port` | the TCP control port, every lamp has its own |
| `music_mode` | whether the lamp supports music mode |
| `power`, `bright`, `color_mode`, `rgb`, `ct`, `hue`, `sat` | the initial state; `color_mode`: 1 is rgb, 2 is color temperature, 3 is hsv |

The state isn't written to the file: after a restart the lamps are again as in the settings.

## What is emulated

- **Search**: an answer to `M-SEARCH` over multicast and to a request sent right to the
  computer's address, for all the lamps at once.
- **Commands**: `get_prop`, `set_power`, `set_rgb`, `set_ct_abx`, `set_hsv`, `set_bright`,
  `set_music`. A color and a brightness are taken only by a lamp that is on. An unknown property
  in `get_prop` is an empty string; without music mode `music_on` is empty too.
- **Smooth changes**: `smooth` with a duration changes the color in the window step by step.
- **Notifications**: after a change the lamp sends `props` to all its connections, after the
  answer to the command.
- **Limits**: at most 4 connections, 60 commands a minute per connection, 144 per lamp; over a
  limit the answer is an error.
- **Music mode**: the lamp connects to the app; commands with no answers, no limits and no
  notifications.

The console shows every command and its result, connections and refusals over the limits. The
bottom of the window shows the power, the color, the brightness, the number of connections, the
commands per minute and music mode.

The error texts are made up: the spec has none. The app only cares that an error came.
