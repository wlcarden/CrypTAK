# Desktop SSH Setup for Remote Phone Access

**Purpose:** Enable secure remote administration of mission devices via SSH over Tailscale  
**Security:** Ed25519 keys only, password authentication disabled, Tailscale network isolation

---

## Phase 1: Generate Desktop SSH Key

If you don't have an existing SSH key for Termux access:

```bash
# Generate ed25519 key for Termux
ssh-keygen -t ed25519 -f ~/.ssh/termux_rsa -C "termux-control@cryptak" -N ""
```

This creates:
- `~/.ssh/termux_rsa` — private key (keep secret)
- `~/.ssh/termux_rsa.pub` — public key (distribute to phones)

---

## Phase 2: Add Phone Public Keys to Desktop

After each phone's Termux setup completes (during provisioning), it prints:
```
===== THIS PHONE'S PUBLIC KEY (send to admin) =====
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIxxxxxx u0_a123@TAK-01
=================================================
```

**Save each public key to a file:**

```bash
# TAK-01 public key
echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIxxxxx u0_a123@TAK-01' >> ~/.ssh/known_hosts

# TAK-02 public key
echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIyyyyy u0_a456@TAK-02' >> ~/.ssh/known_hosts

# TAK-03 public key
echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIzzzzz u0_a789@TAK-03' >> ~/.ssh/known_hosts
```

---

## Phase 3: SSH Config Aliases (Optional but Useful)

Create `~/.ssh/config`:

```
Host tak-01
  HostName 100.64.0.11      # Will be replaced with actual Tailscale IP
  User u0_a123
  Port 8022
  IdentityFile ~/.ssh/termux_rsa
  StrictHostKeyChecking accept-new

Host tak-02
  HostName 100.64.0.12
  User u0_a456
  Port 8022
  IdentityFile ~/.ssh/termux_rsa
  StrictHostKeyChecking accept-new

Host tak-03
  HostName 100.64.0.13
  User u0_a789
  Port 8022
  IdentityFile ~/.ssh/termux_rsa
  StrictHostKeyChecking accept-new
```

Then connect with short names:
```bash
ssh tak-01
ssh tak-02
ssh tak-03
```

---

## Phase 4: Discover Actual Phone IPs

After phones enroll in Headscale, get their real IPs:

```bash
# List all nodes and their IPs
ssh unraid "docker exec headscale headscale nodes list | grep TAK"
```

Output will show:
```
ID | Name | Status | IP | Last Seen
...
5  | TAK-01 | online | 100.64.0.15 | a few seconds ago
6  | TAK-02 | online | 100.64.0.16 | a few seconds ago
7  | TAK-03 | online | 100.64.0.17 | a few seconds ago
```

Update your `~/.ssh/config` with real IPs.

---

## Phase 5: Test SSH Connection

```bash
# Using config alias
ssh tak-01

# Or direct (before config is set up)
ssh -p 8022 -i ~/.ssh/termux_rsa u0_a123@100.64.0.15
```

If successful, you'll see a Termux shell prompt:
```
u0_a123@TAK-01:~$
```

---

## Useful Remote Commands

Once connected via SSH:

### Check device status
```bash
# From phone Termux:
uname -a                           # OS + kernel
cat /proc/meminfo | head -3        # RAM
df -h /data                        # Storage
ps aux | grep atak                 # ATAK process
tail -f ~/.termux/shell_history    # Recent commands
```

### GPS status (for field Pi equivalent)
```bash
ls -la /dev/tty*                   # Serial devices
getprop ro.hardware                # Device hardware
```

### Battery info
```bash
dumpsys battery | grep -E "level|temp|voltage"
```

### Tailscale status
```bash
# From Termux
ip addr show                       # IP addresses
netstat -tlnp | grep LISTEN        # Open ports
```

### Restart services
```bash
# Restart SSH daemon
sv restart sshd

# Check sshd is running
sv status sshd
```

---

## Remote Administration via Headwind MDM

For non-SSH management, use Headwind MDM (http://100.64.0.1:8095):

### Lock device remotely
```
MDM Console → Devices → [TAK-01] → Actions → Lock Device
```
Phone will lock immediately (requires PIN to unlock).

### Wipe device (DANGEROUS)
```
MDM Console → Devices → [TAK-01] → Actions → Wipe Device
```
This erases all data — only use if device is lost/compromised.

### Deploy app remotely
```
MDM Console → Apps → [app] → Install to Devices
→ Select TAK-01, TAK-02, TAK-03 → Install
```

### Monitor battery + status
```
MDM Console → Dashboard → Device status
Shows: Battery %, last check-in, configuration applied
```

---

## Security Best Practices

✅ **Never share private keys** (`~/.ssh/termux_rsa`)  
✅ **Rotate keys** if device is compromised or decommissioned  
✅ **Use specific hosts** in SSH config, not wildcards  
✅ **Monitor check-ins:** If a device hasn't reported in >1h, investigate  
✅ **SSH logs:** In Termux, check `~/.termux/shell_history` for recent access  
✅ **Firewall:** SSH only accessible over Tailscale (100.64.0.0/24), not internet  

---

## Troubleshooting SSH Connection

| Issue | Fix |
|-------|-----|
| `Connection refused` | Verify Tailscale on both phone + desktop; check `sv status sshd` in Termux |
| `Permission denied (publickey)` | Verify phone's pubkey in `~/.ssh/known_hosts`; check UID in config |
| `Timeout` | Phone may have gone to sleep; check battery; verify network connectivity |
| `Host key verification failed` | Accept the key: `ssh-keyscan -p 8022 100.64.0.x >> ~/.ssh/known_hosts` |

---

## Advanced: Copy Files via SCP

```bash
# Download file from phone
scp -P 8022 u0_a123@100.64.0.15:/data/local/tmp/file.txt ~/Desktop/

# Upload file to phone
scp -P 8022 ~/Desktop/config.json u0_a123@100.64.0.15:/data/local/tmp/
```

---

## Monitoring Script (Optional)

Create a simple bash script to monitor all 3 phones:

```bash
#!/bin/bash
# check-phones.sh

echo "Checking CrypTAK phones..."
for i in 1 2 3; do
  IP="100.64.0.$((14+i))"
  echo -n "TAK-0$i ($IP): "
  if timeout 3 ssh -p 8022 -o StrictHostKeyChecking=no u0_a$((122+i*100))@$IP "echo online" 2>/dev/null | grep -q online; then
    echo "✓ Online"
  else
    echo "✗ Offline or unreachable"
  fi
done
```

Run periodically to monitor device health.

