#!/bin/bash
# One manually invoked, bounded full-session test with independent diagnostics.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if (( EUID != 0 )); then
    # Ask once at entry, before tests/USB changes, and preserve all arguments.
    exec sudo -- bash "$root/run-h3-test.sh" "$@"
fi
task_uid=${SUDO_UID:-0}
task_gid=${SUDO_GID:-0}
task_home=$(getent passwd "$task_uid" | cut -d: -f6)
[[ -n "$task_home" ]] || { echo 'Cannot resolve invoking user home' >&2; exit 1; }
umask 077
diagnostics_only=false
if [[ ${1:-} == --diagnostics-only ]]; then
    diagnostics_only=true
    shift
fi
if [[ $diagnostics_only == false ]]; then
    command -v ffmpeg >/dev/null || { echo 'Install once: sudo apt-get update && sudo apt-get install ffmpeg' >&2; exit 1; }
    python3 -m unittest discover -s "$root" -v
    python3 "$root/media_selftest.py"
fi
mkdir -p "$task_home/carlife-tests"
logs=$(mktemp -d "$task_home/carlife-tests/$(date +%Y%m%d-%H%M%S)-XXXXXX")
chown "$task_uid:$task_gid" "$task_home/carlife-tests" "$logs"
# Ownership must be correct before launch, even if the board later resets.
for task_file in recorder.stdout session.jsonl sync.stdout; do
    touch "$logs/$task_file"
    chown "$task_uid:$task_gid" "$logs/$task_file"
done
sync_duration=250
[[ $diagnostics_only == false ]] || sync_duration=15
# Never wait for this process at exit: fsync can block on a failed SD card.
# One process per run, finite normal lifetime; no restart or catch-up storm.
nohup python3 -u "$root/log_sync.py" --interval 2 --duration "$sync_duration" \
    "$logs/session.jsonl" "$logs/system.jsonl" "$logs/recorder.stdout" "$logs/sync.stdout" \
    > "$logs/sync.stdout" 2>&1 < /dev/null &
sync_ready=false
for ((attempt=0; attempt<50; attempt++)); do
    if grep -q 'Sync ready' "$logs/sync.stdout" 2>/dev/null; then
        sync_ready=true
        break
    fi
    sleep 0.1
done
if [[ $sync_ready != true ]]; then
    cat "$logs/sync.stdout" >&2
    echo 'Log sync worker did not start; USB was not changed.' >&2
    exit 1
fi
if [[ $diagnostics_only == true ]]; then
    echo "Read-only reset diagnostics: $logs"
    python3 -u "$root/h3_diagnostics.py" --duration 10 --output "$logs/system.jsonl" \
        > "$logs/recorder.stdout" 2>&1
    exit 0
fi
nohup python3 -u "$root/h3_diagnostics.py" --duration 240 \
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
python3 -u "$root/carlife_md.py" --release-g-serial --duration 180 \
    --log-file "$logs/session.jsonl" "$@" || status=$?
# Make the two recorder/probe files readable to the invoking user after completion.
chown "$task_uid:$task_gid" "$logs/system.jsonl" "$logs/session.jsonl" 2>/dev/null || true
echo "Session exit=$status; independent recorder ends after 240 seconds. Logs: $logs"
exit "$status"
