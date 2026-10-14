import dev.adbox.toolkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FastbootDiagnosticsChecks {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static class Device implements FastbootClient.Transport {
        final Map<String,String> values=new HashMap<>();
        final List<String> commands=new ArrayList<>();
        String pending;boolean open=true;String failure;
        public void command(byte[] bytes){pending=new String(bytes,StandardCharsets.US_ASCII);commands.add(pending);}
        public byte[] response(int timeout)throws IOException{
            if(failure!=null&&pending.equals(failure))throw new EOFException("USB detached");
            String name=pending.substring("getvar:".length());
            return (values.containsKey(name)?"OKAY"+values.get(name):"FAILUnknown variable").getBytes(StandardCharsets.UTF_8);
        }
        public void data(byte[] bytes,int offset,int length){throw new AssertionError("Diagnostics must never upload data");}
        public boolean isOpen(){return open;}
        public void close(){open=false;}
    }
    public static void main(String[] args)throws Exception{
        Device device=new Device();device.values.putAll(Map.of("product","sunfish","serialno","12345","slot-count","2","current-slot","a","unlocked","yes","slot-successful:a","yes","slot-unbootable:a","no","slot-retry-count:a","0","slot-successful:b","no","slot-unbootable:b","yes"));
        var result=FastbootDiagnostics.device(new FastbootClient(device));
        check(result.variables.get("product").equals("sunfish"),"device identity is from the target");
        check(result.variables.get("battery-soc").equals("Unavailable"),"unsupported battery is not guessed");
        check(result.slots.keySet().equals(new LinkedHashSet<>(List.of("a","b"))),"confirmed A/B target has two slots");
        check(result.slots.get("a").get("slot-successful").equals("yes")&&result.slots.get("b").get("slot-unbootable").equals("yes"),"different slot health remains associated with its slot");
        check(result.slots.get("b").get("slot-retry-count").equals("Unavailable"),"unsupported slot retry count remains unknown");
        check(device.open&&device.commands.stream().allMatch(c->c.startsWith("getvar:")),"unsupported diagnostics preserve the connection and do not issue writes");
        try{result.variables.put("unlocked","no");throw new AssertionError("snapshot was mutable");}catch(UnsupportedOperationException expected){checks++;}
        try{result.slots.get("a").put("slot-successful","no");throw new AssertionError("slot snapshot was mutable");}catch(UnsupportedOperationException expected){checks++;}
        check(FastbootDiagnostics.slotNames("Unavailable","a").equals(List.of("a","b")),"current slot can confirm A/B when count is unsupported");
        check(FastbootDiagnostics.slotNames("0x2","b").equals(List.of("a","b")),"hexadecimal slot count is understood");
        check(FastbootDiagnostics.slotNames("1","a").isEmpty()&&FastbootDiagnostics.slotNames("0","a").isEmpty(),"explicit single/no-slot target does not get guessed slot actions");
        check(FastbootDiagnostics.slotNames("Unavailable","Unavailable").isEmpty()&&FastbootDiagnostics.slotNames("99","a").isEmpty(),"unconfirmed or unsupported slot layout has no switch actions");
        Device single=new Device();single.values.put("slot-count","1");single.values.put("current-slot","a");
        check(FastbootDiagnostics.device(new FastbootClient(single)).slots.isEmpty()&&single.commands.stream().noneMatch(c->c.startsWith("getvar:slot-successful:")),"single-slot targets are not queried as A/B");
        Device partition=new Device();partition.values.putAll(Map.of("partition-size:boot_b","0x04000000","partition-type:boot_b","raw","is-logical:boot_b","no","has-slot:boot","yes"));
        var p=FastbootDiagnostics.partition(new FastbootClient(partition),"boot_b");
        check(p.name.equals("boot_b")&&p.baseName.equals("boot"),"partition identity preserves the requested slot");
        check(p.sizeBytes==67108864L,"partition size uses the target's hexadecimal value");
        check(partition.commands.equals(List.of("getvar:partition-size:boot_b","getvar:partition-type:boot_b","getvar:is-logical:boot_b","getvar:has-slot:boot")),"size/type/logical queries target the exact partition while has-slot queries its base");
        Device unknown=new Device();unknown.values.put("partition-size:recovery","garbage");
        var missing=FastbootDiagnostics.partition(new FastbootClient(unknown),"recovery");
        check(missing.sizeBytes==null&&missing.variables.get("partition-type:recovery").equals("Unavailable"),"unsupported/invalid partition size does not invent a size or absence");
        check(!unknown.commands.contains("getvar:partition-size:recovery_a"),"unsuffixed partition is never silently resolved to a slot");
        Device injection=new Device();try{FastbootDiagnostics.partition(new FastbootClient(injection),"boot_a:userdata");throw new AssertionError("invalid name accepted");}catch(IOException expected){check(injection.commands.isEmpty(),"partition validation precedes all USB commands");}
        Device detached=new Device();detached.failure="getvar:version";try{FastbootDiagnostics.device(new FastbootClient(detached));throw new AssertionError("disconnect ignored");}catch(EOFException expected){check(!detached.open&&detached.commands.get(detached.commands.size()-1).equals("getvar:version"),"transport failure aborts collection rather than becoming Unavailable");}
        check(FastbootDiagnostics.readOnlyCommand("flashing get_unlock_ability"),"standard eligibility query is read-only");
        check(!FastbootDiagnostics.readOnlyCommand("flashing unlock")&&!FastbootDiagnostics.readOnlyCommand("flashing lock")&&!FastbootDiagnostics.readOnlyCommand("erase:userdata")&&!FastbootDiagnostics.readOnlyCommand("set_active:b"),"state-changing commands still require review");
        check(FastbootImageSelection.suggestedPartition("dtbo.img").equals("dtbo"),"DTBO image never defaults to the boot partition");
        check(FastbootImageSelection.suggestedPartition("dtbo_b.img").equals("dtbo_b"),"filename with an exact slot retains that slot");
        check(FastbootImageSelection.suggestedPartition("DTBO_A.IMG").equals("dtbo_a"),"recognized uppercase filename is a review hint");
        check(FastbootImageSelection.suggestedPartition("init_boot.img").equals("init_boot")&&FastbootImageSelection.suggestedPartition("vendor_boot_a.img").equals("vendor_boot_a"),"compound boot partition names remain intact");
        check(FastbootImageSelection.suggestedPartition("vbmeta_system_b.img").equals("vbmeta_system_b"),"compound vbmeta partition is recognized without stripping its base");
        for(String unknownName:List.of("my-rom.img","magisk_patched-123.img","dtbo.img.zip","dtbo.img:boot","../dtbo.img","dtbo_ab.img")){
            check(FastbootImageSelection.suggestedPartition(unknownName).isEmpty(),"unrecognized filename requires an explicit reviewed partition: "+unknownName);
        }
        check(FastbootImageSelection.selectSlot("dtbo","a").equals("dtbo_a")&&FastbootImageSelection.selectSlot("dtbo_b","a").equals("dtbo_a"),"explicit A selection replaces an existing slot rather than doubling it");
        check(FastbootImageSelection.selectSlot("dtbo_a","b").equals("dtbo_b")&&FastbootImageSelection.selectSlot("dtbo_b","").equals("dtbo"),"B and unsuffixed choices are explicit");
        check(FastbootImageSelection.selectSlot("vbmeta_system_a","b").equals("vbmeta_system_b"),"slot selection preserves compound partition names");
        try{FastbootImageSelection.selectSlot("dtbo:boot","a");throw new AssertionError("invalid partition accepted");}catch(IOException expected){checks++;}
        try{FastbootImageSelection.selectSlot("dtbo","ab");throw new AssertionError("invalid slot accepted");}catch(IOException expected){checks++;}
        String longest="x".repeat(128);
        check(FastbootImageSelection.selectSlot(longest,"").equals(longest),"unsuffixed maximum-length partition remains valid");
        try{FastbootImageSelection.selectSlot(longest,"a");throw new AssertionError("too-long slotted partition accepted");}catch(IOException expected){checks++;}
        check(FastbootImageSelection.selectSlot("x".repeat(126),"b").length()==128,"maximum-length slotted partition stays within the command validation limit");
        System.out.println("PASS: "+checks+" Fastboot diagnostics, IMG destination, exact partition, slot health, and disconnect checks.");
    }
}
