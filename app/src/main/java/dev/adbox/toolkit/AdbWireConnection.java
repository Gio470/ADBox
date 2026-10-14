package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** ADB transport and multiplexed streams. Target code never executes locally. */
public final class AdbWireConnection implements Closeable {
    private static final int CNXN=code("CNXN"), AUTH=code("AUTH"), OPEN=code("OPEN"), OKAY=code("OKAY"), WRTE=code("WRTE"), CLSE=code("CLSE");
    private final InputStream input; private final OutputStream output; private final Closeable transport; private final AdbIdentity identity;
    private final AtomicBoolean closed=new AtomicBoolean(); private final AtomicInteger ids=new AtomicInteger(1);
    private final ConcurrentMap<Integer,Stream> streams=new ConcurrentHashMap<>();
    private final CompletableFuture<String> connected=new CompletableFuture<>();
    private final AtomicReference<IOException> failure=new AtomicReference<>();
    private static final int HOST_MAX_DATA=65536;
    private volatile int maxData=4096; private int authCount;
    public AdbWireConnection(InputStream in,OutputStream out,Closeable transport,AdbIdentity identity){this.input=in;this.output=out;this.transport=transport;this.identity=identity;}
    public String connect() throws Exception {
        Thread reader=new Thread(this::readLoop,"ADBox-USB-ADB");reader.setDaemon(true);reader.start();
        send(CNXN,0x01000000,HOST_MAX_DATA,"host::\0".getBytes(StandardCharsets.UTF_8));
        try{return connected.get(40,TimeUnit.SECONDS);}catch(Exception e){close();throw new IOException("ADB connection failed. Enable USB debugging and approve ADBox on the target device.",e);}
    }
    public boolean isConnected(){return !closed.get()&&connected.isDone()&&!connected.isCompletedExceptionally();}
    public AdbChannel open(String service) throws Exception {return open(service,false);}
    /** Video consumers defer ACK until read, bounding incoming data without blocking other streams. */
    public AdbChannel open(String service,boolean deferredAcks) throws Exception {
        if(!isConnected())throw new IOException("Connect a device first");
        if(service.indexOf('\0')>=0)throw new IOException("Invalid ADB service");
        Stream stream=new Stream(ids.getAndIncrement(),deferredAcks);streams.put(stream.id,stream);
        try{send(OPEN,stream.id,0,(service+"\0").getBytes(StandardCharsets.UTF_8));stream.ready.get(20,TimeUnit.SECONDS);return stream;}
        catch(Exception e){stream.close();throw new IOException("The device did not open the ADB service: "+service,e);}
    }
    private void readLoop(){
        try{while(!closed.get()){
            byte[] header=readFully(input,24);ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cmd=h.getInt(),a=h.getInt(),b=h.getInt(),length=h.getInt(),checksum=h.getInt(),magic=h.getInt();
            if(magic!=(cmd^0xffffffff)||length<0||length>1024*1024)throw new IOException("Invalid ADB frame");
            byte[] bytes=readFully(input,length);if(checksum!=sum(bytes))throw new IOException("ADB checksum mismatch");
            if(cmd==CNXN){maxData=Math.max(1,Math.min(HOST_MAX_DATA,b));connected.complete(new String(bytes,StandardCharsets.UTF_8).replace("\0",""));}
            else if(cmd==AUTH&&a==1){if(authCount++==0)send(AUTH,2,0,identity.signToken(bytes));else send(AUTH,3,0,identity.adbPublicKey());}
            else if(cmd==OKAY){Stream s=streams.get(b);if(s!=null){s.remote=a;s.ready.complete(null);if(s.writePermit.availablePermits()==0)s.writePermit.release();}}
            else if(cmd==WRTE){Stream s=streams.get(b);if(s!=null){s.remote=a;s.ready.complete(null);if(!s.received.offer(bytes)){s.close();continue;}if(!s.deferredAcks||bytes.length==0)send(OKAY,b,a,new byte[0]);}else send(CLSE,b,a,new byte[0]);}
            else if(cmd==CLSE){Stream s=streams.remove(b);if(s!=null){s.remote=a;s.ready.completeExceptionally(new IOException("Device rejected or closed the service"));s.end();send(CLSE,b,a,new byte[0]);}}
        }}catch(Exception e){if(!closed.get())abort(e instanceof IOException?(IOException)e:new IOException("ADB reader failed",e));}
    }
    private synchronized void send(int command,int a,int b,byte[] bytes) throws IOException {
        if(closed.get())throw failure.get()!=null?failure.get():new IOException("ADB connection closed");
        ByteBuffer h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);h.putInt(command).putInt(a).putInt(b).putInt(bytes.length).putInt(sum(bytes)).putInt(command^0xffffffff);
        try{output.write(h.array());if(bytes.length>0)output.write(bytes);output.flush();}
        catch(IOException e){abort(e);throw failure.get();}
    }
    // After a failed USB write the peer may hold half an ADB frame. Close the
    // transport, rather than appending CLSE or another command to that frame.
    private void abort(IOException cause){failure.compareAndSet(null,cause);try{close();}catch(IOException e){cause.addSuppressed(e);}}
    @Override public void close() throws IOException {
        if(closed.compareAndSet(false,true)){connected.completeExceptionally(new IOException("Disconnected"));for(Stream s:streams.values())s.end();streams.clear();transport.close();}
    }
    public static int code(String code){return ByteBuffer.wrap(code.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    public static int sum(byte[] bytes){int sum=0;for(byte b:bytes)sum+=b&255;return sum;}
    public static byte[] readFully(InputStream in,int length) throws IOException {byte[] bytes=new byte[length];int offset=0;while(offset<length){int n=in.read(bytes,offset,length-offset);if(n<0)throw new EOFException("Unexpected end of ADB data");if(n==0)continue;offset+=n;}return bytes;}
    private final class Stream implements AdbChannel {
        final int id;volatile int remote;volatile boolean ended;final CompletableFuture<Void> ready=new CompletableFuture<>();
        final BlockingQueue<byte[]> received;final boolean deferredAcks;final Semaphore writePermit=new Semaphore(0);
        private byte[] current=new byte[0];private int offset;
        Stream(int id,boolean deferredAcks){this.id=id;this.deferredAcks=deferredAcks;received=new LinkedBlockingQueue<>(deferredAcks?2:Integer.MAX_VALUE);}
        final InputStream in=new InputStream(){
            @Override public int read() throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
            @Override public int read(byte[] bytes,int start,int length) throws IOException {
                if(length==0)return 0;
                while(offset==current.length){if(ended&&received.isEmpty()){if(failure.get()!=null)throw failure.get();return -1;}try{current=received.take();offset=0;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("ADB read interrupted",e);}if(current.length==0&&ended){if(failure.get()!=null)throw failure.get();return -1;}}
                int n=Math.min(length,current.length-offset);System.arraycopy(current,offset,bytes,start,n);offset+=n;if(deferredAcks&&offset==current.length&&!ended&&!closed.get())send(OKAY,id,remote,new byte[0]);return n;
            }
        };
        final OutputStream out=new OutputStream(){
            @Override public void write(int b) throws IOException {write(new byte[]{(byte)b});}
            @Override public void write(byte[] bytes,int start,int length) throws IOException {
                while(length>0){try{if(!writePermit.tryAcquire(20,TimeUnit.SECONDS))throw new IOException("ADB write timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("ADB write interrupted",e);}if(ended)throw failure.get()!=null?failure.get():new IOException("ADB stream closed");int n=Math.min(length,maxData);send(WRTE,id,remote,Arrays.copyOfRange(bytes,start,start+n));start+=n;length-=n;}
            }
        };
        void end(){ended=true;ready.completeExceptionally(new IOException("Disconnected"));received.offer(new byte[0]);writePermit.release();}
        @Override public InputStream input(){return in;}
        @Override public OutputStream output(){return out;}
        @Override public void close() throws IOException {if(!ended){streams.remove(id);end();if(remote!=0&&!closed.get())send(CLSE,id,remote,new byte[0]);}}
    }
}
