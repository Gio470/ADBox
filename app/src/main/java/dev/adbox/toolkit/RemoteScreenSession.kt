package dev.adbox.toolkit

import android.content.Context
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class ScreenQuality(val label:String,val size:Int,val fps:Int,val bitrate:Int){
    Fast("Fast",720,30,1500000),Balanced("Balanced",1024,30,3000000),Sharp("Sharp",1440,30,6000000)
}

/** The helper and both sockets run on the selected USB ADB session; no IP forwarding. */
internal class RemoteScreenSession(private val context:Context,private val open:(String,Boolean)->AdbChannel):Closeable {
    private val stopped=AtomicBoolean()
    private val channels=mutableListOf<AdbChannel>()
    private val input=ScreenInputQueue(64)
    private val lastTouch=AtomicReference<ByteArray?>()
    private val lastKey=AtomicReference<ByteArray?>()
    private var targetPath:String?=null
    private var control:AdbChannel?=null
    private var writer:Thread?=null
    private val log=StringBuilder()
    private val processEnded=AtomicBoolean()
    @Volatile var decoder:ScreenVideoDecoder?=null;private set
    private val problem=AtomicReference<Exception?>()
    fun active()=!stopped.get()
    private fun channel(service:String,stream:Boolean=false):AdbChannel {
        if(stopped.get())throw IOException("Screen control stopped")
        val c=open(service,stream)
        synchronized(channels){if(stopped.get()){c.close();throw IOException("Screen control stopped")};channels+=c}
        return c
    }
    fun run(surface:Surface,quality:ScreenQuality,onStatus:(String)->Unit,onSize:(Int,Int)->Unit){
        try {
            onStatus("Preparing USB video")
            val bytes=context.assets.open("adbox-screen-server.jar").use{it.readBytes()}
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
            if(hash!=SERVER_SHA)throw IOException("The bundled screen helper failed its integrity check")
            val id=SecureRandom().nextInt(Int.MAX_VALUE)
            val suffix=String.format(java.util.Locale.ROOT,"%08x",id)
            val path="/data/local/tmp/adbox-screen-$suffix.jar";targetPath=path
            SyncTransfer.push(channel("sync:"),path,bytes.inputStream())
            val command="CLASSPATH=${AdbResponses.quote(path)} app_process / com.genymobile.scrcpy.Server $SERVER_VERSION scid=$suffix tunnel_forward=true audio=false control=true video_codec=h264 max_size=${quality.size} max_fps=${quality.fps} video_bit_rate=${quality.bitrate} video_codec_options=i-frame-interval=1 clipboard_autosync=false power_on=false cleanup=true log_level=warn"
            val process=channel("exec:$command")
            Thread({try{val buffer=ByteArray(2048);while(active()){val n=process.input().read(buffer);if(n<0)break;synchronized(log){log.append(String(buffer,0,n));if(log.length>8192)log.delete(0,log.length-8192)}}}catch(_:Exception){}finally{processEnded.set(true)}},"ADBox-screen-helper-log").apply{isDaemon=true;start()}
            onStatus("Connecting USB video")
            val deadline=System.nanoTime()+15_000_000_000L
            var video:AdbChannel?=null
            while(active()&&System.nanoTime()<deadline&&!processEnded.get()){
                try{video=channel("localabstract:scrcpy_$suffix",true);break}catch(e:Exception){if(!active())throw e;Thread.sleep(80)}
            }
            val v=video?:throw IOException("Could not start screen capture. ${synchronized(log){log.toString().takeLast(1600)}}")
            // The first dummy byte confirms the listener before opening its second (control) socket.
            ScreenProtocol.readDummy(v.input())
            val c=channel("localabstract:scrcpy_$suffix");control=c
            writer=Thread({try{while(active()){val event=input.take()?:break;c.output().write(event);c.output().flush();if(event.size==32&&event[0].toInt()==2)lastTouch.set(event);if(event.size==14&&event[0].toInt()==0)lastKey.set(event)}}catch(e:Exception){if(active()){problem.set(e);closeQuickly()}}},"ADBox-screen-input").apply{isDaemon=true;start()}
            Thread({try{val discard=ByteArray(1024);while(active()&&c.input().read(discard)>=0){}}catch(_:Exception){}finally{if(active()){problem.compareAndSet(null,IOException("Screen control channel closed"));closeQuickly()}}},"ADBox-screen-control-reader").apply{isDaemon=true;start()}
            val header=ScreenProtocol.readHeader(v.input());onSize(header.width,header.height)
            val d=ScreenVideoDecoder(header.width,header.height,surface,quality.size,onSize){problem.compareAndSet(null,IOException("The Android video decoder stopped"));closeQuickly()};decoder=d
            onStatus("USB video · direct touch")
            while(active()){val packet=ScreenProtocol.readPacket(v.input());d.feed(packet,::active)}
            problem.get()?.let{throw it}
        }catch(e:Exception){if(problem.get()!=null)throw IOException("Screen stream failed: ${problem.get()?.message}",problem.get());if(active())throw e}
        finally{close();decoder?.close();decoder=null}
    }
    fun touch(action:Int,x:Int,y:Int,width:Int,height:Int){enqueue(ScreenProtocol.touch(action,x,y,width,height))}
    fun key(code:Int){enqueue(ScreenProtocol.key(0,code));enqueue(ScreenProtocol.key(1,code))}
    private fun enqueue(bytes:ByteArray){if(!input.offer(bytes)&&active()){problem.set(IOException("Screen input could not keep up; restart screen control"));closeQuickly()}}
    private fun closeQuickly(){Thread({close()},"ADBox-screen-stop").apply{isDaemon=true;start()}}
    override fun close(){
        if(!stopped.compareAndSet(false,true))return
        input.close();writer?.interrupt();if(writer!==Thread.currentThread())writer?.join(200)
        // Release an in-flight finger before ending the controller; no touch is replayed on another target.
        val last=lastTouch.get();if(writer?.isAlive!=true&&last!=null&&last[1].toInt() in listOf(0,2))runCatching{
            val cancelled=last.clone().apply{this[1]=3;this[22]=0;this[23]=0};control?.output()?.write(cancelled)
            cancelled[1]=1;control?.output()?.write(cancelled);control?.output()?.flush()
        }
        val key=lastKey.get();if(writer?.isAlive!=true&&key!=null&&key[1].toInt()==0)runCatching{control?.output()?.write(key.clone().apply{this[1]=1});control?.output()?.flush()}
        val copy=synchronized(channels){channels.toList().also{channels.clear()}}
        copy.asReversed().forEach{runCatching{it.close()}}
        targetPath?.let{path->runCatching{open("exec:rm -f -- ${AdbResponses.quote(path)}",false).use{it.input().read()}}}
    }
    companion object {
        const val SERVER_VERSION="3.3.4"
        const val SERVER_SHA="8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e"
    }
}
