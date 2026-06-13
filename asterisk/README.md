# reelhelm

Asterisk in Docker, fronting a SIP trunk provider, configured
entirely from a single JSON file. v1 covers **voice**: trunk + extensions,
voicemail, call recording, call forwarding, a block list, and best-effort
**SMS** over SIP MESSAGE.

> Asterisk has no native JSON config — it reads `.conf` files. On startup the
> container reads `data/config.json` and **generates** the Asterisk `.conf`
> files from it (`scripts/render_config.py`). Edit the JSON, never the `.conf`.

## Layout

```
.
├── Dockerfile              # Ubuntu 24.04 + Asterisk 20
├── docker-compose.yml      # host networking (recommended for SIP/RTP)
├── scripts/
│   ├── render_config.py    # config.json -> Asterisk .conf
│   ├── log_msg.py          # appends SMS events to messages.jsonl
│   └── entrypoint.sh        # renders config, then launches Asterisk
└── data/                   # mounted at /data (persistent)
    ├── config.json         # <-- the only file you edit
    ├── spool/voicemail/    # voicemail messages
    ├── recordings/         # call recordings (.wav)
    ├── logs/               # Asterisk logs
    │   ├── messages.jsonl  # SMS in/out log (one JSON object per line)
    │   └── cdr-custom/
    │       └── calls.jsonl # every call in/out/missed (one JSON object per line)
    └── db/                 # astdb
```

## Quick start

1. Edit `data/config.json` (see schema below). At minimum set the trunk
   `server`/`username`/`password`/`did` and each extension `password`.
2. Build and run:
   ```bash
   docker compose up -d --build
   ```
3. Point your SIP client at the host's IP, port 5060, username = extension id
   (e.g. `1001`), password = that extension's password.
4. Useful checks:
   ```bash
   docker exec reelhelm asterisk -rx "pjsip show registrations"   # trunk reg
   docker exec reelhelm asterisk -rx "pjsip show endpoints"       # ext + trunk
   docker exec reelhelm asterisk -rx "dialplan show from-internal"
   ```

## config.json schema

| Field | Meaning |
|---|---|
| `trunk.server` | your SIP trunk provider's server/POP hostname, e.g. `sip.example.com` |
| `trunk.username` / `trunk.password` | the trunk's SIP credentials (often a provider sub-account) |
| `trunk.did` | your SMS-enabled DID; used as outbound caller ID and SMS `from` |
| `trunk.register` | `true` to register with the provider (normal) |
| `trunk.encryption` | `true` to encrypt the trunk leg with SIP-TLS + mandatory SRTP. Requires encryption enabled on your provider account, possibly a **numbered** server hostname (e.g. `sip1.example.com`), and uses port 5061. The client/extension leg is unaffected (stays plain UDP). |
| `trunk.tls_verify` | when encryption is on, verify the provider's certificate against the system CA bundle (default `true`). Set `false` only if the handshake fails on cert validation. |
| `extensions[]` | one entry per SIP client |
| `extensions[].id` / `.password` | SIP auth for the client |
| `extensions[].callerid` | internal caller ID label |
| `extensions[].voicemail.enabled/.pin/.email` | voicemail box + email (see caveat) |
| `extensions[].call_recording.enabled` | record this extension's calls |
| `extensions[].call_forward.enabled/.destination` | default forwarding of inbound calls to a PSTN number (overridable at runtime with `*72`/`*73` — see Feature codes) |
| `inbound_extension` | which extension inbound DID calls ring (default: first) |
| `blocklist[]` | caller numbers to reject (inbound calls **and** SMS) |
| `sms.enabled` | enable SMS-over-SIP routing |
| `logging.calls` | write a JSON line per call to `logs/cdr-custom/calls.jsonl` (default `true`) |
| `logging.messages` | write a JSON line per SMS to `logs/messages.jsonl` (default `true`) |
| `settings.codecs` | e.g. `["ulaw","alaw"]` (North America = ulaw) |
| `settings.rtp_start/end` | RTP UDP port range |
| `settings.external_ip` | `"auto"` (detect public IP) or a literal IP |
| `settings.timezone` | e.g. `America/New_York` (voicemail timestamps) |
| `settings.client_encryption` | `true` to encrypt the **client/extension** leg too: extensions register over SIP-TLS on port 5061 with mandatory SRTP. The entrypoint auto-generates a self-signed cert under `data/certs/` (clients connect with cert verification off). Independent of `trunk.encryption`. Point the SIP client at port **5061** with transport **TLS**. |

After editing the config, apply it with `docker compose restart` (the file is
regenerated on every start).

## What v1 does

- **Inbound calls** → block-list check → optional recording → forward to a PSTN
  number *or* ring `inbound_extension` → voicemail on no-answer. Forwarding can be
  toggled live from a phone with `*72`/`*73` (see Feature codes).
- **Outbound calls** (`from-internal`) → block-list check → optional recording →
  caller ID forced to your DID → out the SIP trunk. Dial `*97` for voicemail.
- **SMS** (if `sms.enabled`): inbound SIP MESSAGE from the trunk is relayed to
  `inbound_extension`; outbound SIP MESSAGE from a client is relayed to the trunk
  with your DID as the sender.

## Feature codes (what to dial)

Dial these from a registered extension (the `from-internal` context):

| Code | Action |
|---|---|
| `*97` | **Check your voicemail.** Logs into *your own* mailbox (the calling extension's box) via `VoiceMailMain` — listen to, save, and delete messages. Prompts for the mailbox `voicemail.pin`. |
| `*72<number>` | **Turn on call forwarding** for your extension to `<number>` (e.g. `*7215551234567`). Forwarded calls go out the trunk with your DID as caller ID. |
| `*73` | **Turn off call forwarding** for your extension. |
| `<extension id>` | **Internal call.** Dial another extension's id (e.g. `1001`) to ring it directly; falls through to its voicemail on no-answer. |
| 10/11-digit number | **Outbound PSTN call** (e.g. `15551234567` or `5551234567`), forced to your DID as caller ID and sent out the trunk. |
| `011…` | **International** outbound call. |

> Asterisk's stock voicemail codes are `*97` (your own box, no number needed)
> and `*98` (log into *any* box, prompts for mailbox number). reelhelm wires up
> **`*97` only** — each extension just checks its own box. There is no `*98`.

### Call forwarding: config vs. dial codes

Call forwarding has two layers:

- **Config default** — `extensions[].call_forward` in `config.json` sets the
  starting state for the **inbound** extension (the one inbound DID calls ring).
- **Runtime override** — `*72`/`*73` from a phone store a per-extension setting
  in Asterisk's database (`CFWD/<ext>`) that **takes precedence over the config
  default and persists across restarts**. `*72<number>` forwards; `*73`
  explicitly disables (it does *not* just fall back to the config default).

The override applies both to inbound DID calls (when set on the inbound
extension) and to internal extension-to-extension calls to that extension. To
wipe an override and return to the pure config default, clear the database key
from the CLI:

```bash
docker exec reelhelm asterisk -rx "database del CFWD 1001"
docker exec reelhelm asterisk -rx "database show CFWD"   # inspect current overrides
```

Call **recording** (`call_recording`) and the **blocklist** remain
config-driven only — no dial codes; edit the JSON and `docker compose restart`.

## Logs (JSON)

Both logs are **JSONL** — one JSON object per line, appended as events happen.
That format is append-safe under concurrent calls/messages and trivially parsed
(`jq -s .` to slurp into an array, or stream line by line). Both live in the
mounted `data/` volume and survive restarts; rotate/trim them yourself.

**Calls** — `data/logs/cdr-custom/calls.jsonl` (toggle `logging.calls`). Driven
by Asterisk's CDR engine, so it captures every call — inbound, outbound, and
missed/unanswered — automatically at hangup:

```json
{"start":"2026-06-13 12:00:01","answer":"2026-06-13 12:00:05","end":"2026-06-13 12:01:30","direction":"inbound","src":"15551112222","dst":"1001","duration":89,"billsec":85,"disposition":"ANSWERED","recording":"/data/recordings/20260613-120001-in-15551112222.wav","uniqueid":"1718294401.3"}
```

`direction` is `inbound` / `outbound` / `internal`; `recording` is the matching
file under `data/recordings/` (empty when recording is off); `disposition` is
`ANSWERED` / `NO ANSWER` / `BUSY` / `FAILED` / `CONGESTION`.

**SMS** — `data/logs/messages.jsonl` (toggle `logging.messages`; needs
`sms.enabled`). Logged for each SIP MESSAGE relayed in or out:

```json
{"ts":"2026-06-13T12:00:00-04:00","type":"sms","direction":"in","from":"15551112222","to":"15551234567","body":"hello"}
```

Message bodies are base64-encoded between the dialplan and `log_msg.py`, so
quotes, newlines, emoji, and shell metacharacters in a text can't corrupt the
log or inject into the command line.

## Important caveats

- **MMS is not supported in v1.** Most SIP trunk providers do not carry MMS
  media over SIP MESSAGE — it's delivered via their HTTP callback/API only. MMS needs a
  separate bridge service (the planned v2 sidecar). SMS-over-SIP also has known
  limits: 160 chars/segment, and inbound SIP MESSAGE arrives with the `To:`
  header set to your account name (not the dialed DID), so multi-DID SMS routing
  isn't possible over pure SIP — use the API/callback path for that.
- **Voicemail-to-email needs an MTA.** Messages are always saved to
  `data/spool/voicemail/`, but the email attachment only sends if you add a mail
  transport. None is bundled in v1.
- **Recording consent** is your responsibility — recording laws vary by
  jurisdiction.

## Security (read before exposing to the internet)

Asterisk is a constant target for toll-fraud scanners.

- **The trunk leg can be encrypted** (`trunk.encryption: true`) — SIP-TLS +
  mandatory SRTP between this server and the trunk provider.
- **The client/extension leg can be encrypted too** (`settings.client_encryption:
  true`) — extensions register over SIP-TLS (5061) with mandatory SRTP. The cert
  is **self-signed** (auto-generated under `data/certs/`), so it gives you
  confidentiality but not certificate authentication — the app connects with
  verification disabled. For authentication on top, replace `data/certs/
  asterisk.{crt,key}` with a cert from your own CA and pin it in the client.
  Either way, still prefer LAN-only or a VPN/WireGuard for the client leg.
- **Do not** expose 5060/UDP (the unencrypted client port) to the open internet
  without protection. Prefer a firewall allowlist or a VPN for clients. With
  `client_encryption` on, clients use 5061/TLS instead.
- Use strong, unique per-extension and trunk passwords (the sample values are
  placeholders — change them).
- Consider fail2ban on the host watching `data/logs/`.
- A compromised extension can place expensive calls on your dime.

## Networking

`docker-compose.yml` uses `network_mode: host`, which is the path of least pain
for SIP + RTP (Docker's NAT otherwise causes one-way/no audio). If you must use
bridge networking, uncomment the `ports:` block and map both 5060/udp and the
full RTP range, and keep that range in sync with `settings.rtp_start/end`.

If calls connect but have no audio, set `settings.external_ip` to your public IP
explicitly rather than relying on `"auto"`.

## Roadmap (v2)

A companion container that speaks the provider's SMS/MMS HTTP API + webhook, stores
media under `data/`, and handles MMS and multi-DID messaging — the things pure
SIP MESSAGE can't do.
