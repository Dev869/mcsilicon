# mcsilicon

Apple Silicon tuning for Minecraft. Fabric, Minecraft 1.21.11, Java 21.

This does the macOS-specific work that the existing performance mods don't do. It is not a
replacement for Sodium — install Sodium too. Nothing here duplicates it.

## What it does

**Thread QoS promotion.** macOS schedules threads by QoS class, not by Java thread priority
(which the JVM does not map to anything on macOS without root). The JVM starts every thread at
`QOS_CLASS_DEFAULT` and does not propagate the creating thread's class, so nothing in the game
ever asks for the fast core tier. mcsilicon raises:

| thread | class | when |
|---|---|---|
| render / main | `USER_INTERACTIVE` | pre-launch, before the window exists |
| integrated server | `USER_INTERACTIVE` | server start |
| background worker pool | `USER_INITIATED` | client init |

The IO pool is left alone on purpose: it is an unbounded cached pool whose threads are blocked
on disk rather than CPU-bound, and it reaps them after a minute.

**Tuning report.** Writes `config/mcsilicon-tuning.txt` at startup: what the machine is, what the
mod did, and the JVM arguments to set in the launcher — computed from the actual core tiers and
memory, because those have to be set before the JVM starts and no mod can do it from inside.
It also shouts if you are running under Rosetta 2, which costs more than everything here combined.

## Config

`config/mcsilicon.properties`, rewritten on every launch so new keys appear with their defaults.

```properties
qos.enabled=true
qos.render=USER_INTERACTIVE
qos.server=USER_INTERACTIVE
qos.promoteWorkerPools=true
qos.worker=USER_INITIATED
diagnostics.writeTuningReport=true
```

Classes, fastest to slowest: `USER_INTERACTIVE`, `USER_INITIATED`, `DEFAULT`, `UTILITY`,
`BACKGROUND`. `UTILITY` and `BACKGROUND` are confined to the efficiency tier — never put the
render thread there.

## Benchmark

```sh
./bench.sh <world-name> [duration-seconds] [repeats]
```

Runs the client once per condition with `qos.enabled` flipped, loading the world straight from
the launch args and quitting itself when the sample is complete. Conditions are interleaved
rather than run in blocks, so thermal drift doesn't land entirely on one of them. Results go to
`run/config/mcsilicon-bench.tsv`. VSync, the framerate cap and game sound are forced off for the
duration and your `options.txt` is restored afterwards.

The three conditions are `qos-off`, `qos-on`, and `half-res` (a quarter of the pixels at the same
QoS settings, which answers whether the machine is fill-rate bound at all).

It reports frametime percentiles, not average FPS — average FPS hides the stutter that a
scheduling change actually affects.

A run is marked invalid, and the script exits non-zero, if either of these was true:

- **VSync or a framerate cap was on.** Every frame gets pinned to the display interval, so the
  numbers describe the monitor. The script forces both off and restores your `options.txt` after.
- **The game window lost focus.** Minecraft stops rendering when it isn't frontmost, producing
  thousand-FPS non-frames. This one is easy to do by accident and it silently inverted a result
  during development, which is why it is a hard failure rather than a warning.

### Measured results on an M5 Max

Two findings, both negative, both worth knowing:

**QoS promotion: no effect outside noise.** Median 1% low 205.6 fps with it on versus 208.2 off,
across three interleaved 30-second passes, when spread within a single condition was 183-213.
That matches the hardware — QoS pays off by keeping work off efficiency cores, and this chip
reports `6xSuper + 12xPerformance` with no efficiency tier, so `DEFAULT` was already landing on
fast cores. The feature targets M1-M4, where four of eight to ten cores are efficiency cores. The
tuning report says so directly when it detects a machine with no efficiency tier.

**This machine is not fill-rate bound.** The `half-res` condition renders a quarter of the pixels
(427x240 window, 854x480 framebuffer, against 1708x960) and is not faster — median 1% low 212.8
fps against 205.6 at full resolution, well inside the 183-213 spread. Minecraft here is CPU and draw-call bound, which is
what Sodium addresses. That is why there is no render-scaling feature in this mod: it was
measured, and it would buy nothing.

## Build

```sh
./gradlew build        # jar lands in build/libs/
./gradlew selfCheck    # verifies the libSystem bindings against this machine
./gradlew runClient
```

`selfCheck` asserts the things the mod depends on: QoS sets and reads back, child threads do
*not* inherit it (which is why pool promotion exists at all), pool saturation reaches every
worker, and the sysctl probe returns sane hardware. If the inheritance check ever starts
failing, `Qos.promoteExecutor` has become dead code and should be deleted.

## Notes

Core tiers are read from `hw.perflevelN.name` rather than assumed to be P and E. An M1–M4 reports
`Performance` + `Efficiency`; an M5 Max reports `Super` + `Performance` with no efficiency tier at
all, and code that assumes the last tier is slow throws away twelve fast cores on it.

Uses JNA for the libSystem calls, which Minecraft already ships via OSHI — so no bundled
dependency and no native build step. The FFM API would be tidier but is still preview on Java 21.

To retarget a Minecraft version, change `minecraft_version` / `fabric_version` in
`gradle.properties`. Note that a version only builds once Mojang publishes its official mappings —
26.2 does not have them yet, which is why this targets 1.21.11.
