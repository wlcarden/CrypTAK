#!/usr/bin/env python3
"""
GPS MQTT Bridge — reads TPV from gpsd and publishes to MQTT
Topic: cryptak/field/gps/tpv
"""
import socket, json, time, logging
import paho.mqtt.client as mqtt

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("gps-bridge")

GPSD_HOST = "127.0.0.1"
GPSD_PORT = 2947
MQTT_HOST = "127.0.0.1"
MQTT_PORT = 1883
MQTT_TOPIC = "cryptak/field/gps/tpv"
PUBLISH_INTERVAL = 2.0  # seconds between publishes

WATCH_CMD = b'?WATCH={"enable":true,"json":true};\n'

def connect_gpsd():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((GPSD_HOST, GPSD_PORT))
    s.sendall(WATCH_CMD)
    return s

def main():
    mc = mqtt.Client(client_id="gps-bridge")
    mc.connect(MQTT_HOST, MQTT_PORT, 60)
    mc.loop_start()
    log.info("Connected to MQTT")

    last_publish = 0

    while True:
        try:
            log.info("Connecting to gpsd...")
            s = connect_gpsd()
            buf = ""
            log.info("Connected to gpsd, streaming...")
            while True:
                data = s.recv(4096).decode("utf-8", errors="ignore")
                if not data:
                    break
                buf += data
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        msg = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if msg.get("class") == "TPV":
                        now = time.time()
                        if now - last_publish >= PUBLISH_INTERVAL:
                            mc.publish(MQTT_TOPIC, json.dumps(msg), qos=0, retain=True)
                            mode = msg.get("mode", 0)
                            if mode >= 2:
                                lat = msg.get("lat", 0)
                                lon = msg.get("lon", 0)
                                log.info(f"Fix! lat={lat:.6f} lon={lon:.6f} mode={mode}")
                            else:
                                log.info("No fix yet (mode=%d)", mode)
                            last_publish = now
        except Exception as e:
            log.error("Error: %s — retrying in 5s", e)
            time.sleep(5)

if __name__ == "__main__":
    main()
