#!/usr/bin/env bash
# A/B the QoS promotion by frametime.
#
#   ./bench.sh <world-name> [duration-seconds] [repeats]
#
# Runs the client once per condition, loading <world-name> straight from the launch args, and
# quits itself when the sample is complete. Results land in run/config/mcsilicon-bench.tsv.
#
# The world must already exist in run/saves/. Stand somewhere with a real view — measuring while
# staring at a wall tells you nothing about the chunk renderer.
set -euo pipefail

cd "$(dirname "$0")"

WORLD="${1:-}"
DURATION="${2:-30}"
REPEATS="${3:-3}"

if [[ -z "$WORLD" ]]; then
    echo "usage: ./bench.sh <world-name> [duration-seconds] [repeats]" >&2
    echo >&2
    echo "worlds in run/saves:" >&2
    ls run/saves 2>/dev/null | sed 's/^/  /' >&2 || echo "  (none — create one in game first)" >&2
    exit 1
fi

if [[ ! -d "run/saves/$WORLD" ]]; then
    echo "no such world: run/saves/$WORLD" >&2
    exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"

OPTIONS="run/options.txt"
TSV="run/config/mcsilicon-bench.tsv"
rm -f "$TSV"

# VSync and a framerate cap pin every frame to the display interval, which flattens the
# distribution and hides the entire effect being measured. Force them off for the duration and
# put the player's settings back afterwards, whatever happens.
restore_options() {
    if [[ -f "$OPTIONS.mcsilicon-bak" ]]; then
        mv -f "$OPTIONS.mcsilicon-bak" "$OPTIONS"
        echo "restored $OPTIONS"
    fi
}

if [[ -f "$OPTIONS" ]]; then
    cp "$OPTIONS" "$OPTIONS.mcsilicon-bak"
    trap restore_options EXIT INT TERM
    # Muted too — this launches the game once per condition per pass, and the title music
    # every single time gets old fast.
    sed -i '' \
        -e 's/^enableVsync:.*/enableVsync:false/' \
        -e 's/^maxFps:.*/maxFps:260/' \
        -e 's/^soundCategory_master:.*/soundCategory_master:0.0/' \
        -e 's/^soundCategory_music:.*/soundCategory_music:0.0/' "$OPTIONS"
    grep -q '^enableVsync:' "$OPTIONS" || echo 'enableVsync:false' >> "$OPTIONS"
    grep -q '^maxFps:' "$OPTIONS" || echo 'maxFps:260' >> "$OPTIONS"
    grep -q '^soundCategory_master:' "$OPTIONS" || echo 'soundCategory_master:0.0' >> "$OPTIONS"
    echo "vsync off, framerate uncapped, sound muted for the run"
else
    echo "note: no $OPTIONS yet — the mod will flag the run invalid if vsync ends up on" >&2
fi

# Interleaved rather than all-A-then-all-B: the machine warms up and thermally drifts over a run,
# and alternating keeps that drift from landing entirely on one condition.
BASE_JVM='-XX:+UseZGC -Xmx4G -Xms4G'
# What the tuning report tells people to paste into their launcher. Held against BASE_JVM so the
# advice is measured rather than asserted; read the numbers out of config/mcsilicon-tuning.txt on
# your own machine, these are for a 64 GB, 18-core M5 Max.
TUNED_JVM='-XX:+UseZGC -Xmx8G -Xms8G -XX:+AlwaysPreTouch -Dmax.bg.threads=17'

run_one() {
    local label="$1" qos="$2" window="$3" i="$4" jvm="${5:-$BASE_JVM}" extra="${6:-}"
    echo "==> $label (pass $i/$REPEATS)"
    ./gradlew runClient -q \
        -PbenchWorld="$WORLD" \
        -PbenchWindow="$window" \
        -PbenchJvm="$jvm" \
        -PbenchProps="-Dmcsilicon.bench.enabled=true \
-Dmcsilicon.bench.exitWhenDone=true \
-Dmcsilicon.bench.durationSeconds=$DURATION \
-Dmcsilicon.bench.warmupSeconds=10 \
-Dmcsilicon.bench.label=$label \
-Dmcsilicon.qos.enabled=$qos $extra" \
        > "/tmp/mcsilicon-bench-$label-$i.log" 2>&1 \
        || { echo "    run failed, see /tmp/mcsilicon-bench-$label-$i.log" >&2; return 1; }
    tail -1 "$TSV" | awk -F'\t' \
        '{printf "    %s @%sx, rt=%s, mean %s fps, 1%% low %s fps, p99 %s ms\n", $6, $7, $4, $9, $10, $13}'
}

# half-res differs from qos-on only in window size, so that pair answers "is this machine
# fill-rate bound at all" - i.e. whether a render-scale feature would even be worth building.
# qos-on/qos-off isolates the scheduling change.
# Mach real-time scheduling on the render thread. Differs from qos-on only in this flag, so the
# pair isolates deadline scheduling from class-based scheduling.
REALTIME_PROPS='-Dmcsilicon.realtime.enabled=true'
# App Nap and timer coalescing off. Also differs from qos-on in one flag only.
NOCOALESCE_PROPS='-Dmcsilicon.latency.critical=true'

for i in $(seq 1 "$REPEATS"); do
    run_one qos-off  false 854x480 "$i"
    run_one qos-on   true  854x480 "$i"
    run_one realtime true  854x480 "$i" "$BASE_JVM" "$REALTIME_PROPS"
    run_one nocoal   true  854x480 "$i" "$BASE_JVM" "$NOCOALESCE_PROPS"
    run_one half-res true  427x240 "$i"
    run_one tuned    true  854x480 "$i" "$TUNED_JVM"
done

echo
echo "=== all runs ($TSV) ==="
column -t -s $'\t' "$TSV"

if awk -F'\t' 'NR>1 && $2 != "yes" { found=1 } END { exit !found }' "$TSV"; then
    echo
    echo "!!! Some runs were capped by the display and mean nothing. See the valid column." >&2
    exit 1
fi

echo
echo "=== medians across $REPEATS passes ==="
awk -F'\t' '
function median(list,   a, c, i, j, t) {
    c = split(list, a, " ")
    for (i = 1; i < c; i++) for (j = i + 1; j <= c; j++) if (a[j] < a[i]) { t = a[i]; a[i] = a[j]; a[j] = t }
    return (c % 2) ? a[(c+1)/2] : (a[c/2] + a[c/2+1]) / 2
}
function spread(list,   a, c, i, j, t) {
    c = split(list, a, " ")
    for (i = 1; i < c; i++) for (j = i + 1; j <= c; j++) if (a[j] < a[i]) { t = a[i]; a[i] = a[j]; a[j] = t }
    return a[1] "-" a[c]
}
NR>1 {
    if (!(($1) in seen)) { seen[$1] = ++order; name[order] = $1 }
    fb[$1] = $6; rt[$1] = $4
    fps[$1] = fps[$1] " " $9; low[$1] = low[$1] " " $10; p99[$1] = p99[$1] " " $13
}
END {
    printf "%-11s %-11s %3s %9s %9s %8s   %s\n", "label", "framebuffer", "rt", "meanFps", "1%low", "p99ms", "1%low spread"
    for (i = 1; i <= order; i++) {
        k = name[i]
        printf "%-11s %-11s %3s %9.2f %9.2f %8.2f   %s\n", k, fb[k], rt[k], median(fps[k]), median(low[k]), median(p99[k]), spread(low[k])
    }
}' "$TSV"

echo
echo "Judge on 1% low and p99, not mean fps. A change that lifts the average while widening"
echo "the tail made the game feel worse, and only the tail shows it."
echo "If a condition's gap is smaller than its own spread, it did not do anything."
echo "half-res renders a quarter of the pixels — check the framebuffer column before"
echo "crediting it. If it is no faster, this machine is not fill-rate bound and render"
echo "scaling would buy nothing."
