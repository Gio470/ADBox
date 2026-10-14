package dev.adbox.toolkit;

import java.io.IOException;
import java.util.*;

/** Read-only, target-reported diagnostics. Unsupported variables are never guessed. */
public final class FastbootDiagnostics {
    private FastbootDiagnostics() {}
    public static final String UNAVAILABLE = "Unavailable";
    private static final List<String> DEVICE_VARIABLES = Arrays.asList(
        "product", "serialno", "version", "version-bootloader", "version-baseband",
        "unlocked", "secure", "current-slot", "slot-count", "is-userspace",
        "max-download-size", "max-fetch-size", "battery-soc", "battery-voltage");

    public static final class Snapshot {
        public final Map<String,String> variables;
        public final Map<String,Map<String,String>> slots;
        Snapshot(Map<String,String> variables, Map<String,Map<String,String>> slots) {
            this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            this.slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        }
    }
    public static final class Partition {
        public final String name, baseName;
        public final Map<String,String> variables;
        public final Long sizeBytes;
        Partition(String name, String base, Map<String,String> variables) {
            this.name = name; this.baseName = base;
            this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            this.sizeBytes = FastbootClient.byteSize(variables.get("partition-size:" + name));
        }
    }
    private static String read(FastbootClient client, String name) throws IOException {
        try {
            String value = client.variable(name).trim();
            return value.isEmpty() ? UNAVAILABLE : value;
        } catch (FastbootClient.Rejected unsupported) {
            return UNAVAILABLE;
        }
        // Connection/protocol errors propagate and stop the collection.
    }
    public static Snapshot device(FastbootClient client) throws IOException {
        Map<String,String> variables = new LinkedHashMap<>();
        for (String name : DEVICE_VARIABLES) variables.put(name, read(client, name));
        Map<String,Map<String,String>> slots = new LinkedHashMap<>();
        for (String slot : slotNames(variables.get("slot-count"), variables.get("current-slot"))) {
            Map<String,String> health = new LinkedHashMap<>();
            for (String field : Arrays.asList("slot-successful", "slot-unbootable", "slot-retry-count")) {
                health.put(field, read(client, field + ":" + slot));
            }
            slots.put(slot, Collections.unmodifiableMap(health));
        }
        return new Snapshot(variables, slots);
    }
    public static List<String> slotNames(String count, String current) {
        int number;
        try { number = Integer.decode(count.trim()); }
        catch (Exception unknown) { number = -1; }
        if (number == 2) return Arrays.asList("a", "b");
        // Some A/B bootloaders omit slot-count but report their current slot.
        if (number < 0 && ("a".equals(current) || "b".equals(current))) return Arrays.asList("a", "b");
        return Collections.emptyList();
    }
    public static Partition partition(FastbootClient client, String name) throws IOException {
        FastbootClient.validatePartition(name);
        String base = name.matches(".+_[ab]") ? name.substring(0, name.length() - 2) : name;
        Map<String,String> variables = new LinkedHashMap<>();
        for (String field : Arrays.asList("partition-size", "partition-type", "is-logical")) {
            variables.put(field + ":" + name, read(client, field + ":" + name));
        }
        variables.put("has-slot:" + base, read(client, "has-slot:" + base));
        return new Partition(name, base, variables);
    }
    public static boolean readOnlyCommand(String command) {
        return command.startsWith("getvar:") || command.equals("flashing get_unlock_ability");
    }
}
