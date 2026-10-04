#!/bin/bash
# Install on the already booted board, after the manually attached radio scanned successfully.
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ $(uname -m) == armv7l ]] || { echo 'This installer targets the H3 armv7l board' >&2; exit 1; }
if pgrep -x rtk_hciattach >/dev/null && ! systemctl is-active --quiet carlife-bluetooth; then
    echo 'Stop the manually started rtk_hciattach with Ctrl+C, then rerun. No process was killed.' >&2
    exit 1
fi
for file in /usr/local/sbin/rtk_hciattach /lib/firmware/rtlbt/rtl8761b_fw /lib/firmware/rtlbt/rtl8761b_config; do
    [[ -s "$file" ]] || { echo "Missing $file; use the pinned Radxa installation in BLUETOOTH.md" >&2; exit 1; }
done
install -m 0755 "$root/userpatches/overlay/carlife-bluetooth" /usr/local/sbin/carlife-bluetooth
install -m 0644 "$root/userpatches/overlay/carlife-bluetooth.service" /etc/systemd/system/carlife-bluetooth.service
[[ -e /etc/default/carlife-bluetooth ]] || printf 'BT_UART=/dev/ttyS2\n' > /etc/default/carlife-bluetooth
systemctl daemon-reload
systemctl enable carlife-bluetooth.service bluetooth.service
systemctl restart carlife-bluetooth.service
systemctl start bluetooth.service
echo 'Transport started. Check: bluetoothctl list; journalctl -u carlife-bluetooth -b --no-pager'
echo 'Then reboot and repeat the discovery test to validate automatic startup.'
