# Ambient YeeLight

**English** | [Русский](README.ru.md)

Ambient TV lighting with Xiaomi Yeelight smart bulbs and LED strips. The app captures the
screen, finds its dominant color and sends it smoothly to the lamps over the local network,
about three times a second.

- made for Android TV first; it works on a phone too;
- Android 9 or later (`minSdk 28`);
- the lamps are controlled directly over Wi-Fi, with no Xiaomi cloud and no Yeelight app.

## Features

- **Lamp search** in the local network (SSDP, about 10 seconds), and adding a lamp by hand by its
  IP address.
- **The lamp list is saved.** A new search keeps the enabled lamps and the lamps added by hand.
  If the router gives a lamp a new address, the lamp is still recognized by its id.
- **Lamp status** ("online" / "offline"): found by the search or answered over TCP.
- **Choosing lamps for the light**: a switch on every lamp.
- **Removing a lamp**: a long press on it in the list (on a remote, a long press on OK).
- **The ambient light** is turned on by the "Ambient Light" switch. The system asks for the
  permission to capture the screen; after that the light runs in the background, in a foreground
  service with a notification.
- **Music mode.** If a lamp supports it, the lamp connects to the app and takes colors with no
  rate limit. If not, a usual connection is used, within the lamp's limits.
- **The lamp's state comes back.** When the light starts, the app remembers what each lamp showed
  (power, mode, color or color temperature, brightness), turns it on and sets the brightness from
  the settings. When the light stops, everything comes back as it was; a lamp that was off is
  turned off again.
- **Settings**: the interval between colors, the color change threshold, the transition time and
  the lamps' brightness (70% by default). They apply the next time the light is turned on.
- **Log**, in the settings: the latest lines, saving as a zip to Downloads, sending, clearing.

## Limitations

- **TVs.** The app works only on TVs and set-top boxes with Android TV.
- **Protected video.** Apps with copy protection (DRM), such as Netflix, block screen capture.
  The system gives the app a black frame instead of their picture, and the lamps keep their last
  color. The light works with video that has no such protection: YouTube, file players,
  unprotected TV channels, games.

## Preparing the lamps

In the Yeelight app, turn on **LAN Control** for every lamp. Without it a lamp doesn't answer the
search and doesn't take commands. The TV and the lamps must be in the same network.

## Installing

The app isn't on Google Play. Download the APK from
[Releases](https://github.com/alaishev/ambient-yeelight/releases) and install it by hand: copy it
to the TV on a USB stick and open it in a file manager (the TV asks to allow installing from
unknown sources), or install it over the network with `adb`, see below.

## Building

You need JDK 17 or later and the Android SDK. The SDK path goes into `local.properties`
(`sdk.dir=...`); Android Studio creates it by itself.

```
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # release build
```

APK: `app/build/outputs/apk/debug/ambient-yeelight-v<version>-debug.apk` and
`app/build/outputs/apk/release/ambient-yeelight-v<version>-release.apk`. To install it on a TV
over the network (network debugging must be on in the TV's developer options):

```
adb connect <TV address>:5555
adb install -r app/build/outputs/apk/release/ambient-yeelight-v*-release.apk
```

The project has no signing key of its own yet: the release build is signed with the debug key
(`~/.android/debug.keystore`), so the APK can be installed. Every computer has its own debug
key, so an APK built on another computer won't install over the one already installed: remove
the app first, and the lamp list goes with it.

Without real lamps, the app can be tried with the emulator: `tools/lamp-emulator/`.

## First start

1. Press the search button (the button with a round arrow). The lamps found show up in the
   list. If a lamp isn't found (it happens when the router blocks multicast), add it with the
   "+" button by its IP address.
2. Turn on the switch of every lamp that should take part in the light.
3. Turn on "Ambient Light" and allow the screen capture.

### Xiaomi phones and others with aggressive battery saving

MIUI and similar firmware kill the app together with the light when it's swiped away from
Recents. The screen capture permission is lost too, and the light has to be turned on again. To
keep the light running in the background, in the app's settings:

- turn on "Autostart";
- set "Battery saver" to "No restrictions";
- or lock the app in Recents: long press on its card → the lock.

On Android TV and on stock Android, a swipe doesn't stop the light.

## How it works

1. **Capture.** `ScreenCapture` gets the screen through MediaProjection, scaled down to a
   160×90 frame for a 16:9 screen. The system scales it, so the app never copies a full-size
   picture. Every 300 ms (by default, set in the settings) the newest frame is taken.
2. **Color.** `ColorExtractor` finds the dominant color of the whole frame (Palette, over every
   pixel) and passes it on only if it differs noticeably from the previous one (CIE76 ΔE above
   2.5 by default), so the lamps get no needless commands.
3. **Sending.** Every lamp has its own `LampController`. Colors are collapsed: if a new color
   comes while the previous one still waits to be sent, only the new one goes. The color changes
   smoothly, over 300 ms by default.

Lamp limits, from the Yeelight spec (`docs/Yeelight_Inter-Operation_Spec.pdf`):

- at most 60 commands a minute on one connection: after 60 commands the connection is opened
  again;
- at most 144 commands a minute per lamp in total, that is one per 417 ms, while colors come
  every 300 ms; in a long busy scene a lamp may skip some colors;
- music mode has none of these limits.

If the connection to a lamp is lost, it's opened again with the next color, at most once in
5 seconds.

## Log

The app writes to `files/logs/ambient_light.log`; a debug build also writes to logcat. At 2 MB
the file becomes `ambient_light.log.1`, the old `.1` is deleted, and a new file starts, so the
log keeps the latest 2 to 4 MB. A debug build also writes debug messages (for example, the ΔE of
every new color); a release build starts at INFO.

The log screen opens from the settings with the "Log" button:

- the last 500 lines of the current file;
- "Save": both files as a zip to Downloads; from there it can be copied to a USB stick. On
  Android 9 the app asks for storage access on the first save;
- "Send": the same zip by mail, a messenger or a cloud drive, whatever the device has;
- "Clear": deletes both files.

To read the log on a computer without the log screen (debug build):

```
adb shell run-as com.ambient.yeelight cat files/logs/ambient_light.log
```

## Plans

- **Brightness from the picture.** Now the lamps' brightness is fixed, from the settings. It
  should follow the picture and dim in dark scenes. What's known:
  - a lamp takes only the hue from `set_rgb`, the brightness is set by `bright`: dark grey
    looks white on a strip;
  - the most common color of a frame is often the black bars or a frame around the picture,
    not the picture itself;
  - the CIE76 formula here scales lightness by 2.55, so dark colors flicker through the ΔE
    filter. Try on a real strip what changes without the factor: the filter should stop passing
    changes of brightness alone, and dark scenes should get calmer. The emulator can't show it.
    For now the factor stays: with it the light works well enough.
- **Screen zones.** Each lamp takes the color of its own part of the screen: the left lamp the
  left part, the right one the right part.
- **Command limit.** Count the 60-command limit over the last 60 seconds instead of the whole
  life of a connection, so a connection isn't reopened in calm scenes. Only matters for lamps
  without music mode.
- **Other lamps.** A common lamp interface, so the light works not only with Yeelight, for
  example with an LED strip over USB.
- **Later:** a VLC plugin for Windows, and a Windows app that captures the whole screen, so the
  light works in games and any player.

## License

MIT, see `LICENSE`.
