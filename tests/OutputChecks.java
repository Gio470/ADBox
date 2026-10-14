package dev.adbox.toolkit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic burst/clock fixtures: output is bounded, exact, and completion is never throttled. */
public final class OutputChecks {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static InputStream chunks(String text,int size){
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayInputStream(bytes){
            @Override public synchronized int read(byte[] out,int offset,int length){return super.read(out,offset,Math.min(size,length));}
            @Override public synchronized int available(){return 0;}
        };
    }
    public static void main(String[] args)throws Exception{
        String marker="\n__OUTPUT_END__:",body="hello 照片\n".repeat(1500);
        List<String> updates=new ArrayList<>();
        AdbResponses.ShellResult burst=AdbResponses.readShell(chunks(body+marker+"7\n",97),marker,64000,updates::add,false,50,()->0L);
        check(burst.text.equals(body),"throttling preserves all decoded UTF-8 output");
        check(burst.exitCode==7&&!burst.truncated,"exit status is independent of progress throttling");
        check(updates.size()==2,"a stationary-clock burst emits first snapshot and final output only");
        check(updates.get(updates.size()-1).equals(body),"completion always publishes the final output");
        updates.clear();AtomicLong clock=new AtomicLong();
        AdbResponses.readShell(chunks("a".repeat(2000)+marker+"0\n",100),marker,64000,updates::add,false,50,()->clock.getAndAdd(10_000_000));
        check(updates.size()>2&&updates.size()<10,"progress remains live at the configured clock cadence");
        check(updates.get(updates.size()-1).length()==2000,"periodic progress retains exact final content");
        updates.clear();
        AdbResponses.ShellResult clipped=AdbResponses.readShell(chunks(body+marker+"0\n",127),marker,1024,updates::add,true,50,()->0L);
        check(clipped.truncated&&clipped.text.endsWith("hello 照片\n"),"continuous commands retain the latest bounded output");
        check(updates.get(updates.size()-1).equals(clipped.text),"clipped completion is published even inside a throttle interval");
        updates.clear();
        AdbResponses.ShellResult limited=AdbResponses.readShell(chunks("x".repeat(5000),100),marker,1024,updates::add,false,50,()->0L);
        check(limited.truncated&&limited.text.endsWith("[Output limit reached]"),"output limit still stops a normal command");
        check(updates.get(updates.size()-1).equals(limited.text),"limit termination is never throttled");
        try{AdbResponses.readShell(chunks("unfinished",1),marker,1024,null,false,50,()->0L);throw new AssertionError("Premature close accepted");}catch(EOFException expected){checks++;}
        try{AdbResponses.readShell(chunks("",1),marker,1024,null,false,-1,()->0L);throw new AssertionError("Negative interval accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" bounded output, UI cadence, UTF-8 and final-status checks.");
    }
}
