package dev.adbox.toolkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/** Recovery's block-request protocol, not an APK installation stream. */
public final class SideloadClient {
    public static final int BLOCK_SIZE=65536;
    /** Unique file coverage is separate from bytes sent, which includes repeated reads. */
    public interface Progress { void update(long uniqueBytes,long transferredBytes,long totalBytes); }
    public static String service(long length){return "sideload-host:"+length+":"+BLOCK_SIZE;}
    public static long serve(AdbChannel channel,File packageFile,Progress progress)throws IOException {
        long size=packageFile.length();if(size<=0)throw new IOException("Choose a nonempty recovery update ZIP");
        try(AdbChannel c=channel;RandomAccessFile file=new RandomAccessFile(packageFile,"r")){
            byte[] buffer=new byte[BLOCK_SIZE];long transferred=0,unique=0;
            BitSet served=new BitSet();
            while(true){
                String request=new String(AdbWireConnection.readFully(c.input(),8),StandardCharsets.US_ASCII);
                if(request.equals("DONEDONE"))return transferred;
                if(request.equals("FAILFAIL"))throw new IOException("Recovery reported an installation failure");
                if(!request.matches("[0-9]{8}"))throw new IOException("Invalid recovery block request");
                int block=Integer.parseInt(request);long offset=(long)block*BLOCK_SIZE;
                if(offset>=size)throw new IOException("Recovery requested a block beyond the update file");
                int count=(int)Math.min(BLOCK_SIZE,size-offset);file.seek(offset);file.readFully(buffer,0,count);
                try{c.output().write(buffer,0,count);c.output().flush();}
                catch(IOException e){throw new IOException("Sideload block "+block+" (offset "+offset+"): "+e.getMessage(),e);}
                transferred+=count;
                if(!served.get(block)){served.set(block);unique+=count;}
                if(progress!=null)progress.update(unique,transferred,size);
            }
        }
    }
}
