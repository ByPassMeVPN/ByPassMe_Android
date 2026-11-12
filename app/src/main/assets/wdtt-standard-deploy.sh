#!/bin/bash
# WDTT standard deploy — matches working OLD NL (144.31.54.213)
# Usage: copy wdtt-server binary to /usr/local/bin/ then run this script as root
set -euo pipefail

IFACE=$(ip route show default | awk '/default/ {print $5; exit}')
[ -n "${IFACE:-}" ] || { echo "No default route"; exit 1; }

mkdir -p /etc/wdtt /usr/local/sbin
echo -n 'ByPassMe' > /etc/wdtt/main.password
chmod 600 /etc/wdtt/main.password
chmod +x /usr/local/bin/wdtt-server

cat > /etc/systemd/system/wdtt-server.service << 'SVC'
[Unit]
Description=WDTT VPN Server (Raw+DTLS)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:56000 -listen-raw 0.0.0.0:56003 -wg-port 56001 -config-dir /etc/wdtt -password-file /etc/wdtt/main.password -dns 1.1.1.1,1.0.0.1
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
SVC

cat > /usr/local/sbin/wdtt-forward-fix.sh << 'FIX'
#!/bin/bash
set -euo pipefail
IFACE=$(ip route show default | awk '/default/ {print $5; exit}')
[ -n "${IFACE:-}" ] || exit 0
iptables -C FORWARD -i wdtt0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wdtt0 -j ACCEPT
iptables -C FORWARD -o wdtt0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -o wdtt0 -j ACCEPT
iptables -C FORWARD -i wdttraw0 -m comment --comment WDTT_RAW_MANAGED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wdttraw0 -m comment --comment WDTT_RAW_MANAGED -j ACCEPT
iptables -C FORWARD -o wdttraw0 -m comment --comment WDTT_RAW_MANAGED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -o wdttraw0 -m comment --comment WDTT_RAW_MANAGED -j ACCEPT
iptables -t nat -C POSTROUTING -s 10.70.0.0/16 -o "$IFACE" -m comment --comment WDTT_RAW_MANAGED -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.70.0.0/16 -o "$IFACE" -m comment --comment WDTT_RAW_MANAGED -j MASQUERADE
iptables -t nat -C POSTROUTING -s 10.66.0.0/16 -o "$IFACE" -m comment --comment WDTT_MANAGED -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.66.0.0/16 -o "$IFACE" -m comment --comment WDTT_MANAGED -j MASQUERADE
iptables -t nat -C POSTROUTING -s 10.66.66.0/24 -o "$IFACE" -m comment --comment WDTT_MANAGED -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.66.66.0/24 -o "$IFACE" -m comment --comment WDTT_MANAGED -j MASQUERADE
sysctl -w net.ipv4.ip_forward=1 >/dev/null
exit 0
FIX
chmod +x /usr/local/sbin/wdtt-forward-fix.sh

cat > /etc/systemd/system/wdtt-forward-fix.service << 'FVS'
[Unit]
Description=Ensure WDTT FORWARD/NAT rules
After=network-online.target ufw.service docker.service wdtt-server.service
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/wdtt-forward-fix.sh
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
FVS

cat > /etc/systemd/system/wdtt-forward-fix.timer << 'FVT'
[Unit]
Description=Re-apply WDTT FORWARD rules periodically
[Timer]
OnBootSec=30s
OnUnitActiveSec=5min
Unit=wdtt-forward-fix.service
[Install]
WantedBy=timers.target
FVT

# UFW: only add WDTT rules; never block existing VPN (443/tcp Happ/Incy, xHTTP, Hy2).
if command -v ufw >/dev/null 2>&1; then
  ufw allow 22/tcp
  ufw allow 443/tcp comment 'VPN clients Happ/Incy/xHTTP'
  ufw allow 443/udp comment 'Hy2/hysteria'
  ufw allow 56000/udp comment 'WDTT DTLS'
  ufw allow 56001/udp comment 'WDTT WireGuard'
  ufw allow 56003/udp comment 'WDTT RAW'
  ufw allow 56000/tcp comment 'WDTT API'
  ufw route allow in on wdtt0 out on "$IFACE"
  ufw route allow in on "$IFACE" out on wdtt0
  ufw route allow in on wdttraw0 out on "$IFACE"
  ufw route allow in on "$IFACE" out on wdttraw0
  # Enable only if not already active — avoids resetting user rules on re-deploy.
  ufw status | grep -q 'Status: active' || ufw --force enable
fi

cat > /etc/sysctl.d/99-wdtt.conf << 'SYS'
net.ipv4.ip_forward=1
net.core.rmem_max=134217728
net.core.wmem_max=134217728
net.ipv4.udp_rmem_min=8192
net.ipv4.tcp_congestion_control=bbr
net.netfilter.nf_conntrack_udp_timeout=300
net.netfilter.nf_conntrack_udp_timeout_stream=300
SYS
sysctl -p /etc/sysctl.d/99-wdtt.conf >/dev/null 2>&1 || true

systemctl disable wdtt 2>/dev/null || true
systemctl stop wdtt 2>/dev/null || true
systemctl daemon-reload
systemctl enable wdtt-server wdtt-forward-fix wdtt-forward-fix.timer
systemctl restart wdtt-server
/usr/local/sbin/wdtt-forward-fix.sh
systemctl enable --now wdtt-forward-fix.timer
echo "Deploy OK: $(hostname)"
