package dev.adbox.toolkit;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Resparse raw/sparse images into bounded downloads preserving block offsets. */
public final class SparseImage {
    public static final int RAW=0xcac1,FILL=0xcac2,SKIP=0xcac3,CRC=0xcac4;
    private static final class Run {final int type;final long start,blocks,offset;Run(int t,long s,long b,long o){type=t;start=s;blocks=b;offset=o;}}
    private final File source;private final List<Run> runs=new ArrayList<>();public final int blockSize;public final long totalBlocks,expandedSize;
    private int runIndex;private long consumed;private boolean emitted;
    public SparseImage(File source)throws IOException {
        this.source=source;
        try(RandomAccessFile file=new RandomAccessFile(source,"r")){
            if(file.length()<4||Integer.reverseBytes(file.readInt())!=0xed26ff3a){blockSize=4096;totalBlocks=(source.length()+blockSize-1)/blockSize;if(totalBlocks>0xffffffffL)throw new IOException("Image is too large");runs.add(new Run(RAW,0,totalBlocks,0));}
            else{
                ByteBuffer h=ByteBuffer.wrap(read(file,24)).order(ByteOrder.LITTLE_ENDIAN);int major=Short.toUnsignedInt(h.getShort());h.getShort();int header=Short.toUnsignedInt(h.getShort()),chunkHeader=Short.toUnsignedInt(h.getShort());blockSize=h.getInt();totalBlocks=Integer.toUnsignedLong(h.getInt());long chunks=Integer.toUnsignedLong(h.getInt());h.getInt();
                if(major!=1||header<28||chunkHeader<12||blockSize<=0||blockSize%4!=0||chunks>100000)throw new IOException("Invalid Android sparse image header");file.seek(header);long position=0;
                for(long i=0;i<chunks;i++){
                    ByteBuffer c=ByteBuffer.wrap(read(file,12)).order(ByteOrder.LITTLE_ENDIAN);int type=Short.toUnsignedInt(c.getShort());c.getShort();long blocks=Integer.toUnsignedLong(c.getInt()),size=Integer.toUnsignedLong(c.getInt());long payload=size-chunkHeader;
                    if(payload<0||size<chunkHeader||file.getFilePointer()+chunkHeader-12>file.length())throw new IOException("Invalid sparse chunk size");file.skipBytes(chunkHeader-12);long offset=file.getFilePointer();
                    if((type==RAW&&payload!=blocks*blockSize)||(type==FILL&&payload!=4)||(type==SKIP&&payload!=0)||(type==CRC&&(payload!=4||blocks!=0))||(type!=RAW&&type!=FILL&&type!=SKIP&&type!=CRC)||payload>file.length()-offset)throw new IOException("Truncated or unsupported sparse chunk");
                    if(type==RAW||type==FILL)runs.add(new Run(type,position,blocks,offset));if(type!=CRC)position+=blocks;file.seek(offset+payload);
                }
                if(position!=totalBlocks||file.getFilePointer()!=file.length())throw new IOException("Sparse block totals or file length do not match");
            }
            try{expandedSize=Math.multiplyExact(totalBlocks,(long)blockSize);}catch(ArithmeticException e){throw new IOException("Sparse image size overflow",e);}
        }
    }
    private static byte[] read(RandomAccessFile f,int n)throws IOException{byte[] bytes=new byte[n];f.readFully(bytes);return bytes;}
    private static void chunk(RandomAccessFile out,int type,long blocks,long payload)throws IOException{out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putShort((short)type).putShort((short)0).putInt((int)blocks).putInt((int)(12+payload)).array());}
    public boolean next(File destination,long maximum)throws IOException {
        if(runIndex>=runs.size()&&emitted)return false;
        if(maximum<52L+blockSize)throw new IOException("The device download buffer is too small for one sparse block");
        try(RandomAccessFile input=new RandomAccessFile(source,"r");RandomAccessFile out=new RandomAccessFile(destination,"rw")){
            out.setLength(0);out.write(ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putInt(0xed26ff3a).putShort((short)1).putShort((short)0).putShort((short)28).putShort((short)12).putInt(blockSize).putInt((int)totalBlocks).putInt(0).putInt(0).array());
            long position=0;int chunks=0;boolean wroteData=false;byte[] buffer=new byte[65536];
            while(runIndex<runs.size()){
                Run run=runs.get(runIndex);long start=run.start+consumed,remaining=run.blocks-consumed;
                if(remaining==0){runIndex++;consumed=0;continue;}
                long gap=start-position;long overhead=12+(gap>0?12:0)+12;
                long room=maximum-out.length()-overhead;
                long blocks=run.type==FILL?(room>=4?remaining:0):Math.min(remaining,Math.max(0,room/blockSize));
                if(blocks==0){if(!wroteData)throw new IOException("The device buffer cannot hold this sparse chunk");break;}
                if(gap>0){chunk(out,SKIP,gap,0);chunks++;}
                long bytes=run.type==FILL?4:blocks*blockSize;chunk(out,run.type,blocks,bytes);chunks++;
                input.seek(run.offset+(run.type==RAW?consumed*blockSize:0));long copied=0;
                while(copied<bytes){int n=(int)Math.min(buffer.length,bytes-copied);int available=(int)Math.min(n,Math.max(0,input.length()-input.getFilePointer()));if(available>0)input.readFully(buffer,0,available);if(available<n){if(FastbootClient.isSparse(source))throw new EOFException("Sparse payload ended");Arrays.fill(buffer,available,n,(byte)0);}out.write(buffer,0,n);copied+=n;}
                position=start+blocks;consumed+=blocks;wroteData=true;if(consumed==run.blocks){runIndex++;consumed=0;}
            }
            if(position<totalBlocks){chunk(out,SKIP,totalBlocks-position,0);chunks++;}
            out.seek(20);out.writeInt(Integer.reverseBytes(chunks));emitted=true;
            if(out.length()>maximum)throw new IOException("Sparse part exceeded the download buffer");return true;
        }
    }
}
