#!/bin/bash
# One manually invoked, bounded full-session test with independent diagnostics.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
command -v ffmpeg >/dev/null || { echo 'Install once: sudo apt-get update && sudo apt-get install ffmpeg' >&2; exit 1; }
python3 -m unittest discover -s "$root" -v
python3 "$root/media_selftest.py"
logs="$HOME/carlife-tests/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$logs"
sudo -v
sudo nohup python3 -u "$root/h3_diagnostics.py" --duration 240 \
    --output "$logs/system.jsonl" > "$logs/recorder.stdout" 2>&1 < /dev/null &
ready=false
for ((attempt=0; attempt<50; attempt++)); do
    if [[ -f "$logs/recorder.stdout" ]] && grep -q 'Recorder ready' "$logs/recorder.stdout" 2>/dev/null; then
        ready=true
        break
    fi
    sleep 0.1
done
if [[ $ready != true ]]; then
    cat "$logs/recorder.stdout" >&2
    echo 'Recorder did not become ready; USB was not changed.' >&2
    exit 1
fi
echo "Logs: $logs"
echo 'Connect the OTG data cable after waiting_for_host; default tone lasts 3 seconds.'
status=0
sudo python3 -u "$root/carlife_md.py" --release-g-serial --duration 180 \
    --log-file "$logs/session.jsonl" "$@" || status=$?
# Make the two recorder/probe files readable to the invoking user after completion.
sudo chown "$(id -u):$(id -g)" "$logs/system.jsonl" "$logs/session.jsonl" 2>/dev/null || true
echo "Session exit=$status; independent recorder ends after 240 seconds. Logs: $logs"
exit "$status"
