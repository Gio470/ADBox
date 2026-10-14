import dev.adbox.toolkit.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Queued USB completion routing, shutdown and long recovery transfer checks. */
public class QueuedUsbChecks {
    static final AtomicInteger checks=new AtomicInteger();
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks.incrementAndGet();}
    static class Driver implements QueuedUsbTransport.Driver {
        final BlockingQueue<QueuedUsbTransport.Completion> completions=new LinkedBlockingQueue<>();
        final BlockingQueue<ByteBuffer> readRequests=new LinkedBlockingQueue<>();
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        final CountDownLatch submitted=new CountDownLatch(1),released=new CountDownLatch(1);
        volatile boolean closed,hold,refuse;volatile int lastLength;volatile String problem="mock endpoint";
        public boolean queue(int direction,ByteBuffer buffer)throws IOException{
            if(closed)throw new IOException("closed mock USB");
            if(direction==QueuedUsbTransport.INPUT){readRequests.offer(buffer);return true;}
            if(refuse)return false;
            int length=buffer.remaining();lastLength=length;byte[] data=new byte[length];buffer.get(data);output.write(data);submitted.countDown();
            if(!hold)completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.OUTPUT,length));
            return true;
        }
        public QueuedUsbTransport.Completion await(int timeout)throws TimeoutException,IOException{
            try{QueuedUsbTransport.Completion c=completions.poll(timeout,TimeUnit.MILLISECONDS);if(c==null)throw new TimeoutException();return c;}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        void input(byte[] data)throws Exception{ByteBuffer b=readRequests.poll(3,TimeUnit.SECONDS);if(b==null)throw new AssertionError("no USB IN request");b.put(data);completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.INPUT,data.length));}
        public String describe(){return problem;}
        public void close(){closed=true;completions.offer(new QueuedUsbTransport.Completion(0,0));}
        public void release(){released.countDown();}
    }
    static class PipeDriver extends Driver {
        final PipedInputStream targetIn=new PipedInputStream(131072),hostIn=new PipedInputStream(131072);
        final PipedOutputStream targetOut=new PipedOutputStream(hostIn),hostOut=new PipedOutputStream(targetIn);
        final ByteArrayOutputStream pendingBytes=new ByteArrayOutputStream();
        final AtomicInteger zeroPackets=new AtomicInteger();
        PipeDriver()throws IOException{
            Thread input=new Thread(()->{
                try{while(!closed){ByteBuffer b=readRequests.take();byte[] data=new byte[b.remaining()];int n=hostIn.read(data);if(n<0)throw new EOFException("recovery USB ended");b.put(data,0,n);completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.INPUT,n));}}
                catch(Exception e){if(!closed){problem=e.getMessage();completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.INPUT,-1));}}
            },"Queued-USB-mock-IN");input.setDaemon(true);input.start();
        }
        public boolean queue(int direction,ByteBuffer buffer)throws IOException{
            if(direction==QueuedUsbTransport.INPUT)return super.queue(direction,buffer);
            if(closed)throw new IOException("closed mock USB");
            int length=buffer.remaining();lastLength=length;byte[] data=new byte[length];buffer.get(data);pendingBytes.write(data);
            if(length==0)zeroPackets.incrementAndGet();
            if(length==0||length%512!=0||pendingBytes.size()>=16384){pendingBytes.writeTo(hostOut);hostOut.flush();pendingBytes.reset();}
            completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.OUTPUT,length));return true;
        }
        public void close(){super.close();try{hostIn.close();hostOut.close();targetIn.close();targetOut.close();}catch(IOException ignored){}readRequests.offer(ByteBuffer.allocate(0));}
    }
    static void endToEnd(AdbIdentity identity,File file,int maxPayload)throws Exception{
        PipeDriver driver=new PipeDriver();QueuedUsbTransport usb=new QueuedUsbTransport(driver,512,30000);
        CompletableFuture<Void> peer=new CompletableFuture<>();AtomicInteger frames=new AtomicInteger();
        Thread recovery=new Thread(()->{
            try{
                UsbChecks.Frame connect=new UsbChecks.Frame(driver.targetIn);
                if(connect.remote!=65536)throw new AssertionError("host does not advertise 64 KiB payloads");
                UsbChecks.frame(driver.targetOut,"CNXN",0x01000000,maxPayload,UsbChecks.ascii("sideload::\0"));
                UsbChecks.Frame open=new UsbChecks.Frame(driver.targetIn);int local=open.local,remote=53;
                UsbChecks.frame(driver.targetOut,"OKAY",remote,local,new byte[0]);
                try(RandomAccessFile original=new RandomAccessFile(file,"r")){
                    int blocks=(int)((file.length()+65535)/65536);
                    for(int step=0;step<blocks+1;step++){
                        int block=step==blocks?0:step;
                        UsbChecks.frame(driver.targetOut,"WRTE",remote,local,UsbChecks.ascii(String.format(Locale.ROOT,"%08d",block)));
                        int count=(int)Math.min(65536,file.length()-(long)block*65536);byte[] expected=new byte[count];original.seek((long)block*65536);original.readFully(expected);
                        ByteArrayOutputStream received=new ByteArrayOutputStream();
                        while(received.size()<count){UsbChecks.Frame frame=new UsbChecks.Frame(driver.targetIn);if(frame.command==AdbWireConnection.code("OKAY"))continue;if(frame.command!=AdbWireConnection.code("WRTE")||frame.data.length>maxPayload)throw new AssertionError("bad negotiated recovery frame");frames.incrementAndGet();received.write(frame.data);UsbChecks.frame(driver.targetOut,"OKAY",remote,local,new byte[0]);}
                        if(!Arrays.equals(expected,received.toByteArray()))throw new AssertionError("queued recovery update bytes changed");
                    }
                }
                UsbChecks.frame(driver.targetOut,"WRTE",remote,local,UsbChecks.ascii("DONEDONE"));
                while(new UsbChecks.Frame(driver.targetIn).command!=AdbWireConnection.code("CLSE")){}
                peer.complete(null);
            }catch(Throwable e){peer.completeExceptionally(e);}
        },"Queued-recovery-peer");recovery.setDaemon(true);recovery.start();
        try(AdbWireConnection wire=new AdbWireConnection(usb.input,usb.output,usb,identity)){
            wire.connect();long sent=SideloadClient.serve(wire.open(SideloadClient.service(file.length())),file,(unique,bytes,total)->{});
            peer.get(10,TimeUnit.SECONDS);
            check(sent==file.length()+Math.min(65536,file.length()),"queued recovery transfer preserves exact full/tail/repeated block bytes");
            int blocks=(int)(file.length()/65536);int tail=(int)(file.length()%65536);
            int expected=(blocks+1)*(65536/maxPayload)+(tail>0?(tail+maxPayload-1)/maxPayload:0);
            check(frames.get()==expected,"modern 64 KiB negotiation reduces acknowledgements while 4 KiB peers keep their limit");
        }
        check(driver.released.await(3,TimeUnit.SECONDS),"queued USB request resources release after completion reader exits");
    }
    public static void main(String[] args)throws Exception{
        ExecutorService workers=Executors.newCachedThreadPool();
        try{
            Driver both=new Driver();both.hold=true;
            try(QueuedUsbTransport usb=new QueuedUsbTransport(both,512,3000)){
                Future<?> send=workers.submit(()->{try{usb.output.write(new byte[]{7,8,9});}catch(IOException e){throw new UncheckedIOException(e);}});
                check(both.submitted.await(3,TimeUnit.SECONDS),"USB OUT queued before waiting for completion");
                both.input(new byte[]{1,2,3,4});check(Arrays.equals(usb.input.readNBytes(4),new byte[]{1,2,3,4}),"USB IN completes while an OUT request is pending");check(!send.isDone(),"IN completion cannot incorrectly finish an OUT write");
                both.completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.OUTPUT,3));send.get(3,TimeUnit.SECONDS);check(Arrays.equals(both.output.toByteArray(),new byte[]{7,8,9}),"OUT completes with its own exact byte count");
                both.input(new byte[0]);both.input(new byte[]{5,6});check(Arrays.equals(usb.input.readNBytes(2),new byte[]{5,6}),"input zero-length packets are ignored and the IN request is requeued");
            }
            check(both.released.await(3,TimeUnit.SECONDS),"normal close waits for request reader to release resources");
            Driver disconnect=new Driver();disconnect.hold=true;QueuedUsbTransport usb=new QueuedUsbTransport(disconnect,512,3000);
            Future<?> write=workers.submit(()->{try{usb.output.write(new byte[24]);throw new AssertionError("closed write succeeded");}catch(IOException expected){check(expected.getMessage().contains("USB disconnected"),"disconnect releases pending writer");}});
            disconnect.submitted.await(3,TimeUnit.SECONDS);Future<?> read=workers.submit(()->{try{check(usb.input.read()==-1,"normal close releases waiting reader with EOF");}catch(IOException e){throw new UncheckedIOException(e);}});usb.close();write.get(3,TimeUnit.SECONDS);read.get(3,TimeUnit.SECONDS);
            Driver failed=new Driver();failed.hold=true;failed.problem="attached=true; OUT=0x1";QueuedUsbTransport broken=new QueuedUsbTransport(failed,512,3000);
            Future<?> fail=workers.submit(()->{try{broken.output.write(new byte[24]);throw new AssertionError("zero-byte completion accepted");}catch(IOException expected){check(expected.getMessage().contains("0 / 24")&&expected.getMessage().contains("attached=true"),"zero-byte OUT failure keeps size and attachment diagnostics");}});
            failed.submitted.await(3,TimeUnit.SECONDS);failed.completions.offer(new QueuedUsbTransport.Completion(QueuedUsbTransport.OUTPUT,0));fail.get(3,TimeUnit.SECONDS);
            // The writer wakes before the completion reader finishes driver.close/release.
            // Await cleanup explicitly rather than racing its next instruction.
            check(failed.released.await(3,TimeUnit.SECONDS),"completion failure releases request resources");
            check(failed.closed,"completion failure closes transport");
            Driver timeout=new Driver();timeout.hold=true;
            try(QueuedUsbTransport waiting=new QueuedUsbTransport(timeout,512,50)){try{waiting.output.write(new byte[24]);throw new AssertionError("missing completion accepted");}catch(IOException expected){check(expected.getMessage().contains("50 ms")&&timeout.closed,"completion deadline aborts without replaying the pending request");}}
            Driver refused=new Driver();refused.refuse=true;
            try(QueuedUsbTransport rejected=new QueuedUsbTransport(refused,512,3000)){try{rejected.output.write(new byte[24]);throw new AssertionError("rejected queue accepted");}catch(IOException expected){check(expected.getMessage().contains("Cannot queue USB OUT")&&refused.closed,"queue rejection remains distinct from completed failure");}}
            Path dir=Files.createTempDirectory("adbox-queued-usb-");
            try{
                AdbIdentity identity=AdbIdentity.loadOrCreate(dir.resolve("identity").toFile());File small=dir.resolve("small.zip").toFile();byte[] block=new byte[65536];new Random(29).nextBytes(block);Files.write(small.toPath(),Arrays.copyOf(block,65536));try(OutputStream output=new FileOutputStream(small,true)){output.write(new byte[7]);}
                endToEnd(identity,small,4096);endToEnd(identity,small,65536);
                File large=dir.resolve("long.zip").toFile();try(OutputStream output=new BufferedOutputStream(new FileOutputStream(large))){for(int i=0;i<2048;i++)output.write(block);output.write(new byte[7]);}
                endToEnd(identity,large,65536);check(large.length()>111L*1024*1024,"long queued recovery fixture crosses the earlier 111 MB failure point");
            }finally{try(var files=Files.walk(dir)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
        }finally{workers.shutdownNow();}
        System.out.println("PASS: "+checks.get()+" queued USB dispatch, shutdown and long sideload checks.");
    }
}
