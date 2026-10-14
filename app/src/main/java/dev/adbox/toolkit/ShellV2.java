package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** ADB shell-v2 framing with binary stdout, exit status, stdin and half-close. */
public final class ShellV2 {
    public static final class Packet {public final int id;public final byte[] data;Packet(int id,byte[] data){this.id=id;this.data=data;}}
    public static Packet read(InputStream input)throws IOException {
        byte[] header=AdbWireConnection.readFully(input,5);
        int size=ByteBuffer.wrap(header,1,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if(size<0||size>1024*1024)throw new IOException("Invalid shell-v2 packet length");
        return new Packet(header[0]&255,AdbWireConnection.readFully(input,size));
    }
    public static void write(OutputStream output,int id,byte[] data)throws IOException {
        synchronized(output){output.write(ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put((byte)id).putInt(data.length).array());output.write(data);output.flush();}
    }
    public static void resize(OutputStream output,int rows,int cols)throws IOException {write(output,5,(rows+"x"+cols+",0x0\0").getBytes(StandardCharsets.US_ASCII));}
    public static long run(AdbChannel channel,InputStream source,OutputStream destination)throws IOException {
        AtomicReference<IOException> writerFailure=new AtomicReference<>();Thread writer=null;
        try(AdbChannel c=channel){
            if(source!=null){writer=new Thread(()->{try{byte[] bytes=new byte[32768];int n;while((n=source.read(bytes))!=-1)if(n>0)write(c.output(),0,java.util.Arrays.copyOf(bytes,n));write(c.output(),4,new byte[0]);}catch(IOException e){writerFailure.set(e);try{c.close();}catch(IOException ignored){}}},"ADBox-shell-stdin");writer.setDaemon(true);writer.start();}
            else write(c.output(),4,new byte[0]);
            ByteArrayOutputStream errors=new ByteArrayOutputStream();long count=0;
            while(true){Packet packet=read(c.input());
                if(packet.id==1){destination.write(packet.data);count+=packet.data.length;}
                else if(packet.id==2){int n=Math.min(packet.data.length,65536-errors.size());if(n>0)errors.write(packet.data,0,n);}
                else if(packet.id==3){if(packet.data.length!=1&&packet.data.length!=4)throw new IOException("Invalid shell exit status");int status=packet.data[0]&255;if(packet.data.length==4)status=ByteBuffer.wrap(packet.data).order(ByteOrder.LITTLE_ENDIAN).getInt();if(writerFailure.get()!=null)throw writerFailure.get();if(status!=0)throw new IOException("Device command exited "+status+": "+errors.toString("UTF-8").trim());destination.flush();return count;}
                else throw new IOException("Unexpected shell-v2 response "+packet.id);
            }
        }finally{if(writer!=null){writer.interrupt();if(source!=null)try{source.close();}catch(IOException ignored){}}}
    }
}
