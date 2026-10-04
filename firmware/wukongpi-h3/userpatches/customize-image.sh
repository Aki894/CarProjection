#!/bin/bash
set -euo pipefail
[[ "$3" == orangepizero ]] || { echo 'Unexpected board' >&2; exit 1; }
install -m 0755 /tmp/overlay/carlife-board-check /usr/local/sbin/carlife-board-check
install -m 0644 /tmp/overlay/carlife-firmware-info /etc/carlife-firmware-info
mkdir -p /etc/NetworkManager/conf.d /etc/systemd/journald.conf.d
cat > /etc/NetworkManager/conf.d/90-carlife-wifi.conf <<'EOF'
[connection]
wifi.powersave=2
EOF
cat > /etc/systemd/journald.conf.d/90-carlife.conf <<'EOF'
[Journal]
Storage=volatile
RuntimeMaxUse=16M
EOF
# Keep Armbian's first-login password change and unique SSH host-key generation.
# No credentials or Wi-Fi SSID are baked into this public image.
systemctl enable ssh
systemctl disable hostapd 2>/dev/null || true
ln -sf /usr/share/zoneinfo/Asia/Hong_Kong /etc/localtime
echo Asia/Hong_Kong > /etc/timezone
printf '\nCarLife H3 development image: run sudo carlife-board-check\n' >> /etc/motd
