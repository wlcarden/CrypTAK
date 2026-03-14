#!/usr/bin/env python3
"""
Power button handler for reTerminal
- Short press (< 2s): toggle display backlight
- Long press (>= 3s): shutdown (power-cycle barrel jack to restart)
"""
import struct, time, subprocess, os, sys, glob, logging

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("power-btn")

BACKLIGHT  = "/sys/class/backlight/1-0045/brightness"
MAX_BRIGHT = 255
SHORT_MAX  = 2.0   # seconds — max hold for short press
LONG_MIN   = 5.0   # seconds — min hold for long press (shutdown)

EV_KEY    = 1
KEY_SLEEP = 142  # 0x8e

def find_event_device():
    for path in glob.glob("/sys/class/input/input*/name"):
        try:
            if open(path).read().strip() == "gpio_keys":
                d = os.path.dirname(path)
                for ev in glob.glob(d + "/event*"):
                    return f"/dev/input/{os.path.basename(ev)}"
        except Exception:
            pass
    return "/dev/input/event3"

def read_event(f):
    data = f.read(16)
    if len(data) < 16:
        return None
    tv_sec, tv_usec, evtype, code, value = struct.unpack("IIHHI", data)
    return evtype, code, value

def set_backlight(val):
    try:
        open(BACKLIGHT, "w").write(str(val))
    except Exception as e:
        log.error("Backlight error: %s", e)

def get_backlight():
    try:
        return int(open(BACKLIGHT).read().strip())
    except Exception:
        return MAX_BRIGHT

def do_shutdown():
    log.info("LONG PRESS — shutting down. Power-cycle barrel jack to restart.")
    set_backlight(0)
    time.sleep(0.5)
    subprocess.run(["shutdown", "-h", "now"])

def main():
    dev = find_event_device()
    log.info("Power button handler on %s", dev)

    press_time = None

    try:
        f = open(dev, "rb")
    except PermissionError:
        log.error("Cannot open %s — run as root", dev)
        sys.exit(1)

    while True:
        try:
            ev = read_event(f)
            if ev is None:
                continue
            evtype, code, value = ev

            if evtype != EV_KEY or code != KEY_SLEEP:
                continue

            if value == 1:  # key down
                press_time = time.monotonic()
                log.info("Button pressed")

            elif value == 0 and press_time is not None:  # key up
                held = time.monotonic() - press_time
                press_time = None
                log.info("Released after %.2fs", held)

                if held >= LONG_MIN:
                    do_shutdown()
                elif held < SHORT_MAX:
                    if get_backlight() > 0:
                        set_backlight(0)
                        log.info("Display OFF")
                    else:
                        set_backlight(MAX_BRIGHT)
                        log.info("Display ON")

        except Exception as e:
            log.error("Event error: %s — retrying", e)
            press_time = None
            time.sleep(1)
            try:
                f = open(dev, "rb")
            except Exception:
                pass

if __name__ == "__main__":
    main()
