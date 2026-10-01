# MeshMonitor access and credential recovery

Last verified: 2026-09-12.

## Current login

- Service: http://192.168.50.120:8090
- Username: `admin`
- Password: stored only in the protected files below, not in this repository.
- The historical default password in `DEPLOYMENT.md` is no longer valid.

## Authoritative credential locations

| Copy | Location | Protection |
| --- | --- | --- |
| Desktop | `/home/wlcarden/.config/cryptak/meshmonitor/credentials.json` | Directory 0700, file 0600, owned by wlcarden |
| Unraid recovery copy | `/mnt/user/appdata/cryptak-secrets/meshmonitor/credentials.json` | Directory 0700, file 0600, owned by root |

The Unraid copy is on persistent appdata storage, not the container filesystem or
the reboot-ephemeral `/root` directory. It is outside MeshMonitor's mounted data
directory. Both copies were written and byte-compared before resetting the
password. The desktop file was fsynced. A successful authenticated admin login
over an SSH tunnel was then verified. Each directory contains `verification.json`
with the result and timestamp.

These are **permission-protected plaintext files, not an encrypted vault**. Do
not attach them to issues, paste them into chat, commit them, or put them in a
public share. Anyone with root access to the respective machine can read them.
Use encrypted backup/password-manager storage for any off-site copy.

## View the saved login on this desktop

Open the credential file in a local text editor:

```bash
xdg-open "$HOME/.config/cryptak/meshmonitor/credentials.json"
```

The password is the `password` value. Avoid displaying it during screen sharing.

## Restore the desktop copy from Unraid

Only use this if the desktop directory is missing. It refuses to overwrite an
existing directory. The authenticated SSH alias `unraid` is already configured
on this desktop; access to Unraid is required on any replacement machine.

```bash
(
  set -eu
  umask 077
  directory="$HOME/.config/cryptak/meshmonitor"
  test ! -e "$directory"
  mkdir -p "$HOME/.config/cryptak"
  mkdir -m 700 "$directory"
  scp unraid:/mnt/user/appdata/cryptak-secrets/meshmonitor/credentials.json "$directory/credentials.json"
  chmod 600 "$directory/credentials.json"
)
```

If restoring onto a replacement machine, obtain its authorized SSH access
separately; this credential backup does not contain SSH private keys.

## Password-reset record

The authorized 2026-09-12 recovery generated a 32-byte cryptographically random
password and changed only the existing admin account's password hash. Existing
permissions, activation state, password-lock state, MFA settings and API tokens
were preserved. An audit-log entry records the recovery. The pre-reset account
record is stored privately as `previous-admin-state.json`; its password hash is
sensitive and is not a plaintext password recovery mechanism.

`tools/meshmonitor/reset_admin.py` records the recovery procedure. It requires
`--confirm-reset` and refuses to run when the credential directory already exists.
Do not delete the directory merely to rerun it. Routine future password changes
must update and verify both credential copies and verify the new login.

The bundled `/app/reset-admin.mjs` also clears MFA and changes other account
flags. Do not use it without reviewing those side effects.

## API tokens and diagnostic access

API tokens inherit the owning account's permissions. Generating a new token
revokes that account's existing token. No token was generated or revoked during
this recovery. Authenticated session cookies are also secrets; the recovery
check stores `session.cookies` only in the protected desktop directory.

For one-time diagnostics, prefer a normal authenticated session with CSRF
protection over SSH rather than replacing an integration's token. Revoke unused
sessions/tokens through MeshMonitor when their work is finished.
