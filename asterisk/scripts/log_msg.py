#!/usr/bin/env python3
"""Append one JSON line (JSONL) describing an SMS to a log file under /data.

Called from the Asterisk dialplan via System() in the sms-in / sms-out contexts.
Every free-text or number field is passed base64-encoded so a message body can't
break shell word-splitting or inject anything into the command line; we decode
them here.

Usage:
  log_msg.py <logfile> <direction> <from_b64> <to_b64> <body_b64>
"""

import base64
import fcntl
import json
import os
import sys
from datetime import datetime, timezone


def b64(s):
    try:
        return base64.b64decode(s).decode("utf-8", "replace")
    except Exception:
        return ""


def main():
    if len(sys.argv) != 6:
        print("usage: log_msg.py <logfile> <direction> <from_b64> <to_b64> "
              "<body_b64>", file=sys.stderr)
        return 1

    logfile, direction = sys.argv[1], sys.argv[2]
    rec = {
        # Local-time ISO-8601 with offset (container TZ is set from config).
        "ts": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "type": "sms",
        "direction": direction,
        "from": b64(sys.argv[3]),
        "to": b64(sys.argv[4]),
        "body": b64(sys.argv[5]),
    }
    line = json.dumps(rec, ensure_ascii=False) + "\n"

    os.makedirs(os.path.dirname(logfile) or ".", exist_ok=True)
    # Append under an exclusive lock so concurrent messages can't interleave.
    with open(logfile, "a", encoding="utf-8") as f:
        fcntl.flock(f, fcntl.LOCK_EX)
        try:
            f.write(line)
        finally:
            fcntl.flock(f, fcntl.LOCK_UN)
    return 0


if __name__ == "__main__":
    sys.exit(main())
