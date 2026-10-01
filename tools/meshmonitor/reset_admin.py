import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.request
from datetime import datetime, timezone


LOCAL = Path.home() / ".config/cryptak/meshmonitor"
REMOTE = "/mnt/user/appdata/cryptak-secrets/meshmonitor"
SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", "unraid"]


def ssh(command, payload=None):
    result = subprocess.run(SSH + [command], input=payload, capture_output=True, timeout=40)
    if result.returncode:
        raise RuntimeError(f"Remote operation failed with exit {result.returncode}; secret output withheld")
    return result.stdout


def private_write(path, data):
    with path.open("xb") as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def mirrored_write(name, data):
    private_write(LOCAL / name, data)
    ssh(f"umask 077; set -Ce; cat > '{REMOTE}/{name}'; chmod 600 '{REMOTE}/{name}'", data)
    if ssh(f"cat '{REMOTE}/{name}'") != data:
        raise RuntimeError("Backup content verification failed")
    if ssh(f"stat -c '%U:%a' '{REMOTE}/{name}'").strip() != b"root:600":
        raise RuntimeError("Backup ownership/permissions verification failed")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description="One-time MeshMonitor admin recovery with protected independent credential copies")
    parser.add_argument("--confirm-reset", action="store_true", required=True)
    args = parser.parse_args()
    if not args.confirm_reset:
        parser.error("Explicit reset confirmation required")
    if LOCAL.exists():
        raise RuntimeError("Credential directory already exists; refusing to overwrite or rotate again")
    ssh(f"test ! -e '{REMOTE}'")
    inspect = """
const Database=require('/app/node_modules/better-sqlite3');
const db=new Database('/data/meshmonitor.db',{readonly:true,fileMustExist:true});
const user=db.prepare('SELECT id,username,password_hash,is_admin,is_active,password_locked,mfa_enabled FROM users WHERE username=?').get('admin');
if (!user || !user.is_admin || !user.is_active || user.mfa_enabled) throw new Error('Account state requires separate review');
process.stdout.write(JSON.stringify(user));
db.close();
"""
    previous = ssh("docker exec -i meshmonitor node --input-type=commonjs", inspect.encode())
    previous_user = json.loads(previous)
    LOCAL.parent.mkdir(mode=0o700, exist_ok=True)
    LOCAL.mkdir(mode=0o700)
    ssh(f"umask 077; mkdir -p '{REMOTE}'; chmod 700 /mnt/user/appdata/cryptak-secrets '{REMOTE}'; test \"$(stat -c '%U:%a' '{REMOTE}')\" = root:700")
    credentials = {
        "service": "MeshMonitor",
        "url": "http://192.168.50.120:8090",
        "username": "admin",
        "password": secrets.token_urlsafe(32),
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    encoded = (json.dumps(credentials, indent=2) + "\n").encode()
    mirrored_write("previous-admin-state.json", previous + b"\n")
    mirrored_write("credentials.json", encoded)
    print("Credential copies saved and byte-verified before password reset", flush=True)
    update = """
const fs=require('fs');
const Database=require('/app/node_modules/better-sqlite3');
const bcrypt=require('/app/node_modules/bcrypt');
const input=JSON.parse(fs.readFileSync(0,'utf8'));
if (typeof input.password!=='string' || input.password.length<32 || input.password.length>72) throw new Error('Invalid password length');
const db=new Database('/data/meshmonitor.db',{fileMustExist:true,timeout:5000});
const hash=bcrypt.hashSync(input.password,12);
db.transaction(()=>{
 const result=db.prepare('UPDATE users SET password_hash=? WHERE id=? AND username=? AND password_hash=? AND is_admin=1 AND is_active=1 AND mfa_enabled=0').run(hash,input.id,'admin',input.previousHash);
 if (result.changes!==1) throw new Error('Account changed; refusing reset');
 db.prepare('INSERT INTO audit_log (user_id,action,resource,details,ip_address,timestamp,username) VALUES (?,?,?,?,?,?,?)').run(input.id,'admin_password_recovery','user',JSON.stringify({method:'authorized SSH recovery',passwordOnly:true}),'127.0.0.1',Date.now(),'admin');
})();
process.stdout.write('password_reset_ok');
db.close();
"""
    import shlex

    payload = json.dumps({"password": credentials["password"], "id": previous_user["id"], "previousHash": previous_user["password_hash"]}).encode()
    response = ssh("docker exec -i meshmonitor node --input-type=commonjs -e " + shlex.quote(update), payload)
    if response != b"password_reset_ok":
        raise RuntimeError("Unexpected reset result")
    print("Password reset; account permissions, MFA settings and API tokens unchanged", flush=True)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    tunnel = subprocess.Popen(SSH[:-1] + ["-o", "ExitOnForwardFailure=yes", "-N", "-L", f"127.0.0.1:{port}:127.0.0.1:8090", "unraid"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for attempt in range(50):
            if tunnel.poll() is not None:
                raise RuntimeError("SSH login tunnel failed")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            raise TimeoutError("SSH login tunnel not ready")
        jar = http.cookiejar.MozillaCookieJar(str(LOCAL / "session.cookies"))
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

        def request(path, data=None, csrf=None):
            headers = {"Content-Type": "application/json"}
            if csrf:
                headers["X-CSRF-Token"] = csrf
            req = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=json.dumps(data).encode() if data is not None else None, headers=headers)
            with client.open(req, timeout=15) as result:
                return json.load(result)

        csrf = request("/api/csrf-token")["csrfToken"]
        login = request("/api/auth/login", {"username": "admin", "password": credentials["password"]}, csrf)
        if login.get("requireMfa"):
            raise RuntimeError("MFA required; complete verification interactively")
        status = request("/api/auth/status")
        if not status.get("authenticated") or status.get("user", {}).get("username") != "admin" or not status["user"].get("isAdmin"):
            raise RuntimeError("Authenticated administrator verification failed")
        jar.save(ignore_discard=True, ignore_expires=True)
        Path(jar.filename).chmod(0o600)
        verification = {
            "verified_at": datetime.now(timezone.utc).isoformat(),
            "authenticated": True,
            "username": "admin",
            "credential_copies_match": True,
            "api_tokens_changed": False,
            "account_permissions_changed": False,
            "mfa_changed": False,
        }
        mirrored_write("verification.json", (json.dumps(verification, indent=2) + "\n").encode())
        print("PASS: new admin login verified over SSH; protected copies match", flush=True)
    finally:
        tunnel.terminate()
        tunnel.wait(timeout=10)


if __name__ == "__main__":
    main()
