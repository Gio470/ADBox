package dev.adbox.toolkit;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/** Filename hints fill the review only; they never resolve slots or execute a flash. */
public final class FastbootImageSelection {
    private FastbootImageSelection() {}
    public static String suggestedPartition(String filename) {
        if (filename == null) return "";
        String name = filename.toLowerCase(Locale.ROOT);
        if (!name.endsWith(".img")) return "";
        name = name.substring(0, name.length() - 4);
        String base = baseName(name);
        if (!Arrays.asList("dtbo", "boot", "init_boot", "vendor_boot", "recovery",
            "vbmeta", "vbmeta_system", "vbmeta_vendor", "super", "system", "system_ext",
            "vendor", "product", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm",
            "userdata", "cache", "dtb").contains(base)) return "";
        return name;
    }
    public static String baseName(String partition) {
        return partition != null && partition.matches(".+_[ab]")
            ? partition.substring(0, partition.length() - 2) : partition;
    }
    public static String selectSlot(String partition, String slot) throws IOException {
        FastbootClient.validatePartition(partition);
        if (!slot.equals("") && !slot.equals("a") && !slot.equals("b")) throw new IOException("Choose an exact name, slot A, or slot B");
        String selected = baseName(partition) + (slot.isEmpty() ? "" : "_" + slot);
        FastbootClient.validatePartition(selected);
        return selected;
    }
}
