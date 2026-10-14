package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Standard ADB SYNC: directory inventory and streamed push/pull. */
public final class SyncTransfer {
    public static final class Entry {
        public final String name;public final boolean directory;public final long size,modified;
        Entry(String n,int mode,int bytes,int time){name=n;directory=(mode&0xf000)==0x4000;size=Integer.toUnsignedLong(bytes);modified=Integer.toUnsignedLong(time);}
    }
    private static void request(OutputStream out,String id,byte[] bytes) throws IOException {header(out,id,bytes.length);out.write(bytes);out.flush();}
    private static void header(OutputStream out,String id,int value) throws IOException {out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWireConnection.code(id)).putInt(value).array());}
    private static int[] response(InputStream in) throws IOException {ByteBuffer b=ByteBuffer.wrap(AdbWireConnection.readFully(in,8)).order(ByteOrder.LITTLE_ENDIAN);return new int[]{b.getInt(),b.getInt()};}
    private static void failed(InputStream in,int length) throws IOException {if(length<0||length>65536)throw new IOException("Invalid SYNC error length");throw new IOException(new String(AdbWireConnection.readFully(in,length),StandardCharsets.UTF_8));}
    private static byte[] path(String path) throws IOException {byte[] bytes=path.getBytes(StandardCharsets.UTF_8);if(path.indexOf('\0')>=0||bytes.length>4096)throw new IOException("Invalid remote path");return bytes;}
    public static List<Entry> list(AdbChannel channel,String path) throws IOException {
        try(AdbChannel c=channel){request(c.output(),"LIST",path(path));List<Entry> entries=new ArrayList<>();
            while(true){int[] h=response(c.input());if(h[0]==AdbWireConnection.code("FAIL"))failed(c.input(),h[1]);
                if(h[0]==AdbWireConnection.code("DONE")){AdbWireConnection.readFully(c.input(),12);break;}
                if(h[0]!=AdbWireConnection.code("DENT"))throw new IOException("Unexpected directory response");
                ByteBuffer metadata=ByteBuffer.wrap(AdbWireConnection.readFully(c.input(),12)).order(ByteOrder.LITTLE_ENDIAN);int size=metadata.getInt(),time=metadata.getInt(),length=metadata.getInt();
                if(length<0||length>4096||entries.size()>=20000)throw new IOException("Directory exceeds the supported listing size");
                String name=new String(AdbWireConnection.readFully(c.input(),length),StandardCharsets.UTF_8);if(!name.equals(".")&&!name.equals(".."))entries.add(new Entry(name,h[1],size,time));
            }
            entries.sort(Comparator.comparing((Entry e)->!e.directory).thenComparing(e->e.name.toLowerCase(Locale.ROOT)));return entries;
        }
    }
    public static long pull(AdbChannel channel,String path,OutputStream destination) throws IOException {
        try(AdbChannel c=channel){request(c.output(),"RECV",path(path));long count=0;
            while(true){int[] h=response(c.input());if(h[0]==AdbWireConnection.code("DONE"))break;if(h[0]==AdbWireConnection.code("FAIL"))failed(c.input(),h[1]);
                if(h[0]!=AdbWireConnection.code("DATA")||h[1]<0||h[1]>65536)throw new IOException("Invalid file data frame");byte[] bytes=AdbWireConnection.readFully(c.input(),h[1]);destination.write(bytes);count+=bytes.length;
            }destination.flush();return count;
        }
    }
    public static long push(AdbChannel channel,String path,InputStream source) throws IOException {
        try(AdbChannel c=channel){request(c.output(),"SEND",path(path+",33188"));byte[] buffer=new byte[65536];long count=0;int n;
            while((n=source.read(buffer))!=-1){if(n==0)continue;header(c.output(),"DATA",n);c.output().write(buffer,0,n);c.output().flush();count+=n;}
            header(c.output(),"DONE",(int)(System.currentTimeMillis()/1000));c.output().flush();int[] h=response(c.input());if(h[0]==AdbWireConnection.code("FAIL"))failed(c.input(),h[1]);if(h[0]!=AdbWireConnection.code("OKAY"))throw new IOException("Device did not confirm the upload");return count;
        }
    }
}
