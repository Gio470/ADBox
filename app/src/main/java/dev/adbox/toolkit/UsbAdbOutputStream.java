package dev.adbox.toolkit;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** USB ADB writes end with a short packet, including an explicit ZLP for aligned data. */
public final class UsbAdbOutputStream extends OutputStream {
    public interface Transfer { int write(byte[] bytes,int offset,int length)throws IOException; }
    private static final int MAX_TRANSFER=16384;
    private static final byte[] EMPTY=new byte[0];
    private final int packetSize;
    private final Transfer transfer;
    public UsbAdbOutputStream(int packetSize,Transfer transfer){
        if(packetSize<=0)throw new IllegalArgumentException("Invalid USB endpoint packet size");
        this.packetSize=packetSize;this.transfer=Objects.requireNonNull(transfer);
    }
    @Override public void write(int value)throws IOException{write(new byte[]{(byte)value});}
    @Override public void write(byte[] bytes,int offset,int length)throws IOException{
        if(offset<0||length<0||offset>bytes.length-length)throw new IndexOutOfBoundsException();
        if(length==0)return;
        int remaining=length,lastWritten=0;
        while(remaining>0){
            int count=Math.min(remaining,MAX_TRANSFER);
            int written;
            try{written=transfer.write(bytes,offset,count);}
            catch(IOException e){throw new IOException("USB write stopped at "+(length-remaining)+" / "+length+" B. "+e.getMessage(),e);}
            // Positive results specify exactly how many bytes were sent. Continue with
            // the unsent suffix; never replay a whole packet or guess after an error.
            if(written<=0||written>count)throw new IOException("USB OUT returned "+written+" for "+count+" B at "+(length-remaining)+" / "+length+" B (packet size "+packetSize+"). Re-enter Apply update from ADB and reconnect USB.");
            offset+=written;remaining-=written;lastWritten=written;
        }
        // FunctionFS reads can be larger than the advertised ADB payload. Without this
        // terminator recovery may wait forever and never send the next ADB OKAY.
        // A partial result may already have ended with a short packet. Terminate only
        // when the final physical write was aligned, not when total logical length was.
        if(lastWritten%packetSize==0){
            int result=transfer.write(EMPTY,0,0);
            if(result!=0)throw new IOException("USB packet termination failed (result "+result+", payload "+length+" B, packet size "+packetSize+"). Reconnect recovery and try again.");
        }
    }
}
