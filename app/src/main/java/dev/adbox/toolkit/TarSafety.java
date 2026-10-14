package dev.adbox.toolkit;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Validate a plain ustar archive before feeding it to the target's tar. */
public final class TarSafety {
    private static String text(byte[] h,int start,int length){int end=start;while(end<start+length&&h[end]!=0)end++;return new String(h,start,end-start,StandardCharsets.UTF_8);}
    private static long octal(byte[] h,int start,int length)throws IOException{String s=text(h,start,length).trim();if(s.isEmpty())return 0;if(!s.matches("[0-7]+"))throw new IOException("Unsupported TAR number encoding");try{return Long.parseLong(s,8);}catch(Exception e){throw new IOException("Invalid TAR size",e);}}
    public static void validate(File file)throws IOException{
        try(RandomAccessFile f=new RandomAccessFile(file,"r")){int entries=0;boolean ended=false;byte[] h=new byte[512];
            while(f.getFilePointer()<f.length()){
                f.readFully(h);boolean zero=true;for(byte b:h)if(b!=0){zero=false;break;}if(zero){ended=true;break;}
                long sum=0;for(int i=0;i<512;i++)sum+=(i>=148&&i<156)?32:h[i]&255;if(sum!=octal(h,148,8))throw new IOException("TAR header checksum mismatch");
                String name=text(h,0,100),prefix=text(h,345,155);if(!prefix.isEmpty())name=prefix+"/"+name;
                if(name.isEmpty()||name.startsWith("/")||name.indexOf('\0')>=0)throw new IOException("Unsafe TAR entry path");for(String part:name.split("/"))if(part.equals(".."))throw new IOException("TAR entry escapes the restore destination");
                int type=h[156]&255;if(type!=0&&type!='0'&&type!='5')throw new IOException("Restore supports plain files/directories; links and extended TAR metadata are not accepted");
                long size=octal(h,124,12);if(size>f.length()-f.getFilePointer())throw new IOException("Truncated TAR entry");long padded=(size+511)/512*512;if(padded>f.length()-f.getFilePointer())throw new IOException("Truncated TAR padding");f.seek(f.getFilePointer()+padded);if(++entries>1000000)throw new IOException("TAR has too many entries");
            }
            if(!ended||entries==0)throw new IOException("Choose a nonempty, complete TAR backup");
        }
    }
}
