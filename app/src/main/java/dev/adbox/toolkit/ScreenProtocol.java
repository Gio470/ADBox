package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** The pinned scrcpy 3.3.4 USB stream/control protocol. No TCP client is used. */
public final class ScreenProtocol {
    public static final int MAX_PACKET = 4 * 1024 * 1024;
    private static final long CONFIG = 1L << 63, KEY = 1L << 62;
    private ScreenProtocol() {}
    public static final class VideoHeader {
        public final String name; public final int width, height;
        VideoHeader(String name, int width, int height){this.name=name;this.width=width;this.height=height;}
    }
    public static final class Packet {
        public final byte[] bytes; public final long pts; public final boolean config, key;
        Packet(byte[] bytes,long pts,boolean config,boolean key){this.bytes=bytes;this.pts=pts;this.config=config;this.key=key;}
    }
    public static void readDummy(InputStream input)throws IOException {
        if(input.read()!=0)throw new IOException("The screen helper did not accept its USB connection");
    }
    public static VideoHeader readHeader(InputStream input)throws IOException {
        byte[] name=AdbWireConnection.readFully(input,64);int end=0;while(end<name.length&&name[end]!=0)end++;
        DataInputStream in=new DataInputStream(input);int codec=in.readInt();
        if(codec!=0x68323634)throw new IOException("The target could not start H.264 screen capture");
        int width=in.readInt(),height=in.readInt();
        if(width<1||height<1||width>8192||height>8192)throw new IOException("Invalid screen stream dimensions");
        return new VideoHeader(new String(name,0,end,StandardCharsets.UTF_8),width,height);
    }
    public static Packet readPacket(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(input);long flags=in.readLong();int size=in.readInt();
        if(size<1||size>MAX_PACKET)throw new IOException("Invalid screen video packet size");
        return new Packet(AdbWireConnection.readFully(in,size),flags&~(CONFIG|KEY),(flags&CONFIG)!=0,(flags&KEY)!=0);
    }
    public static byte[] touch(int action,int x,int y,int width,int height)throws IOException {
        if(action<0||action>3||width<1||height<1||width>65535||height>65535||x<0||y<0||x>=width||y>=height)throw new IOException("Invalid screen touch coordinates");
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).put((byte)2).put((byte)action).putLong(0)
            .putInt(x).putInt(y).putShort((short)width).putShort((short)height)
            .putShort((short)(action==1||action==3?0:0xffff)).putInt(0).putInt(0).array();
    }
    public static byte[] key(int action,int keycode)throws IOException {
        if((action!=0&&action!=1)||keycode<0||keycode>1024)throw new IOException("Invalid screen key event");
        return ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).put((byte)0).put((byte)action).putInt(keycode).putInt(0).putInt(0).array();
    }
}
