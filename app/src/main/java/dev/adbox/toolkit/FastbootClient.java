package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Synchronous, packet-oriented USB Fastboot, as specified by AOSP. */
public final class FastbootClient implements Closeable {
    public interface Transport extends Closeable {
        void command(byte[] bytes) throws IOException;
        void data(byte[] bytes,int offset,int length) throws IOException;
        byte[] response(int timeoutMillis) throws IOException;
        default int readData(byte[] bytes,int offset,int length,int timeoutMillis)throws IOException{throw new IOException("Fastboot readback is unavailable on this transport");}
        boolean isOpen();
    }
    public interface Progress { void transferred(long bytes,long total); }
    public static final class Rejected extends IOException { public Rejected(String reason){super(reason.isEmpty()?"The bootloader rejected the command":reason);} }
    public static final class Reply {
        public final String value;
        public final List<String> messages;
        Reply(String value,List<String> messages){this.value=value;this.messages=Collections.unmodifiableList(new ArrayList<>(messages));}
    }
    private static final class Response {final String type,payload;Response(String type,String payload){this.type=type;this.payload=payload;}}
    private final Transport transport;
    public FastbootClient(Transport transport){this.transport=Objects.requireNonNull(transport);}
    public boolean isConnected(){return transport.isOpen();}
    @Override public void close()throws IOException{transport.close();}
    private static byte[] encode(String command)throws IOException{
        if(command.isEmpty()||command.length()>4096)throw new IOException("Fastboot commands must contain 1–4096 ASCII characters");
        for(int i=0;i<command.length();i++)if(command.charAt(i)<32||command.charAt(i)>126)throw new IOException("Fastboot commands cannot contain control characters or non-ASCII text");
        return command.getBytes(StandardCharsets.US_ASCII);
    }
    private Response receive(List<String> messages,Consumer<String> log,int timeout)throws IOException{
        long deadline=System.nanoTime()+timeout*1_000_000L;
        for(int packets=0;packets<8192;packets++){
            long remaining=(deadline-System.nanoTime())/1_000_000L;
            if(remaining<=0)throw new IOException("The Fastboot device did not finish responding");
            byte[] bytes=transport.response((int)Math.min(Integer.MAX_VALUE,remaining));
            if(bytes==null)throw new EOFException("Fastboot disconnected");
            if(bytes.length==0)continue;
            if(bytes.length<4||bytes.length>256)throw new IOException("Invalid Fastboot response size");
            String type=new String(bytes,0,4,StandardCharsets.US_ASCII);
            String payload=new String(bytes,4,bytes.length-4,StandardCharsets.UTF_8);int nul=payload.indexOf(0);if(nul>=0)payload=payload.substring(0,nul);
            if(type.equals("INFO")||type.equals("TEXT")){
                String message=type.equals("INFO")?"(bootloader) "+payload+"\n":payload;
                messages.add(message);if(log!=null)log.accept(message);deadline=System.nanoTime()+timeout*1_000_000L;continue;
            }
            if(type.equals("FAIL"))throw new Rejected(payload);
            if(type.equals("OKAY")||type.equals("DATA"))return new Response(type,payload);
            throw new IOException("Unknown Fastboot response: "+type);
        }
        throw new IOException("The device sent too many Fastboot responses");
    }
    public synchronized Reply execute(String command,Consumer<String> log)throws IOException{
        byte[] encoded=encode(command);if(!isConnected())throw new IOException("Connect a Fastboot device first");
        try{
            transport.command(encoded);List<String> messages=new ArrayList<>();Response response=receive(messages,log,120000);
            if(!response.type.equals("OKAY"))throw new IOException("This command needs an image transfer. Use Flash image or Boot image.");
            return new Reply(response.payload,messages);
        }catch(Rejected rejection){throw rejection;}catch(IOException failure){try{close();}catch(IOException ignored){}throw failure;}
    }
    public synchronized String variable(String name)throws IOException{return execute("getvar:"+name,null).value;}
    public synchronized long fetch(String partition,long offset,long expected,OutputStream destination,Consumer<String> log,Progress progress)throws IOException {
        validatePartition(partition);boolean reading=false;
        try{
            transport.command(encode(String.format(Locale.US,"fetch:%s:0x%08x:0x%08x",partition,offset,expected)));
            List<String> messages=new ArrayList<>();Response response=receive(messages,log,30000);
            if(!response.type.equals("DATA")||!response.payload.matches("[0-9a-fA-F]{8}"))throw new IOException("The bootloader did not start partition readback");
            long size=Long.parseLong(response.payload,16);if(size!=expected)throw new IOException("The device reported a different partition readback size");
            reading=true;byte[] bytes=new byte[16384];long received=0;
            while(received<size){int n=transport.readData(bytes,0,(int)Math.min(bytes.length,size-received),30000);if(n<=0)throw new EOFException("Partition readback ended early");destination.write(bytes,0,n);received+=n;if(progress!=null)progress.transferred(received,size);}
            if(!receive(messages,log,120000).type.equals("OKAY"))throw new IOException("Partition readback was not confirmed");reading=false;return received;
        }catch(Rejected e){if(reading)try{close();}catch(IOException ignored){}throw e;}catch(IOException e){try{close();}catch(IOException ignored){}throw e;}
    }
    public synchronized void download(InputStream source,long length,Consumer<String> log,Progress progress)throws IOException{
        if(length<=0||length>0xffffffffL)throw new IOException("Fastboot images must contain 1 byte to 4 GB minus 1 byte");
        if(!isConnected())throw new IOException("Connect a Fastboot device first");
        boolean transferring=false;
        try{
            transport.command(encode(String.format(Locale.US,"download:%08x",length)));
            List<String> messages=new ArrayList<>();Response start=receive(messages,log,30000);
            if(!start.type.equals("DATA")||!start.payload.matches("[0-9a-fA-F]{8}"))throw new IOException("The device did not accept the image download");
            long requested=Long.parseLong(start.payload,16);
            if(requested!=length)throw new IOException("The device requested a different image length");
            transferring=true;byte[] buffer=new byte[16384];long sent=0;
            if(progress!=null)progress.transferred(0,length);
            while(sent<length){int n=source.read(buffer,0,(int)Math.min(buffer.length,length-sent));if(n<0)throw new EOFException("The selected image ended during transfer");if(n==0)continue;transport.data(buffer,0,n);sent+=n;if(progress!=null)progress.transferred(sent,length);}
            if(source.read()!=-1)throw new IOException("The selected image changed size during transfer");
            Response finish=receive(messages,log,120000);transferring=false;
            if(!finish.type.equals("OKAY"))throw new IOException("The bootloader did not confirm the image transfer");
        }catch(Rejected rejection){if(transferring)try{close();}catch(IOException ignored){}throw rejection;}catch(IOException failure){try{close();}catch(IOException ignored){}throw failure;}
    }
    public synchronized Reply flash(String partition,File image,Consumer<String> log,Progress progress)throws IOException{
        validatePartition(partition);
        try(InputStream input=new FileInputStream(image)){download(input,image.length(),log,progress);}
        if(log!=null)log.accept("Writing partition "+partition+"…\n");
        return execute("flash:"+partition,log);
    }
    public synchronized Reply boot(File image,Consumer<String> log,Progress progress)throws IOException{
        if(isSparse(image))throw new IOException("Choose a raw boot image. Sparse images are for flashing partitions.");
        try(InputStream input=new FileInputStream(image)){download(input,image.length(),log,progress);}
        if(log!=null)log.accept("Booting the downloaded image…\n");return execute("boot",log);
    }
    public static void validatePartition(String name)throws IOException{
        if(name==null||!name.matches("[A-Za-z0-9_.-]{1,128}"))throw new IOException("Enter an exact partition name, such as boot_a or recovery");
    }
    public static Long byteSize(String value){try{String s=value.trim();long bytes=s.startsWith("0x")||s.startsWith("0X")?Long.parseLong(s.substring(2),16):Long.parseLong(s);return bytes>0?bytes:null;}catch(Exception ignored){return null;}}
    public static boolean isSparse(File image)throws IOException{
        if(image.length()<4)return false;
        try(InputStream input=new FileInputStream(image)){byte[] bytes=AdbWireConnection.readFully(input,4);return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt()==0xed26ff3a;}
    }
    public static long expandedSize(File image)throws IOException{
        if(!isSparse(image))return image.length();
        try(InputStream input=new FileInputStream(image)){
            ByteBuffer h=ByteBuffer.wrap(AdbWireConnection.readFully(input,28)).order(ByteOrder.LITTLE_ENDIAN);
            h.getInt();int major=Short.toUnsignedInt(h.getShort());h.getShort();int header=Short.toUnsignedInt(h.getShort());int chunk=Short.toUnsignedInt(h.getShort());long block=Integer.toUnsignedLong(h.getInt()),blocks=Integer.toUnsignedLong(h.getInt());
            if(major!=1||header<28||chunk<12||block==0)throw new IOException("Unsupported or invalid Android sparse image");
            try{return Math.multiplyExact(block,blocks);}catch(ArithmeticException e){throw new IOException("Sparse image size is invalid",e);}
        }
    }
}
