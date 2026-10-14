package dev.adbox.toolkit;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One completion reader dispatches queued USB IN and OUT requests to their owners. */
public final class QueuedUsbTransport implements Closeable {
    public static final int INPUT=1,OUTPUT=2,BUFFER_SIZE=16384;
    public interface Driver extends Closeable {
        boolean queue(int direction,ByteBuffer buffer)throws IOException;
        Completion await(int timeoutMillis)throws IOException,TimeoutException;
        String describe();
        default void release()throws IOException{}
    }
    public static final class Completion {
        public final int direction,count;
        public Completion(int direction,int count){this.direction=direction;this.count=count;}
    }
    private final Driver driver;
    private final int writeTimeoutMillis;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<IOException> failure=new AtomicReference<>();
    private final BlockingQueue<byte[]> incoming=new LinkedBlockingQueue<>();
    private final ByteBuffer inputBuffer=ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final ByteBuffer outputBuffer=ByteBuffer.allocateDirect(BUFFER_SIZE);
    private final AtomicReference<CompletableFuture<Integer>> pending=new AtomicReference<>();
    private volatile int outputLength;
    private final Object writer=new Object();
    private byte[] current=new byte[0];private int offset;
    public final InputStream input=new InputStream(){
        public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
        public int read(byte[] bytes,int start,int length)throws IOException{
            if(start<0||length<0||start>bytes.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return 0;
            while(offset==current.length){
                if(closed.get()&&incoming.isEmpty()){if(failure.get()!=null)throw failure.get();return -1;}
                try{current=incoming.take();offset=0;}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Queued USB read interrupted",e);}
                if(current.length==0&&closed.get()){if(failure.get()!=null)throw failure.get();return -1;}
            }
            int n=Math.min(length,current.length-offset);System.arraycopy(current,offset,bytes,start,n);offset+=n;return n;
        }
    };
    public final OutputStream output;
    public QueuedUsbTransport(Driver driver,int packetSize,int writeTimeoutMillis)throws IOException{
        this.driver=Objects.requireNonNull(driver);this.writeTimeoutMillis=writeTimeoutMillis;
        if(writeTimeoutMillis<=0)throw new IllegalArgumentException("Invalid USB write timeout");
        output=new UsbAdbOutputStream(packetSize,this::write);
        try{if(!driver.queue(INPUT,inputBuffer))throw new IOException("Cannot queue USB IN. "+driver.describe());}
        catch(IOException e){fail(e);try{driver.release();}catch(IOException cleanup){e.addSuppressed(cleanup);}throw e;}
        Thread pump=new Thread(this::complete,"ADBox-USB-completions");pump.setDaemon(true);pump.start();
    }
    private void complete(){
        int emptyReads=0;
        try{while(!closed.get()){
            Completion completion;
            try{completion=driver.await(1000);}catch(TimeoutException e){continue;}
            if(closed.get())return;
            if(completion==null)throw new IOException("USB completion failed. "+driver.describe());
            if(completion.direction==INPUT){
                if(completion.count<0||completion.count>BUFFER_SIZE)throw new IOException("Invalid queued USB IN length "+completion.count);
                if(completion.count>0){
                    emptyReads=0;inputBuffer.flip();byte[] data=new byte[completion.count];inputBuffer.get(data);incoming.offer(data);
                }else if(++emptyReads>=64)throw new IOException("USB IN repeatedly completed without data. "+driver.describe());
                inputBuffer.clear();
                if(!driver.queue(INPUT,inputBuffer))throw new IOException("Cannot requeue USB IN. "+driver.describe());
            }else if(completion.direction==OUTPUT){
                CompletableFuture<Integer> future=pending.get();
                if(future==null)throw new IOException("Unexpected USB OUT completion");
                if(completion.count<0||completion.count>outputLength||(outputLength>0&&completion.count==0))
                    throw new IOException("Queued USB OUT completed "+completion.count+" / "+outputLength+" B. "+driver.describe());
                future.complete(completion.count);
            }else throw new IOException("Unknown USB completion direction");
        }}catch(Exception e){if(!closed.get())fail(e instanceof IOException?(IOException)e:new IOException("Queued USB completion failed: "+e.getMessage(),e));}
        finally{try{driver.release();}catch(IOException ignored){}}
    }
    private int write(byte[] bytes,int start,int length)throws IOException{
        synchronized(writer){
            ensureOpen();outputBuffer.clear();outputBuffer.put(bytes,start,length);outputBuffer.flip();outputLength=length;
            CompletableFuture<Integer> future=new CompletableFuture<>();pending.set(future);
            try{
                if(!driver.queue(OUTPUT,outputBuffer))throw new IOException("Cannot queue USB OUT ("+length+" B). "+driver.describe());
                return future.get(writeTimeoutMillis,TimeUnit.MILLISECONDS);
            }catch(InterruptedException e){Thread.currentThread().interrupt();IOException error=new IOException("Queued USB write interrupted",e);fail(error);throw error;}
            catch(TimeoutException e){IOException error=new IOException("Queued USB OUT did not complete in "+writeTimeoutMillis+" ms ("+length+" B). "+driver.describe(),e);fail(error);throw error;}
            catch(ExecutionException e){Throwable cause=e.getCause();throw cause instanceof IOException?(IOException)cause:new IOException("Queued USB OUT failed",cause);}
            catch(IOException e){fail(e);throw failure.get();}
            finally{pending.compareAndSet(future,null);}
        }
    }
    private void ensureOpen()throws IOException{if(closed.get())throw failure.get()!=null?failure.get():new IOException("USB transport closed");}
    private void fail(IOException error){failure.compareAndSet(null,error);try{close();}catch(IOException e){error.addSuppressed(e);}}
    public void close()throws IOException{
        if(closed.compareAndSet(false,true)){
            IOException error=failure.get()!=null?failure.get():new IOException("USB disconnected");
            CompletableFuture<Integer> future=pending.get();if(future!=null)future.completeExceptionally(error);
            incoming.offer(new byte[0]);driver.close();
        }
    }
}
