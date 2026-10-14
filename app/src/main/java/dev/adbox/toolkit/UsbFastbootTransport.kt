package dev.adbox.toolkit

import android.hardware.usb.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Fastboot responses retain USB packet boundaries; this is not an ADB stream. */
internal class UsbFastbootTransport(private val manager: UsbManager, private val device: UsbDevice) : FastbootClient.Transport {
    private val face = (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
        it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 3
    } ?: throw IOException("This USB device has no Fastboot interface")
    private val endpoints = (0 until face.endpointCount).map(face::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    private val incoming = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw IOException("Missing Fastboot input endpoint")
    private val outgoing = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw IOException("Missing Fastboot output endpoint")
    private val connection = manager.openDevice(device) ?: throw IOException("USB permission was not granted")
    private val closed = AtomicBoolean()
    init { if (!connection.claimInterface(face, true)) { connection.close(); throw IOException("Cannot claim the Fastboot USB interface") } }
    override fun isOpen() = !closed.get() && manager.deviceList.containsKey(device.deviceName)
    override fun command(bytes: ByteArray) {
        if (!isOpen()) throw IOException("Fastboot USB device disconnected")
        val n = connection.bulkTransfer(outgoing, bytes, bytes.size, 5000)
        if (n != bytes.size) { close(); throw IOException("The USB command transfer failed. Reconnect the device.") }
    }
    override fun data(bytes: ByteArray, offset: Int, length: Int) {
        var position=offset;var remaining=length
        while(remaining>0){
            if(!isOpen())throw IOException("Fastboot USB device disconnected during image transfer")
            val n=connection.bulkTransfer(outgoing,bytes,position,minOf(remaining,16384),10000)
            if(n<=0){close();throw IOException("USB image transfer failed. Check the cable and reconnect.")}
            position+=n;remaining-=n
        }
    }
    override fun response(timeoutMillis: Int): ByteArray {
        val end=System.nanoTime()+timeoutMillis.toLong()*1_000_000
        val bytes=ByteArray(256)
        while(true){
            if(!isOpen())throw IOException("Fastboot USB device disconnected")
            val remaining=(end-System.nanoTime())/1_000_000
            if(remaining<=0)throw IOException("The bootloader response timed out")
            val n=connection.bulkTransfer(incoming,bytes,bytes.size,minOf(1000,remaining.toInt()).coerceAtLeast(1))
            if(n>0)return bytes.copyOf(n)
        }
    }
    override fun readData(bytes:ByteArray,offset:Int,length:Int,timeoutMillis:Int):Int {
        if(!isOpen())throw IOException("Fastboot USB device disconnected during readback")
        val n=connection.bulkTransfer(incoming,bytes,offset,minOf(length,16384),timeoutMillis)
        if(n<=0)throw IOException("Fastboot partition readback timed out")
        return n
    }
    override fun close() { if(closed.compareAndSet(false,true)){connection.releaseInterface(face);connection.close()} }
}
