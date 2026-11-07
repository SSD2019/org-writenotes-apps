#!/usr/bin/env bash
# deploy_usb.sh — One-command build & SSH deploy to Jailbroken Kindle Scribe (Jobman USB or Wi-Fi mode)
# Usage: ./kindle/deploy_usb.sh [kindle_ip] [kindle_password]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KINDLE_IP="${1:-}"
KINDLE_PW="${2:-kindle}"
HOST_USB_IP="192.168.15.201/24"
HOST_LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}' || true)"

# 1. Auto-detect USB (192.168.15.244) or Wi-Fi (192.168.1.150) Kindle IP if not explicitly specified
if [[ -z "$KINDLE_IP" ]]; then
    if ping -c 1 -W 1 192.168.15.244 >/dev/null 2>&1; then
        KINDLE_IP="192.168.15.244"
    elif ping -c 1 -W 1 192.168.1.150 >/dev/null 2>&1; then
        KINDLE_IP="192.168.1.150"
    else
        KINDLE_IP="192.168.15.244"
        echo "=== Configuring Host USB Network Interface ($HOST_USB_IP) ==="
        USB_CONN="$(nmcli -t -f NAME,DEVICE,TYPE connection show | awk -F: '$2 ~ /^enx|^usb/ {print $1; exit}')"
        if [[ -n "${USB_CONN:-}" ]]; then
            nmcli connection modify "$USB_CONN" ipv4.method manual ipv4.addresses "$HOST_USB_IP" ipv4.never-default yes
            nmcli connection up "$USB_CONN"
        fi
    fi
fi

# 2. Build & package the Kindle Scribe ARM binary and Scriptlets
"$SCRIPT_DIR/build_kual.sh"

TARBALL="$SCRIPT_DIR/dist/org-writenotes-kindle-scribe.tar.gz"

echo "=== Deploying $TARBALL to root@$KINDLE_IP:/mnt/us/ ==="
python3 - "$TARBALL" "$KINDLE_IP" "$KINDLE_PW" "${HOST_LAN_IP:-192.168.1.145}" <<'PYEOF'
import os, pty, select, sys, time

tarball, ip, pw, wifi_host = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

def run_ssh(argv):
    pid, fd = pty.fork()
    if pid == 0:
        os.execvp(argv[0], argv)
    out = b""
    sent_pw = False
    deadline = time.time() + 20
    while time.time() < deadline:
        r, _, _ = select.select([fd], [], [], 0.2)
        if r:
            try:
                chunk = os.read(fd, 4096)
                if not chunk:
                    break
                out += chunk
                if b"password:" in out.lower() and not sent_pw:
                    os.write(fd, (pw + "\n").encode("utf-8"))
                    sent_pw = True
            except OSError:
                break
    os.close(fd)
    os.waitpid(pid, 0)
    return out.decode("utf-8", errors="replace")

print(run_ssh([
    "scp", "-O",
    "-o", "StrictHostKeyChecking=no",
    "-o", "UserKnownHostsFile=/dev/null",
    tarball,
    f"root@{ip}:/tmp/own.tar.gz"
]))

remote_cmd = (
    "killall org-writenotes-kindle 2>/dev/null || true && "
    "cp /mnt/us/org-writenotes/config.json /tmp/own_config.bak 2>/dev/null || true && "
    "tar -xzf /tmp/own.tar.gz -C /mnt/us/ && "
    "rm -f /tmp/own.tar.gz && "
    "if [ -f /tmp/own_config.bak ]; then "
    "  OLD_TOK=$(sed -n 's/.*\"token\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' /tmp/own_config.bak | head -n 1); "
    "  if [ -n \"$OLD_TOK\" ] && [ \"$OLD_TOK\" != \"replace-with-org-writenotes-session-token\" ]; then "
    "    sed -i \"s/\\\"token\\\"[[:space:]]*:[[:space:]]*\\\"[^\\\"]*\\\"/\\\"token\\\": \\\"$OLD_TOK\\\"/\" /mnt/us/org-writenotes/config.json; "
    "  fi; "
    "  rm -f /tmp/own_config.bak; "
    "fi && "
    f"sed -i 's/\"wifiHost\"[[:space:]]*:[[:space:]]*\"[^\"]*\"/\"wifiHost\": \"{wifi_host}\"/' /mnt/us/org-writenotes/config.json && "
    "chmod +x /mnt/us/org-writenotes/bin/org-writenotes-kindle /mnt/us/org-writenotes/run.sh /mnt/us/documents/Org-WriteNotes*.sh && "
    "/mnt/us/org-writenotes/bin/org-writenotes-kindle --devices"
)
print(run_ssh([
    "ssh", "-T", "-x",
    "-o", "StrictHostKeyChecking=no",
    "-o", "UserKnownHostsFile=/dev/null",
    f"root@{ip}",
    remote_cmd
]))
PYEOF

