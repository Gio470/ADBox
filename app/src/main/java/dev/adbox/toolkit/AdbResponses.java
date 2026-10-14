package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/** Service-level completion independent of the transport's asynchronous close. */
public final class AdbResponses {
    public static final class ShellResult {
        public final String text;public final int exitCode;public final boolean truncated;
        ShellResult(String t,int c,boolean cut){text=t;exitCode=c;truncated=cut;}
    }
    public static String marker(){return "\n__ADBOX_"+UUID.randomUUID().toString().replace("-","")+"__:";}
    public static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    public static String shell(String command,String marker){return "sh -c "+quote(command)+"; printf '"+marker.replace("\n","\\n")+"%s\\n' \"$?\"";}
    public static ShellResult readShell(InputStream input,String marker,int limit,Consumer<String> progress) throws IOException {
        return readShell(input,marker,limit,progress,false);
    }
    public static ShellResult readShell(InputStream input,String marker,int limit,Consumer<String> progress,boolean keepRunning) throws IOException {
        return readShell(input,marker,limit,progress,keepRunning,0,System::nanoTime);
    }
    public static ShellResult readShell(InputStream input,String marker,int limit,Consumer<String> progress,boolean keepRunning,int progressIntervalMillis) throws IOException {
        return readShell(input,marker,limit,progress,keepRunning,progressIntervalMillis,System::nanoTime);
    }
    static ShellResult readShell(InputStream input,String marker,int limit,Consumer<String> progress,boolean keepRunning,int progressIntervalMillis,LongSupplier clock) throws IOException {
        if(progressIntervalMillis<0)throw new IllegalArgumentException("Negative progress interval");
        Reader reader=new InputStreamReader(input,StandardCharsets.UTF_8);char[] chars=new char[4096];StringBuilder output=new StringBuilder();
        boolean clipped=false,reported=false;long lastProgress=0,interval=progressIntervalMillis*1_000_000L;
        while(true){int n=reader.read(chars);if(n<0)throw new EOFException("The command ended before the device reported its exit status");output.append(chars,0,n);
            int start=output.indexOf(marker),end=start<0?-1:output.indexOf("\n",start+marker.length());
            if(end>=0){int code;try{code=Integer.parseInt(output.substring(start+marker.length(),end).trim());}catch(NumberFormatException e){throw new IOException("Invalid command exit status",e);}String text=output.substring(0,start);if(keepRunning&&text.length()>limit){text=text.substring(text.length()-limit);clipped=true;}text=(clipped?"[Earlier output clipped]\n":"")+text;if(progress!=null)progress.accept(text);return new ShellResult(text,code,clipped);}
            if(output.length()>limit){if(!keepRunning){String text=output.substring(0,limit)+"\n[Output limit reached]";if(progress!=null)progress.accept(text);return new ShellResult(text,-1,true);}output.delete(0,output.length()-limit);clipped=true;start=output.indexOf(marker);}
            if(progress!=null){long now=clock.getAsLong();if(!reported||now-lastProgress>=interval){progress.accept(start<0?output.toString():output.substring(0,start));lastProgress=now;reported=true;}}
        }
    }
    public static byte[] readPng(InputStream input,int limit)throws IOException{
        byte[] signature=AdbWireConnection.readFully(input,8),expected={(byte)137,80,78,71,13,10,26,10};
        if(!Arrays.equals(signature,expected))throw new IOException("The device did not return a PNG screenshot");
        ByteArrayOutputStream output=new ByteArrayOutputStream();output.write(signature);
        while(true){byte[] header=AdbWireConnection.readFully(input,8);int size=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt();
            if(size<0||size>limit-output.size()-12)throw new IOException("Screenshot exceeds the supported size");
            byte[] data=AdbWireConnection.readFully(input,size),checksum=AdbWireConnection.readFully(input,4);
            CRC32 crc=new CRC32();crc.update(header,4,4);crc.update(data);
            if((int)crc.getValue()!=ByteBuffer.wrap(checksum).order(ByteOrder.BIG_ENDIAN).getInt())throw new IOException("Screenshot data is incomplete or corrupt");
            output.write(header);output.write(data);output.write(checksum);
            if(new String(header,4,4,StandardCharsets.US_ASCII).equals("IEND")){if(size!=0)throw new IOException("Invalid screenshot terminator");return output.toByteArray();}
        }
    }
}
