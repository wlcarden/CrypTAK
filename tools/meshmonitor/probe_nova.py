import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
from datetime import datetime, timezone

from reset_admin import LOCAL, SSH, private_write, ssh


SOURCE = "91f7b51c-cc17-4aed-9871-7eb29b026d07"
TARGETS = {"NP01": "!dac91b96", "NP02": "!5a373c93", "NP03": "!d75e4955"}


def traces(target, minimum_id):
    number = int(target[1:], 16)
    program = f"""
const Database=require('/app/node_modules/better-sqlite3');
const db=new Database('/data/meshmonitor.db',{{readonly:true,fileMustExist:true}});
const rows=db.prepare('SELECT id,fromNodeId,toNodeId,route,routeBack,snrTowards,snrBack,timestamp,createdAt FROM traceroutes WHERE sourceId=? AND id>? AND (fromNodeNum=? OR toNodeNum=?) ORDER BY id').all({json.dumps(SOURCE)},{minimum_id},{number},{number});
process.stdout.write(JSON.stringify(rows));
db.close();
"""
    return json.loads(ssh("docker exec -i meshmonitor node --input-type=commonjs", program.encode()))


def matching_response(rows):
    for row in rows:
        if row["route"] is not None and isinstance(json.loads(row["route"]), list):
            return row
    return None


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="Send one authenticated traceroute to each NoVA bench node; verify replies separately")
    parser.add_argument("--probe", action="store_true", required=True)
    parser.parse_args()
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    tunnel = subprocess.Popen(SSH[:-1] + ["-o", "ExitOnForwardFailure=yes", "-N", "-L", f"127.0.0.1:{port}:127.0.0.1:8090", "unraid"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1)
        if tunnel.poll() is not None:
            raise RuntimeError("SSH tunnel failed")
        jar = http.cookiejar.MozillaCookieJar(str(LOCAL / "session.cookies"))
        jar.load(ignore_discard=True, ignore_expires=False)
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

        def request(path, payload=None):
            headers = {"Content-Type": "application/json"}
            if payload is not None:
                headers["X-CSRF-Token"] = request("/api/csrf-token")["csrfToken"]
            outgoing = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=json.dumps(payload).encode() if payload is not None else None, headers=headers)
            with client.open(outgoing, timeout=15) as response:
                return json.load(response)

        if not request("/api/auth/status").get("authenticated"):
            raise RuntimeError("Authenticated MeshMonitor session required")
        connection = request("/api/connection")
        if not connection.get("connected") or connection.get("userDisconnected"):
            raise RuntimeError("Gateway is not connected")
        results = []
        last_sent = 0
        for name, target in TARGETS.items():
            time.sleep(max(0, 60 - (time.monotonic() - last_sent)))
            old_rows = traces(target, 0)
            minimum_id = max((row["id"] for row in old_rows), default=0)
            entry = {"name": name, "node_id": target, "minimum_trace_id": minimum_id, "requested_at": datetime.now(timezone.utc).isoformat()}
            sent = request("/api/traceroute", {"destination": target, "sourceId": SOURCE})
            if not sent.get("success"):
                raise RuntimeError(f"{name}: gateway did not accept request")
            last_sent = time.monotonic()
            print(f"{name}: request accepted; waiting up to 90 seconds for a route response", flush=True)
            deadline = last_sent + 90
            response = None
            while time.monotonic() < deadline:
                time.sleep(5)
                response = matching_response(traces(target, minimum_id))
                if response:
                    break
            entry["status"] = "response_received" if response else "no_response_within_90_seconds"
            if response:
                entry["trace"] = response
            results.append(entry)
            print(f"{name}: {entry['status']}", flush=True)
        for entry in results:
            if entry["status"] != "response_received":
                response = matching_response(traces(entry["node_id"], entry["minimum_trace_id"]))
                if response:
                    entry["status"] = "response_received_late"
                    entry["trace"] = response
        report = {"checked_at": datetime.now(timezone.utc).isoformat(), "gateway": "Unraid MeshMonitor", "source_id": SOURCE, "results": results}
        destination = Path.home() / "Desktop/Radio Comms/nova-mesh/private" / ("gateway-probes-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + ".json")
        private_write(destination, (json.dumps(report, indent=2) + "\n").encode())
        print("Final results: " + ", ".join(f"{entry['name']}={entry['status']}" for entry in results), flush=True)
        print(f"Report: {destination}", flush=True)
    finally:
        tunnel.terminate()
        tunnel.wait(timeout=10)


if __name__ == "__main__":
    main()
