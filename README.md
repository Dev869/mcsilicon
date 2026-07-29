# mcsilicon

**Minecraft on Apple Silicon, scheduled properly.**

macOS decides which cores a thread runs on by its *QoS class*. Java never sets one, so every
Minecraft thread — including the render thread — starts at `DEFAULT` and can be parked on an
efficiency core mid-frame. mcsilicon tells macOS which threads matter, and prints the launcher
settings it can't change from inside the game.

Fabric · Minecraft 1.21.11 · Java 21 · macOS on M-series only.

---

## Will this help *my* Mac?

Be honest with yourself here, because the answer is "it depends", and for some Macs it's "no".

| Your chip | Does it help? | Why |
|---|---|---|
| **M1, M2, M3, M4** (incl. Pro / Max / Ultra) | **Yes** | These have efficiency cores. Without QoS, Minecraft's render thread lands on them regularly, and an efficiency core takes roughly twice as long on the same frame. |
| **M5 Max** (and any chip with no efficiency tier) | **Barely** | [Measured here](#measured-results-on-an-m5-max): no difference outside run-to-run noise. Every core is a fast core, so `DEFAULT` was already fine. |
| **Intel Mac** | **No** | No core tiers to sort threads onto. The mod won't load anything harmful, it just has nothing to do. |

You don't have to guess. Install it, launch once, and read `config/mcsilicon-tuning.txt` — it
names your chip, your core tiers, and tells you plainly if your machine is one of the ones where
this does very little.

**mcsilicon is not a replacement for Sodium.** Sodium is still the single biggest FPS win on a
Mac and this mod deliberately does not duplicate any of it. Install both.

---

## Install

**1. You need these first**

- macOS on an Apple Silicon Mac
- [Fabric Loader](https://fabricmc.net/use/installer/) 0.16.0 or newer, for Minecraft 1.21.11
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 21 or newer, **arm64 build** (see the Rosetta warning below)

**2. Download the jar**

Grab `mcsilicon-0.1.0.jar` from the [latest release](https://github.com/Dev869/mcsilicon/releases/latest).

**3. Drop it in your mods folder**

```
~/Library/Application Support/minecraft/mods/
```

Quickest way to open it: in Finder press <kbd>⇧</kbd><kbd>⌘</kbd><kbd>G</kbd> and paste that path.
If you use a custom launcher profile or a MultiMC/Prism instance, use that instance's `mods`
folder instead.

**4. Launch the game once**, then come back here.

---

## Check it worked

Two places to look.

**The log.** One line near startup:

```
[mcsilicon] Apple M2 Pro (8xPerformance + 4xEfficiency), 16 GB RAM — tuning notes: .../config/mcsilicon-tuning.txt
```

**The report.** Open `config/mcsilicon-tuning.txt` in your Minecraft folder. It's written fresh on
every launch and looks like this:

```
MACHINE
  cpu            Apple M5 Max
  core tiers     6xSuper + 12xPerformance   (tier 0 is the fastest)
  memory         64 GB unified
  rosetta        no
  java           21.0.12 (Homebrew)

WHAT MCSILICON DID
  render thread  -> USER_INTERACTIVE
  server thread  -> USER_INTERACTIVE
  worker pools   -> USER_INITIATED

JVM ARGUMENTS (set these in your launcher - the mod cannot)

  -Xmx8G -Xms8G -XX:+UseZGC -XX:+AlwaysPreTouch -Dmax.bg.threads=17
```

### Do the thing the report asks

That JVM arguments line is the part most people skip, and it's worth more than everything else
here. A wrong heap size or garbage collector costs more frametime than thread scheduling wins
back — but those have to be set **before** the JVM starts, so no mod can set them for you.

Copy the line from *your* report (the numbers are computed from your actual cores and RAM, don't
copy the example above) and paste it into your launcher:

- **Official launcher** → Installations → your profile → ⋯ → Edit → More Options → JVM Arguments
- **Prism / MultiMC** → Edit Instance → Settings → Java → JVM arguments
- **ModrinthApp** → Options → Java & Window → Java arguments

Relaunch. The report's `CURRENT JVM FLAGS` section will show them taking effect.

---

## Settings

`config/mcsilicon.properties`, created on first launch and rewritten every launch so new options
show up with their defaults. Defaults are good; you only need this to turn things off.

```properties
qos.enabled=true                    # master switch for everything below
qos.render=USER_INTERACTIVE         # main + render thread
qos.server=USER_INTERACTIVE         # integrated (singleplayer) server thread
qos.promoteWorkerPools=true         # also promote the background worker pool
qos.worker=USER_INITIATED           # ...to this class
diagnostics.writeTuningReport=true  # write config/mcsilicon-tuning.txt
```

QoS classes, fastest to slowest:

`USER_INTERACTIVE` → `USER_INITIATED` → `DEFAULT` → `UTILITY` → `BACKGROUND`

⚠️ `UTILITY` and `BACKGROUND` are *confined* to the slowest core tier by macOS. Never put the
render thread there — it will be dramatically worse than doing nothing at all.

---

## FAQ

**Does this work on a server?**
The server-side half does — the integrated server thread is promoted in singleplayer, and a
dedicated server on an M-series Mac gets the same treatment. There's nothing macOS-specific to do
on a Linux host.

**Will it break with other performance mods?**
No. It only changes thread scheduling hints and writes a text file. Sodium, Lithium, FerriteCore
and ModernFix all touch completely different things, and you should be running them too.

**I'm on an Intel Mac / Linux / Windows.**
Nothing happens. The libSystem bindings won't load, the report says so, and the game runs normally.

**Why do I need Fabric API?**
For the mod loading hooks. It's the single most common Fabric dependency; you almost certainly
have it already.

**How do I uninstall it?**
Delete the jar from `mods/`. You may also want to remove the JVM arguments you pasted into your
launcher, and delete `config/mcsilicon.properties` and `config/mcsilicon-tuning.txt`.

**The report says "rosetta: YES".**
You're running an x86 Java under translation, and it's costing you more than every optimization
on this page combined. Install an arm64 build of Java 21 (`brew install openjdk@21`, or Temurin's
aarch64 macOS package) and point your launcher at it.

---

## What it actually does

**Thread QoS promotion.** The JVM starts every thread at `QOS_CLASS_DEFAULT` and does not
propagate the creating thread's class to threads it spawns, so nothing in the game ever asks for
the fast core tier. Java thread priority doesn't help — the JVM does not map it to anything on
macOS without root. mcsilicon raises:

| thread | class | when |
|---|---|---|
| render / main | `USER_INTERACTIVE` | pre-launch, before the window exists |
| integrated server | `USER_INTERACTIVE` | server start |
| background worker pool | `USER_INITIATED` | client init |

The IO pool is left alone on purpose: it's an unbounded cached pool whose threads block on disk
rather than CPU, and it reaps them after a minute anyway.

**Tuning report.** `config/mcsilicon-tuning.txt`, described above.

---

## Benchmark

There's a frametime benchmark built in, if you want to measure the difference on your own machine
rather than take anyone's word for it. Needs the repo checked out:

```sh
./bench.sh <world-name> [duration-seconds] [repeats]
```

It launches the client once per condition, loads the world straight from the launch args, samples,
and quits itself. Conditions are interleaved rather than run in blocks so thermal drift doesn't
land entirely on one of them. Results go to `run/config/mcsilicon-bench.tsv`.

Three conditions: `qos-off`, `qos-on`, and `half-res` (a quarter of the pixels at the same QoS
settings, which answers whether the machine is fill-rate bound at all).

It reports frametime percentiles, not average FPS — average FPS hides exactly the stutter that a
scheduling change affects.

VSync, the framerate cap and game sound are forced off for the duration, and your `options.txt` is
restored afterward. A run is marked invalid and the script exits non-zero if either of these
happened:

- **VSync or a framerate cap was on.** Every frame gets pinned to the display interval, so the
  numbers describe your monitor, not your CPU.
- **The game window lost focus.** Minecraft stops rendering when it isn't frontmost, producing
  thousand-FPS non-frames. Easy to do by accident, and it silently inverted a result during
  development, which is why it's a hard failure rather than a warning.

### Measured results on an M5 Max

**Setup.** Apple M5 Max (6×Super + 12×Performance, no efficiency tier), 64 GB, macOS 26, Java 21
arm64, vanilla renderer with no Sodium. Render distance 16, simulation distance 12, windowed at
854×480 logical / 1708×960 framebuffer. Three interleaved 30-second passes per condition after a
10-second warmup, ~10,000 frames each. Raw data: [`benchmarks/m5-max.tsv`](benchmarks/m5-max.tsv).

Median of three runs, with the min–max range across those runs in parentheses:

| | Without mod | With mod | Difference |
|---|---|---|---|
| **Mean FPS** ↑ | **343.0** <br><sub>332.8 – 363.4</sub> | **354.7** <br><sub>343.2 – 358.3</sub> | +11.7 fps (+3.4%) |
| **1% low FPS** ↑ | **208.2** <br><sub>191.1 – 211.3</sub> | **205.6** <br><sub>182.9 – 212.5</sub> | −2.7 fps (−1.3%) |
| Median frametime ↓ | 2.69 ms <br><sub>2.42 – 2.77</sub> | 2.56 ms <br><sub>2.46 – 2.66</sub> | −0.13 ms |
| 95th pct frametime ↓ | 4.23 ms <br><sub>4.18 – 4.30</sub> | 4.19 ms <br><sub>4.01 – 4.30</sub> | −0.04 ms |
| 99th pct frametime ↓ | 4.80 ms <br><sub>4.73 – 5.23</sub> | 4.86 ms <br><sub>4.71 – 5.47</sub> | +0.06 ms |
| 99.9th pct frametime ↓ | 5.75 ms <br><sub>5.59 – 6.26</sub> | 5.57 ms <br><sub>5.50 – 6.47</sub> | −0.18 ms |
| Stutters ↓ | 43 <br><sub>25 – 78</sub> | 57 <br><sub>25 – 157</sub> | +14 |

↑ higher is better, ↓ lower is better. A "stutter" is any frame taking more than twice that run's
own median frametime, so it measures consistency relative to the run rather than an absolute
threshold.

**Read the ranges, not the differences.** Every difference in that last column is smaller than the
spread between two runs of the *same* condition — mean FPS varied by 30 fps run-to-run without
touching a setting, and the mod "gained" 11.7. The honest summary is **no measurable effect on
this machine**, in either direction.

That is the expected result here, not a failure. QoS promotion pays off by keeping work off
efficiency cores, and this chip has none — it reports `6xSuper + 12xPerformance`, so `DEFAULT` was
already landing on fast cores. The feature targets M1–M4, where four of eight to ten cores are
efficiency cores and the render thread genuinely does get parked on one. The tuning report says
this directly when it detects a machine with no efficiency tier.

**No M1–M4 numbers here yet.** I don't own one of those chips. If you run `./bench.sh` on one, a
PR with your TSV is very welcome — that's the configuration this mod is actually for.

**Bonus finding: this machine is not fill-rate bound.** A third condition renders a quarter of the
pixels (427×240 window, 854×480 framebuffer) and is not faster — 350.3 mean FPS / 212.8 1% low,
inside the same noise band as full resolution. Minecraft here is CPU and draw-call bound, which is
what Sodium addresses. That's why there is no render-scaling feature in this mod: it was measured,
and it would buy nothing.

---

## Build from source

```sh
./gradlew build        # jar lands in build/libs/
./gradlew selfCheck    # verifies the libSystem bindings against this machine
./gradlew runClient
```

`selfCheck` asserts the things the mod depends on: QoS sets and reads back, child threads do
*not* inherit it (which is why pool promotion exists at all), pool saturation reaches every
worker, and the sysctl probe returns sane hardware. If the inheritance check ever starts failing,
`Qos.promoteExecutor` has become dead code and should be deleted.

---

## Design notes

Core tiers are read from `hw.perflevelN.name` rather than assumed to be P and E. An M1–M4 reports
`Performance` + `Efficiency`; an M5 Max reports `Super` + `Performance` with no efficiency tier at
all, and code that assumes the last tier is slow throws away twelve fast cores on it.

Uses JNA for the libSystem calls, which Minecraft already ships via OSHI — so no bundled
dependency and no native build step. The FFM API would be tidier but is still preview on Java 21.

To retarget a Minecraft version, change `minecraft_version` / `fabric_version` in
`gradle.properties`. Note that a version only builds once Mojang publishes its official mappings —
26.2 does not have them yet, which is why this targets 1.21.11.

---

MIT. See [LICENSE](LICENSE).
