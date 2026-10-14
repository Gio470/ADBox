package dev.adbox.toolkit

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Decode on worker threads and render directly to a Surface, without Compose bitmap copies. */
internal class ScreenVideoDecoder(width:Int,height:Int,surface:Surface,maxSize:Int,onSize:(Int,Int)->Unit,onError:()->Unit):Closeable {
    private val codec=MediaCodec.createDecoderByType("video/avc")
    private val closed=AtomicBoolean()
    private val failure=AtomicReference<Exception?>()
    val rendered=AtomicLong()
    private val output:Thread
    init {
        try {
            val format=MediaFormat.createVideoFormat("video/avc",width,height).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,ScreenProtocol.MAX_PACKET)
                setInteger(MediaFormat.KEY_MAX_WIDTH,maxOf(width,height,maxSize))
                setInteger(MediaFormat.KEY_MAX_HEIGHT,maxOf(width,height,maxSize))
                setInteger(MediaFormat.KEY_PRIORITY,0)
                if(Build.VERSION.SDK_INT>=30&&codec.codecInfo.getCapabilitiesForType("video/avc").isFeatureSupported("low-latency"))setInteger(MediaFormat.KEY_LOW_LATENCY,1)
            }
            codec.configure(format,surface,null,0);codec.start()
        }catch(e:Exception){codec.release();throw e}
        output=Thread({
            val info=MediaCodec.BufferInfo()
            fun publishSize(){
                val f=codec.outputFormat
                fun value(key:String,default:Int)=if(f.containsKey(key))f.getInteger(key)else default
                val w=value("crop-right",f.getInteger(MediaFormat.KEY_WIDTH)-1)-value("crop-left",0)+1
                val h=value("crop-bottom",f.getInteger(MediaFormat.KEY_HEIGHT)-1)-value("crop-top",0)+1
                if(w<1||h<1||w>8192||h>8192)throw IOException("Invalid decoded screen dimensions")
                onSize(w,h)
            }
            try {
                while(!closed.get()){
                    var index=codec.dequeueOutputBuffer(info,10000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){publishSize();continue}
                    if(index<0)continue
                    // Discard only already-decoded old output; compressed reference frames are all decoded.
                    while(!closed.get()){
                        val next=codec.dequeueOutputBuffer(info,0)
                        if(next<0){if(next==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)publishSize();break}
                        codec.releaseOutputBuffer(index,false);index=next
                    }
                    codec.releaseOutputBuffer(index,!closed.get());if(!closed.get())rendered.incrementAndGet()
                }
            }catch(e:Exception){if(!closed.get()){failure.set(e);onError()}}
        },"ADBox-screen-render").apply{isDaemon=true;start()}
    }
    fun feed(packet:ScreenProtocol.Packet,active:()->Boolean){
        while(!closed.get()&&active()){
            failure.get()?.let{throw IOException("Screen decoder failed",it)}
            val index=codec.dequeueInputBuffer(10000)
            if(index<0)continue
            val buffer=codec.getInputBuffer(index)?:throw IOException("Screen decoder input is unavailable")
            buffer.clear();if(packet.bytes.size>buffer.remaining())throw IOException("Screen video frame exceeds the decoder input buffer")
            buffer.put(packet.bytes)
            codec.queueInputBuffer(index,0,packet.bytes.size,packet.pts,if(packet.config)MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            return
        }
        throw IOException("Screen stream stopped")
    }
    override fun close(){if(closed.compareAndSet(false,true)){output.join(1000);runCatching{codec.stop()};codec.release()}}
}
