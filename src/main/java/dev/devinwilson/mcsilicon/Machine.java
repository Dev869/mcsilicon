package dev.devinwilson.mcsilicon;

import java.util.ArrayList;
import java.util.List;

/**
 * What this Mac actually is. Read once from sysctl.
 *
 * <p>Core tiers are read by name rather than assumed to be "P and E" — an M5 Max reports
 * "Super" x6 + "Performance" x12, with no efficiency tier at all, so hardcoding the old
 * two-tier P/E layout gives wrong numbers on current hardware.
 */
public record Machine(
        String cpu,
        long memoryBytes,
        boolean translated,
        List<Tier> tiers) {

    /** One core tier. {@code index} 0 is always the fastest. */
    public record Tier(int index, String name, int logicalCpus) {
    }

    public static Machine probe() {
        String cpu = Darwin.sysctlString("machdep.cpu.brand_string", System.getProperty("os.arch", "unknown"));
        long mem = Darwin.sysctlLong("hw.memsize", Runtime.getRuntime().maxMemory());
        boolean rosetta = Darwin.sysctlLong("sysctl.proc_translated", 0) == 1;

        List<Tier> tiers = new ArrayList<>();
        long n = Darwin.sysctlLong("hw.nperflevels", 0);
        for (int i = 0; i < n; i++) {
            tiers.add(new Tier(
                    i,
                    Darwin.sysctlString("hw.perflevel" + i + ".name", "tier" + i),
                    (int) Darwin.sysctlLong("hw.perflevel" + i + ".logicalcpu", 0)));
        }
        if (tiers.isEmpty()) {
            tiers.add(new Tier(0, "cpu", Runtime.getRuntime().availableProcessors()));
        }
        return new Machine(cpu, mem, rosetta, List.copyOf(tiers));
    }

    /** Cores on the fastest tier. */
    public int fastCores() {
        return tiers.getFirst().logicalCpus();
    }

    /**
     * Cores worth scheduling game work on: everything except the efficiency tier.
     *
     * <p>Matched on tier name rather than by dropping the last tier. An M1-M4 reports
     * "Performance" + "Efficiency", but an M5 Max reports "Super" + "Performance" with no
     * efficiency tier at all — dropping the last tier there would throw away 12 fast cores.
     */
    public int preferredCores() {
        int sum = 0;
        for (Tier t : tiers) {
            if (!isEfficiency(t)) sum += t.logicalCpus();
        }
        return sum > 0 ? sum : tiers.getFirst().logicalCpus();
    }

    /** True when this chip has a genuine efficiency tier that QoS demotion would park work on. */
    public boolean hasEfficiencyTier() {
        return tiers.stream().anyMatch(Machine::isEfficiency);
    }

    private static boolean isEfficiency(Tier t) {
        String n = t.name().toLowerCase();
        return n.contains("efficiency") || n.contains("e-core") || n.contains("icestorm");
    }

    public boolean appleSilicon() {
        return "aarch64".equals(System.getProperty("os.arch")) || cpu.startsWith("Apple");
    }

    public long memoryGigabytes() {
        return memoryBytes / (1024L * 1024L * 1024L);
    }

    /** Heap size we suggest: enough for a heavy pack, small enough that GC pauses stay short. */
    public int suggestedHeapGb() {
        long gb = memoryGigabytes();
        if (gb >= 32) return 8;
        if (gb >= 16) return 6;
        return 4;
    }

    public String describeTiers() {
        StringBuilder sb = new StringBuilder();
        for (Tier t : tiers) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append(t.logicalCpus()).append('x').append(t.name());
        }
        return sb.toString();
    }
}
