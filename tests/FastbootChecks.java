import dev.adbox.toolkit.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class FastbootChecks {
    static int checks;
    static void check(boolean test,String message){if(!test)throw new AssertionError(message);checks++;}
    static class Fake implements FastbootClient.Transport {
        final Queue<byte[]> replies=new ArrayDeque<>();final List<String> commands=new ArrayList<>();final ByteArrayOutputStream data=new ByteArrayOutputStream();boolean open=true;
        Fake(String...responses){for(String s:responses)replies.add(s.getBytes(StandardCharsets.UTF_8));}
        public void command(byte[] bytes){commands.add(new String(bytes,StandardCharsets.US_ASCII));check(bytes.length>0&&bytes[bytes.length-1]!=0,"commands have no NUL terminator");}
        public void data(byte[] bytes,int offset,int length){check(length<=16384,"USB transfer chunk limit");data.write(bytes,offset,length);}
        public byte[] response(int timeout)throws IOException{if(replies.isEmpty())throw new IOException("fixture response timeout");return replies.remove();}
        public boolean isOpen(){return open;}public void close(){open=false;}
    }
    public static void main(String[] args)throws Exception {
        Fake query=new Fake("INFOreading product","TEXTcontinuing\0","OKAYpixel");List<String> logs=new ArrayList<>();FastbootClient client=new FastbootClient(query);
        check(client.execute("getvar:product",logs::add).value.equals("pixel"),"getvar result");check(logs.equals(List.of("(bootloader) reading product\n","continuing")),"INFO and TEXT framing");check(query.commands.equals(List.of("getvar:product")),"single command packet");
        Fake rejected=new Fake("FAILUnknown variable","OKAYa");client=new FastbootClient(rejected);try{client.variable("unknown");throw new AssertionError("FAIL ignored");}catch(FastbootClient.Rejected expected){check(expected.getMessage().equals("Unknown variable"),"bootloader rejection reaches caller");}check(client.isConnected()&&client.variable("current-slot").equals("a"),"FAIL leaves transport synchronized for another query");
        Fake zero=new Fake("","OKAY0.4");check(new FastbootClient(zero).variable("version").equals("0.4"),"zero-length packets ignored");
        for(String response:List.of("OK","WHATEVER","DATA00000004")){Fake malformed=new Fake(response);try{new FastbootClient(malformed).execute("getvar:version",null);throw new AssertionError("invalid response accepted");}catch(IOException expected){check(!malformed.isOpen(),"uncertain protocol state closes the transport");}}
        Fake invalid=new Fake("OKAY");try{new FastbootClient(invalid).execute("reboot\n",null);throw new AssertionError("control character accepted");}catch(IOException expected){check(invalid.commands.isEmpty(),"invalid commands rejected before USB");}
        byte[] image=new byte[50000];new Random(5).nextBytes(image);Fake transfer=new Fake("DATA0000c350","INFOvalidating","OKAY");List<Long> progress=new ArrayList<>();
        new FastbootClient(transfer).download(new ByteArrayInputStream(image),image.length,null,(n,total)->{check(total==image.length,"transfer total remains exact");progress.add(n);});
        check(transfer.commands.equals(List.of("download:0000c350")),"download length uses eight hexadecimal digits");check(Arrays.equals(image,transfer.data.toByteArray()),"streamed image integrity");check(progress.get(0)==0&&progress.get(progress.size()-1)==50000,"transfer progress bounds");
        Fake mismatch=new Fake("DATA00000002");try{new FastbootClient(mismatch).download(new ByteArrayInputStream(image),50000,null,null);throw new AssertionError("mismatched DATA accepted");}catch(IOException expected){check(mismatch.data.size()==0&&!mismatch.open,"different requested size aborts before transferring bytes");}
        Fake truncated=new Fake("DATA00000005");try{new FastbootClient(truncated).download(new ByteArrayInputStream(new byte[2]),5,null,null);throw new AssertionError("truncated image accepted");}catch(EOFException expected){check(!truncated.open&&!truncated.commands.contains("flash:boot"),"incomplete image cannot proceed to flashing");}
        Fake grown=new Fake("DATA00000002");try{new FastbootClient(grown).download(new ByteArrayInputStream(new byte[3]),2,null,null);throw new AssertionError("changed image size accepted");}catch(IOException expected){check(!grown.open,"changed source closes transport before flash");}
        Path path=Files.createTempFile("adbox-fastboot-image-",".img");Files.write(path,image);
        Fake flash=new Fake("DATA0000c350","OKAY","INFOwriting","OKAY");new FastbootClient(flash).flash("boot_a",path.toFile(),null,null);check(flash.commands.equals(List.of("download:0000c350","flash:boot_a")),"flash happens only after a confirmed download");
        Fake refusal=new Fake("FAILimage too large");try{new FastbootClient(refusal).flash("boot_a",path.toFile(),null,null);throw new AssertionError("download refusal ignored");}catch(FastbootClient.Rejected expected){check(refusal.commands.size()==1&&refusal.data.size()==0,"refused download cannot issue flash");}
        Fake badPartition=new Fake("OKAY");try{new FastbootClient(badPartition).flash("boot_a:userdata",path.toFile(),null,null);throw new AssertionError("invalid partition accepted");}catch(IOException expected){check(badPartition.commands.isEmpty(),"partition validation precedes download");}
        Fake boot=new Fake("DATA0000c350","OKAY","OKAY");new FastbootClient(boot).boot(path.toFile(),null,null);check(boot.commands.get(1).equals("boot"),"temporary boot uses staged image");
        byte[] sparse=ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putInt(0xed26ff3a).putShort((short)1).putShort((short)0).putShort((short)28).putShort((short)12).putInt(4096).putInt(20).putInt(0).putInt(0).array();Files.write(path,sparse);
        check(FastbootClient.isSparse(path.toFile())&&FastbootClient.expandedSize(path.toFile())==81920,"sparse expanded size preflight");Fake sparseBoot=new Fake("OKAY");try{new FastbootClient(sparseBoot).boot(path.toFile(),null,null);throw new AssertionError("sparse boot accepted");}catch(IOException expected){check(sparseBoot.commands.isEmpty(),"sparse image cannot be booted as a raw boot image");}
        check(FastbootClient.byteSize("0x10000000")==268435456L&&FastbootClient.byteSize("1024")==1024L&&FastbootClient.byteSize("unknown")==null,"device buffer sizes parse without invented values");
        Fake timeout=new Fake();try{new FastbootClient(timeout).variable("version");throw new AssertionError("timeout ignored");}catch(IOException expected){check(!timeout.open,"timeout closes an uncertain USB session");}
        byte[] dtbo=new byte[128];new Random(19).nextBytes(dtbo);ByteBuffer.wrap(dtbo).order(ByteOrder.BIG_ENDIAN).putInt(0xd7b7ab1e);Files.write(path,dtbo);
        for(String partition:List.of("dtbo","dtbo_a","dtbo_b")){
            Fake dtboFlash=new Fake("DATA00000080","OKAY","OKAY");
            new FastbootClient(dtboFlash).flash(partition,path.toFile(),null,null);
            check(dtboFlash.commands.equals(List.of("download:00000080","flash:"+partition)),"DTBO flash preserves the exact reviewed partition and waits for download confirmation");
            check(Arrays.equals(dtbo,dtboFlash.data.toByteArray()),"DTBO raw image bytes and header are sent unchanged");
        }
        Files.delete(path);System.out.println("PASS: "+checks+" Fastboot command, IMG/DTBO flash, download, boot, and failure checks.");
    }
}
