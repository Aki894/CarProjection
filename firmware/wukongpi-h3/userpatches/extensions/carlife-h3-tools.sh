# Armbian extension: development tools, without a desktop or automatic hotspot.
function post_family_config__carlife_h3_packages() {
    add_packages_to_image openssh-server usbutils iw rfkill bluez hostapd dnsmasq-base \
        avahi-utils iperf3 tcpdump curl git python3 build-essential pkg-config \
        libusb-1.0-0-dev libssl-dev libavcodec-dev libavutil-dev libswresample-dev
}
