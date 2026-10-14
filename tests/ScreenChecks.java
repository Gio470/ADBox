import dev.adbox.toolkit.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ScreenChecks {
    static final java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks.incrementAndGet();}
    static byte[] bytes(int... values){ByteBuffer b=ByteBuffer.allocate(values.length*4);for(int value:values)b.putInt(value);return b.array();}
    static class Frame {int cmd,a,b;byte[] payload;Frame(int c,int a,int b,byte[] p){cmd=c;this.a=a;this.b=b;payload=p;}}
    static Frame read(InputStream input)throws Exception{ByteBuffer h=ByteBuffer.wrap(AdbWireConnection.readFully(input,24)).order(ByteOrder.LITTLE_ENDIAN);int cmd=h.getInt(),a=h.getInt(),b=h.getInt(),size=h.getInt();h.getInt();h.getInt();return new Frame(cmd,a,b,AdbWireConnection.readFully(input,size));}
    static void send(OutputStream out,String cmd,int a,int b,byte[] payload)throws Exception{ByteBuffer h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);int c=AdbWireConnection.code(cmd);h.putInt(c).putInt(a).putInt(b).putInt(payload.length).putInt(AdbWireConnection.sum(payload)).putInt(c^0xffffffff);out.write(h.array());out.write(payload);out.flush();}
    static void backpressure()throws Exception{
        PipedInputStream hostInput=new PipedInputStream(65536),peerInput=new PipedInputStream(65536);
        PipedOutputStream peerOutput=new PipedOutputStream(hostInput),hostOutput=new PipedOutputStream(peerInput);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        var peer=executor.submit(()->{try{
            read(peerInput);send(peerOutput,"CNXN",0x01000000,65536,"device::\0".getBytes());
            Frame video=read(peerInput);send(peerOutput,"OKAY",31,video.a,new byte[0]);send(peerOutput,"WRTE",31,video.a,new byte[]{10,20,30});
            Frame control=read(peerInput);check(control.cmd==AdbWireConnection.code("OPEN"),"video does not ACK before the consumer reads");send(peerOutput,"OKAY",32,control.a,new byte[0]);send(peerOutput,"WRTE",32,control.a,new byte[]{42});
            // Independent streams may interleave: OPEN can win the output lock before the control ACK.
            Frame first=read(peerInput),second=read(peerInput);
            Frame probe=first.cmd==AdbWireConnection.code("OPEN")?first:second;
            Frame ackControl=first.cmd==AdbWireConnection.code("OPEN")?second:first;
            check(ackControl.cmd==AdbWireConnection.code("OKAY")&&ackControl.b==32,"control remains responsive while the video consumer is paused");
            check(probe.cmd==AdbWireConnection.code("OPEN"),"partial video reads do not prematurely release backpressure");send(peerOutput,"OKAY",33,probe.a,new byte[0]);
            Frame ackVideo=read(peerInput);check(ackVideo.cmd==AdbWireConnection.code("OKAY")&&ackVideo.b==31,"video ACK is sent once its payload is consumed");
            send(peerOutput,"WRTE",31,video.a,new byte[]{40,50});
            Frame next=read(peerInput);check(next.cmd==AdbWireConnection.code("OKAY")&&next.b==31,"the next video packet resumes after consumption");
            send(peerOutput,"WRTE",31,video.a,new byte[0]);
            Frame empty=read(peerInput);check(empty.cmd==AdbWireConnection.code("OKAY")&&empty.b==31,"empty video writes are acknowledged without a consumer deadlock");
            send(peerOutput,"CLSE",31,video.a,new byte[0]);return null;
        }catch(Exception e){throw new RuntimeException(e);}});
        AdbWireConnection wire=new AdbWireConnection(hostInput,hostOutput,()->{hostInput.close();hostOutput.close();},null);
        try{
            wire.connect();AdbChannel video=wire.open("localabstract:video",true),control=wire.open("localabstract:control");
            check(control.input().read()==42,"another incoming stream is delivered without draining video");
            check(video.input().read()==10,"first partial video byte is exact");wire.open("localabstract:probe");
            check(Arrays.equals(AdbWireConnection.readFully(video.input(),2),new byte[]{20,30}),"remaining video data is preserved");
            check(Arrays.equals(AdbWireConnection.readFully(video.input(),2),new byte[]{40,50}),"backpressured sequence preserves all bytes");
            check(video.input().read()==-1,"video close unblocks its reader");peer.get(5,TimeUnit.SECONDS);
        }finally{wire.close();peerOutput.close();peerInput.close();executor.shutdownNow();}
    }
    public static void main(String[] args)throws Exception{
        ByteArrayOutputStream raw=new ByteArrayOutputStream();raw.write(0);byte[] name=new byte[64];System.arraycopy("Pixel 4a".getBytes(),0,name,0,8);raw.write(name);raw.write(bytes(0x68323634,440,960));
        var input=new ByteArrayInputStream(raw.toByteArray());ScreenProtocol.readDummy(input);var header=ScreenProtocol.readHeader(input);check(header.name.equals("Pixel 4a")&&header.width==440&&header.height==960,"pinned video header order and dimensions");
        byte[] data={0,0,0,1,0x65,10,20,30};
        for(long flags:new long[]{1L<<63,(1L<<62)|123456,222}){
            ByteBuffer frame=ByteBuffer.allocate(12+data.length).putLong(flags).putInt(data.length).put(data);
            InputStream chunks=new FilterInputStream(new ByteArrayInputStream(frame.array())){public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(1,n));}};
            var packet=ScreenProtocol.readPacket(chunks);check(Arrays.equals(packet.bytes,data),"short USB reads retain every compressed byte");check(packet.config==(flags<0)&&packet.key==((flags&(1L<<62))!=0),"codec config and keyframe flags stay separate");check(packet.pts==(flags&0x3fffffffffffffffL),"flags do not leak into presentation timestamps");
        }
        for(int size:new int[]{-1,0,ScreenProtocol.MAX_PACKET+1}){try{ScreenProtocol.readPacket(new ByteArrayInputStream(ByteBuffer.allocate(12).putLong(0).putInt(size).array()));throw new AssertionError("invalid packet size accepted");}catch(IOException expected){checks.incrementAndGet();}}
        try{ScreenProtocol.readPacket(new ByteArrayInputStream(ByteBuffer.allocate(14).putLong(1).putInt(3).putShort((short)4).array()));throw new AssertionError("truncated packet accepted");}catch(EOFException expected){checks.incrementAndGet();}
        for(int[] dimensions:new int[][]{{0,720},{720,0},{8193,480}}){ByteArrayOutputStream h=new ByteArrayOutputStream();h.write(new byte[64]);h.write(bytes(0x68323634,dimensions[0],dimensions[1]));try{ScreenProtocol.readHeader(new ByteArrayInputStream(h.toByteArray()));throw new AssertionError("invalid dimensions accepted");}catch(IOException expected){checks.incrementAndGet();}}
        byte[] touch=ScreenProtocol.touch(0,100,200,440,960);ByteBuffer t=ByteBuffer.wrap(touch);check(t.remaining()==32&&t.get()==2&&t.get()==0&&t.getLong()==0,"touch event type, down action and finger id");check(t.getInt()==100&&t.getInt()==200&&Short.toUnsignedInt(t.getShort())==440&&Short.toUnsignedInt(t.getShort())==960,"screen coordinates include their encoded geometry");check(Short.toUnsignedInt(t.getShort())==65535&&t.getInt()==0&&t.getInt()==0,"touch pressure and button fields match the server");
        check(ByteBuffer.wrap(ScreenProtocol.touch(1,100,200,440,960)).getShort(22)==0,"finger-up clears pressure");
        try{ScreenProtocol.touch(0,440,100,440,960);throw new AssertionError("offscreen touch accepted");}catch(IOException expected){checks.incrementAndGet();}
        byte[] key=ScreenProtocol.key(1,4);ByteBuffer k=ByteBuffer.wrap(key);check(k.remaining()==14&&k.get()==0&&k.get()==1&&k.getInt()==4&&k.getInt()==0&&k.getInt()==0,"key-up includes keycode, repeat and meta state");
        ScreenInputQueue queue=new ScreenInputQueue(4);queue.offer(touch);for(int x=0;x<10000;x++)queue.offer(ScreenProtocol.touch(2,x%440,200,440,960));queue.offer(ScreenProtocol.touch(1,100,200,440,960));check(queue.size()==3,"ten thousand drag updates use bounded storage");check(queue.take()[1]==0&&ByteBuffer.wrap(queue.take()).getInt(10)==9999%440&&queue.take()[1]==1,"latest motion preserves down/up order");
        ScreenInputQueue boundaries=new ScreenInputQueue(4);boundaries.offer(touch);boundaries.offer(ScreenProtocol.touch(2,1,1,440,960));boundaries.offer(ScreenProtocol.key(0,3));boundaries.offer(ScreenProtocol.touch(2,2,2,440,960));check(boundaries.offer(ScreenProtocol.touch(1,2,2,440,960)),"up can evict obsolete motion when capacity is reached");check(boundaries.take()[1]==0&&boundaries.take()[0]==0&&boundaries.take()[1]==2&&boundaries.take()[1]==1,"motion coalescing never crosses a key event boundary");
        ScreenInputQueue full=new ScreenInputQueue(2);full.offer(ScreenProtocol.key(0,4));full.offer(ScreenProtocol.key(1,4));check(!full.offer(touch),"full critical-event queue fails explicitly instead of dropping a down/up");
        ScreenInputQueue blocked=new ScreenInputQueue(2);ExecutorService waiter=Executors.newSingleThreadExecutor();var future=waiter.submit(blocked::take);blocked.close();check(future.get(1,TimeUnit.SECONDS)==null&&!blocked.offer(touch),"closing input unblocks the writer and rejects later input");waiter.shutdownNow();
        backpressure();System.out.println("PASS: "+checks+" screen video framing, direct input, bounded motion and independent ADB backpressure checks.");
    }
}
