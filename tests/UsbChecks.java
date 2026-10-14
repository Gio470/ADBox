import dev.adbox.toolkit.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises USB short-packet completion beneath the real ADB and sideload clients. */
public class UsbChecks {
    static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static byte[] ascii(String text){return text.getBytes(StandardCharsets.US_ASCII);}
    static final class Frame {
        final int command,local,remote;final byte[] data;
        Frame(InputStream input)throws IOException{
            ByteBuffer h=ByteBuffer.wrap(AdbWireConnection.readFully(input,24)).order(ByteOrder.LITTLE_ENDIAN);
            command=h.getInt();local=h.getInt();remote=h.getInt();int length=h.getInt(),sum=h.getInt(),magic=h.getInt();
            if(magic!=(command^0xffffffff)||length<0||length>65536)throw new IOException("bad host frame");
            data=AdbWireConnection.readFully(input,length);
            if(AdbWireConnection.sum(data)!=sum)throw new IOException("bad host checksum");
        }
    }
    static void frame(OutputStream output,String command,int local,int remote,byte[] bytes)throws IOException{
        int code=AdbWireConnection.code(command);
        output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(code).putInt(local).putInt(remote).putInt(bytes.length).putInt(AdbWireConnection.sum(bytes)).putInt(code^0xffffffff).array());output.write(bytes);output.flush();
    }
    static final class Recovery implements AutoCloseable {
        final PipedInputStream hostIn=new PipedInputStream(131072),deviceIn=new PipedInputStream(131072);
        final PipedOutputStream deviceOut=new PipedOutputStream(hostIn),hostOut=new PipedOutputStream(deviceIn);
        final ByteArrayOutputStream pending=new ByteArrayOutputStream(),received=new ByteArrayOutputStream();
        final AtomicInteger zeros=new AtomicInteger(),completeBlocks=new AtomicInteger(),submissions=new AtomicInteger();
        final AtomicBoolean usbFailed=new AtomicBoolean();
        final CountDownLatch fullPayload=new CountDownLatch(1);
        final CompletableFuture<Void> peer=new CompletableFuture<>();
        final CompletableFuture<Long> transfer=new CompletableFuture<>();
        final AdbWireConnection wire;
        Recovery(AdbIdentity identity,File update,boolean terminate)throws Exception{this(identity,update,terminate,false,false);}
        Recovery(AdbIdentity identity,File update,boolean terminate,boolean partial,boolean fail)throws Exception{
            UsbAdbOutputStream.Transfer usb=(bytes,offset,length)->{
                submissions.incrementAndGet();
                if(usbFailed.get())throw new AssertionError("USB reused after ambiguous failure");
                if(fail&&length==3584){usbFailed.set(true);return -1;}
                int actual=fail&&length==4096?512:partial&&length>=1024?1024:partial&&length==24?8:length;
                if(actual==0)zeros.incrementAndGet();
                pending.write(bytes,offset,actual);
                // Modern recovery submits 16 KiB FunctionFS reads. A short packet or
                // ZLP completes a smaller read; a full 4 KiB payload alone does not.
                if(actual==0||actual%512!=0||pending.size()>=16384){pending.writeTo(hostOut);hostOut.flush();pending.reset();}
                if(length==4096)fullPayload.countDown();
                return actual;
            };
            OutputStream output=terminate?new UsbAdbOutputStream(512,usb):new OutputStream(){
                public void write(int value)throws IOException{write(new byte[]{(byte)value});}
                public void write(byte[] bytes,int offset,int length)throws IOException{usb.write(bytes,offset,length);}
            };
            wire=new AdbWireConnection(hostIn,output,()->{hostIn.close();hostOut.close();deviceIn.close();deviceOut.close();},identity);
            Thread daemon=new Thread(()->{
                try{
                    Frame connect=new Frame(deviceIn);if(connect.command!=AdbWireConnection.code("CNXN"))throw new IOException("missing connection handshake");
                    frame(deviceOut,"CNXN",0x01000000,4096,ascii("sideload::\0"));
                    Frame open=new Frame(deviceIn);
                    if(open.command!=AdbWireConnection.code("OPEN")||!Arrays.equals(open.data,ascii(SideloadClient.service(update.length())+"\0")))throw new IOException("wrong sideload service");
                    int local=open.local,remote=41;frame(deviceOut,"OKAY",remote,local,new byte[0]);
                    byte[] original=Files.readAllBytes(update.toPath());
                    for(int block:new int[]{0,1,0}){
                        frame(deviceOut,"WRTE",remote,local,ascii(String.format(Locale.ROOT,"%08d",block)));
                        int offset=block*SideloadClient.BLOCK_SIZE,count=Math.min(SideloadClient.BLOCK_SIZE,original.length-offset);
                        ByteArrayOutputStream blockBytes=new ByteArrayOutputStream();
                        while(blockBytes.size()<count){
                            Frame packet=new Frame(deviceIn);
                            if(packet.command==AdbWireConnection.code("OKAY"))continue;
                            if(packet.command!=AdbWireConnection.code("WRTE")||packet.local!=local||packet.remote!=remote)throw new IOException("unexpected sideload frame");
                            blockBytes.write(packet.data);frame(deviceOut,"OKAY",remote,local,new byte[0]);
                        }
                        if(!Arrays.equals(blockBytes.toByteArray(),Arrays.copyOfRange(original,offset,offset+count)))throw new IOException("update bytes changed");
                        received.write(blockBytes.toByteArray());completeBlocks.incrementAndGet();
                    }
                    frame(deviceOut,"WRTE",remote,local,ascii("DONEDONE"));
                    while(new Frame(deviceIn).command!=AdbWireConnection.code("CLSE")){}
                    peer.complete(null);
                }catch(Throwable e){peer.completeExceptionally(e);}
            },"Recovery-USB-fixture");daemon.setDaemon(true);daemon.start();
            wire.connect();
            Thread sender=new Thread(()->{
                try{transfer.complete(SideloadClient.serve(wire.open(SideloadClient.service(update.length())),update,(unique,sent,total)->{}));}
                catch(Throwable e){transfer.completeExceptionally(e);}
            },"Sideload-USB-fixture");sender.setDaemon(true);sender.start();
        }
        public void close()throws IOException{wire.close();}
    }
    public static void main(String[] args)throws Exception{
        for(int packet:new int[]{64,512,1024})for(int length:new int[]{1,24,63,64,511,512,1023,1024,4096,16384,16385,32768}){
            byte[] data=new byte[length+6];new Random(length).nextBytes(data);List<Integer> lengths=new ArrayList<>();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            UsbAdbOutputStream output=new UsbAdbOutputStream(packet,(b,o,n)->{lengths.add(n);bytes.write(b,o,n);return n;});
            output.write(data,3,length);
            check(Arrays.equals(bytes.toByteArray(),Arrays.copyOfRange(data,3,3+length)),"USB preserves exact data, offset and length");
            long zeros=lengths.stream().filter(n->n==0).count();
            check(zeros==(length%packet==0?1:0)&&lengths.stream().allMatch(n->n<=16384),"USB completion handles full/high/super-speed packets and bounded transfers");
        }
        AtomicInteger calls=new AtomicInteger();new UsbAdbOutputStream(512,(b,o,n)->{calls.incrementAndGet();return n;}).write(new byte[0]);check(calls.get()==0,"an empty Java write does not add a spurious USB packet");
        for(int result:new int[]{-1,0,4097}){
            calls.set(0);try{new UsbAdbOutputStream(512,(b,o,n)->{calls.incrementAndGet();return result;}).write(new byte[4096]);throw new AssertionError("incomplete USB write accepted");}
            catch(IOException expected){check(calls.get()==1,"zero/negative/invalid USB results stop without replaying bytes");}
        }
        try{new UsbAdbOutputStream(512,(b,o,n)->n==0?-1:n).write(new byte[4096]);throw new AssertionError("failed terminator accepted");}
        catch(IOException expected){check(expected.getMessage().contains("termination"),"failed USB terminator is actionable");}
        for(int[] fragments:new int[][]{{7,17},{512,3584},{3000,1096},{1024,1024,2048},{4095,1},{15000,16000,1768}}){
            int length=Arrays.stream(fragments).sum();byte[] data=new byte[length+9];new Random(length).nextBytes(data);
            ByteArrayOutputStream delivered=new ByteArrayOutputStream();AtomicInteger partIndex=new AtomicInteger(),terminators=new AtomicInteger();
            UsbAdbOutputStream output=new UsbAdbOutputStream(512,(b,o,n)->{
                if(n==0){terminators.incrementAndGet();return 0;}
                int take=fragments[partIndex.getAndIncrement()];if(take>n)throw new AssertionError("invalid fixture fragment");delivered.write(b,o,take);return take;
            });output.write(data,4,length);
            check(Arrays.equals(delivered.toByteArray(),Arrays.copyOfRange(data,4,4+length)),"positive partial writes continue at the exact unsent offset");
            check(terminators.get()==(fragments[fragments.length-1]%512==0?1:0),"final physical packet determines ZLP; logical aligned lengths do not add spurious ZLP");
        }
        for(int bad:new int[]{0,-1}){
            ByteArrayOutputStream accepted=new ByteArrayOutputStream();AtomicInteger attempt=new AtomicInteger();
            try{new UsbAdbOutputStream(512,(b,o,n)->{if(attempt.getAndIncrement()==0){accepted.write(b,o,512);return 512;}return bad;}).write(new byte[4096]);throw new AssertionError("ambiguous suffix accepted");}
            catch(IOException expected){check(attempt.get()==2&&accepted.size()==512&&expected.getMessage().contains("512 / 4096"),"failure after partial progress reports exact offset and never replays it");}
        }
        Path dir=Files.createTempDirectory("adbox-usb-regression-");
        try{
            AdbIdentity identity=AdbIdentity.loadOrCreate(dir.resolve("identity").toFile());File update=dir.resolve("update.zip").toFile();byte[] data=new byte[65536+7];new Random(3).nextBytes(data);Files.write(update.toPath(),data);
            Recovery legacy=new Recovery(identity,update,false);
            try{
                check(legacy.fullPayload.await(3,TimeUnit.SECONDS),"legacy sender reaches the first aligned payload");
                try{legacy.transfer.get(500,TimeUnit.MILLISECONDS);throw new AssertionError("missing ZLP unexpectedly completed sideload");}
                catch(TimeoutException expected){check(legacy.completeBlocks.get()==0,"old USB path stalls before its first sideload block completes");}
            }finally{legacy.close();}
            try{legacy.transfer.get(3,TimeUnit.SECONDS);throw new AssertionError("disconnected legacy transfer succeeded");}
            catch(ExecutionException expected){check(expected.getCause() instanceof IOException,"disconnect unblocks stalled writer with an error");}
            try(Recovery fixed=new Recovery(identity,update,true)){
                check(fixed.transfer.get(5,TimeUnit.SECONDS)==data.length+65536L,"correct USB framing serves a full block, partial tail and repeated block");
                fixed.peer.get(3,TimeUnit.SECONDS);
                check(fixed.completeBlocks.get()==3&&fixed.zeros.get()==32,"recovery acknowledges all 32 aligned ADB payloads without stalling");
                ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(data);expected.write(data,0,65536);
                check(Arrays.equals(expected.toByteArray(),fixed.received.toByteArray()),"end-to-end sideload over USB preserves all requested update bytes");
            }
            try(Recovery partial=new Recovery(identity,update,true,true,false)){
                check(partial.transfer.get(5,TimeUnit.SECONDS)==data.length+65536L,"sideload survives repeated positive partial header and payload writes");
                partial.peer.get(3,TimeUnit.SECONDS);
                ByteArrayOutputStream expected=new ByteArrayOutputStream();expected.write(data);expected.write(data,0,65536);
                check(Arrays.equals(expected.toByteArray(),partial.received.toByteArray())&&partial.completeBlocks.get()==3,"fragmented end-to-end USB stream remains byte-for-byte valid");
                check(partial.zeros.get()==32,"fragmented payloads still end with correct USB terminators");
            }
            try(Recovery failed=new Recovery(identity,update,true,false,true)){
                try{failed.transfer.get(5,TimeUnit.SECONDS);throw new AssertionError("ambiguous USB transfer completed");}
                catch(ExecutionException expected){check(expected.getCause() instanceof IOException&&expected.getCause().getMessage().contains("Sideload block 0")&&expected.getCause().getMessage().contains("512 / 4096"),"sideload preserves block and USB suffix failure diagnostics");}
                check(!failed.wire.isConnected(),"failed ADB frame closes the whole USB transport");
                int before=failed.submissions.get();
                try{failed.wire.open("shell:echo test");throw new AssertionError("failed connection reused");}catch(IOException expected){check(failed.submissions.get()==before,"neither CLSE nor another command is appended after ambiguous frame failure");}
            }
        }finally{try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
        System.out.println("PASS: "+checks+" USB packet-boundary and end-to-end recovery sideload regression checks.");
    }
}
