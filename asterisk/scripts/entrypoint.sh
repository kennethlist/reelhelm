#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${CONFIG_FILE:-/data/config.json}"
ETC_DIR="/etc/asterisk"

echo "[entrypoint] reelhelm starting"

if [[ ! -f "$CONFIG_FILE" ]]; then
    echo "[entrypoint] ERROR: $CONFIG_FILE not found. Mount your data/ dir at /data." >&2
    exit 1
fi

# Persistent storage layout (lives in the mounted /data volume).
mkdir -p /data/spool/voicemail \
         /data/recordings \
         /data/logs/cdr-custom \
         /data/db
mkdir -p /var/run/asterisk

# Optional timezone from config (best-effort).
TZ_VAL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("settings",{}).get("timezone",""))' "$CONFIG_FILE" 2>/dev/null || true)"
if [[ -n "$TZ_VAL" && -f "/usr/share/zoneinfo/$TZ_VAL" ]]; then
    ln -sf "/usr/share/zoneinfo/$TZ_VAL" /etc/localtime
    echo "$TZ_VAL" > /etc/timezone
fi

# If client-leg encryption is on, make sure a TLS server cert exists. It's
# self-signed (the app connects with cert verification off), regenerated only if
# missing, and persisted under the mounted /data volume.
CLIENT_ENC="$(python3 -c 'import json,sys;print("1" if json.load(open(sys.argv[1])).get("settings",{}).get("client_encryption") else "")' "$CONFIG_FILE" 2>/dev/null || true)"
if [[ -n "$CLIENT_ENC" ]]; then
    CERT_DIR=/data/certs
    mkdir -p "$CERT_DIR"
    if [[ ! -f "$CERT_DIR/asterisk.crt" || ! -f "$CERT_DIR/asterisk.key" ]]; then
        echo "[entrypoint] client_encryption on: generating self-signed TLS cert"
        openssl req -x509 -newkey rsa:2048 -nodes \
            -keyout "$CERT_DIR/asterisk.key" \
            -out "$CERT_DIR/asterisk.crt" \
            -days 3650 -subj "/CN=reelhelm" >/dev/null 2>&1
        chmod 600 "$CERT_DIR/asterisk.key"
        echo "[entrypoint] cert SHA-256 fingerprint:"
        openssl x509 -in "$CERT_DIR/asterisk.crt" -noout -fingerprint -sha256 2>/dev/null || true
    fi
fi

# Render Asterisk .conf files from JSON.
python3 /opt/reelhelm/render_config.py "$CONFIG_FILE" "$ETC_DIR"

echo "[entrypoint] launching Asterisk"
exec /usr/sbin/asterisk -f -vvv
