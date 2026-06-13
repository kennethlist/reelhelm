# Asterisk was dropped from Debian 12; Ubuntu 24.04 (universe) ships Asterisk 20,
# which has chan_pjsip, app_voicemail, app_mixmonitor and res_pjsip_messaging —
# everything v1 needs.
FROM ubuntu:24.04

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        asterisk \
        asterisk-modules \
        python3 \
        ca-certificates \
        openssl \
        curl \
        tzdata && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /etc/asterisk/*.conf /etc/asterisk/*.sample

COPY scripts/render_config.py /opt/reelhelm/render_config.py
COPY scripts/log_msg.py /opt/reelhelm/log_msg.py
COPY scripts/entrypoint.sh /opt/reelhelm/entrypoint.sh
RUN chmod +x /opt/reelhelm/entrypoint.sh /opt/reelhelm/log_msg.py

# SIP signalling: 5060/udp plain, 5061/tcp for TLS (trunk and/or client
# encryption). RTP range is configured via config.json (settings.rtp_start/end)
# and is only meaningful with host networking or an explicit port mapping.
EXPOSE 5060/udp
EXPOSE 5061/tcp

VOLUME ["/data"]

ENTRYPOINT ["/opt/reelhelm/entrypoint.sh"]
