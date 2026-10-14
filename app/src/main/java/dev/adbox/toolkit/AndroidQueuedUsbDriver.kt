package dev.adbox.toolkit

import android.hardware.usb.*
import android.os.Build
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Android adapter; only QueuedUsbTransport's pump calls requestWait. */
internal class AndroidQueuedUsbDriver(
    private val manager:UsbManager,private val device:UsbDevice,
    private val connection:UsbDeviceConnection,private val input:UsbEndpoint,private val output:UsbEndpoint
):QueuedUsbTransport.Driver{
    private val closed=AtomicBoolean(false)
    private val released=AtomicBoolean(false)
    private val guard=Any()
    private val readFlight=AtomicBoolean(false)
    private val writeFlight=AtomicBoolean(false)
    private val read=UsbRequest()
    private val write=UsbRequest()
    @Volatile private var readBuffer:ByteBuffer?=null
    @Volatile private var writeBuffer:ByteBuffer?=null
    init{
        if(!read.initialize(connection,input)||!write.initialize(connection,output)){
            close();release();throw IOException("Cannot initialize queued USB requests")
        }
    }
    override fun queue(direction:Int,buffer:ByteBuffer):Boolean=synchronized(guard){
        if(closed.get()||!manager.deviceList.containsKey(device.deviceName))throw IOException("USB detached. ${describe()}")
        val request=when(direction){
            QueuedUsbTransport.INPUT->{readBuffer=buffer;readFlight.set(true);read}
            QueuedUsbTransport.OUTPUT->{writeBuffer=buffer;writeFlight.set(true);write}
            else->throw IOException("Invalid USB request direction")
        }
        val flight=if(direction==QueuedUsbTransport.INPUT)readFlight else writeFlight
        try{request.queue(buffer).also{if(!it)flight.set(false)}}
        catch(e:Exception){flight.set(false);throw IOException("Cannot queue USB request: ${e.message}. ${describe()}",e)}
    }
    @Throws(TimeoutException::class)
    override fun await(timeoutMillis:Int):QueuedUsbTransport.Completion?{
        val completed=connection.requestWait(timeoutMillis.toLong())?:throw IOException("Android returned no USB completion. ${describe()}")
        return when(completed){
            read->{readFlight.set(false);QueuedUsbTransport.Completion(QueuedUsbTransport.INPUT,readBuffer?.position()?:-1)}
            write->{writeFlight.set(false);QueuedUsbTransport.Completion(QueuedUsbTransport.OUTPUT,writeBuffer?.position()?:-1)}
            else->throw IOException("Android returned an unknown USB request")
        }
    }
    override fun describe()="queued USB; attached=${manager.deviceList.containsKey(device.deviceName)}; permission=${manager.hasPermission(device)}; OUT=0x${output.address.toString(16)}; host ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
    override fun close(){
        synchronized(guard){
            if(closed.compareAndSet(false,true)){runCatching{read.cancel()};runCatching{write.cancel()}}
        }
    }
    override fun release(){
        if(released.compareAndSet(false,true)){
            // Called only after the completion pump leaves requestWait. Reap cancelled
            // requests before freeing their native request objects/direct buffers.
            val deadline=android.os.SystemClock.elapsedRealtime()+1500L
            while((readFlight.get()||writeFlight.get())&&android.os.SystemClock.elapsedRealtime()<deadline){
                try{await((deadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt())}
                catch(_:Exception){break}
            }
            connection.close();runCatching{read.close()};runCatching{write.close()}
        }
    }
}
