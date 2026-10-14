import dev.adbox.toolkit.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class CoreChecks {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static void header(OutputStream out,String id,int value)throws Exception{out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(AdbWireConnection.code(id)).putInt(value).array());}
    static class FakeChannel implements AdbChannel{
        final ByteArrayInputStream in;final ByteArrayOutputStream out=new ByteArrayOutputStream();boolean closed;
        FakeChannel(byte[] response){in=new ByteArrayInputStream(response);}
        public InputStream input(){return in;}public OutputStream output(){return out;}public void close(){closed=true;}
    }
    static class Frame {int cmd,a,b;byte[] payload;}
    static Frame readFrame(InputStream in)throws Exception{
        ByteBuffer h=ByteBuffer.wrap(AdbWireConnection.readFully(in,24)).order(ByteOrder.LITTLE_ENDIAN);
        Frame f=new Frame();f.cmd=h.getInt();f.a=h.getInt();f.b=h.getInt();int length=h.getInt(),sum=h.getInt(),magic=h.getInt();
        check(magic==(f.cmd^0xffffffff),"outgoing ADB magic");f.payload=AdbWireConnection.readFully(in,length);check(sum==AdbWireConnection.sum(f.payload),"outgoing ADB checksum");return f;
    }
    static void frame(OutputStream out,String command,int a,int b,byte[] payload)throws Exception{
        int cmd=AdbWireConnection.code(command);out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a).putInt(b).putInt(payload.length).putInt(AdbWireConnection.sum(payload)).putInt(cmd^0xffffffff).array());out.write(payload);out.flush();
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("adbox-test-identity-");
        AdbIdentity identity=AdbIdentity.loadOrCreate(dir.toFile());identity.certificate.checkValidity();identity.certificate.verify(identity.certificate.getPublicKey());
        check(Arrays.equals(identity.privateKey.getEncoded(),AdbIdentity.loadOrCreate(dir.toFile()).privateKey.getEncoded()),"persistent identity survives reload");
        byte[] challenge=bytes("trusted device challenge");byte[] token=MessageDigest.getInstance("SHA-1").digest(challenge);
        Signature verify=Signature.getInstance("SHA1withRSA");verify.initVerify(identity.certificate);verify.update(challenge);check(verify.verify(identity.signToken(token)),"ADB RSA signature signs the supplied SHA-1 token");
        String adbKey=new String(identity.adbPublicKey(),StandardCharsets.US_ASCII);byte[] struct=Base64.getDecoder().decode(adbKey.substring(0,adbKey.indexOf(' ')));
        ByteBuffer key=ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);check(struct.length==524&&key.getInt()==64,"Android ADB public key layout");check(adbKey.endsWith("adbox@android\0"),"public key identity terminator");

        PipedInputStream clientIn=new PipedInputStream(65536),serverIn=new PipedInputStream(65536);
        PipedOutputStream serverOut=new PipedOutputStream(clientIn),clientOut=new PipedOutputStream(serverIn);
        CompletableFuture<Integer> uploaded=new CompletableFuture<>();CompletableFuture<Void> authenticated=new CompletableFuture<>();
        Thread daemon=new Thread(()->{try{
            Frame cnxn=readFrame(serverIn);check(cnxn.cmd==AdbWireConnection.code("CNXN"),"client starts an ADB handshake");
            frame(serverOut,"AUTH",1,0,token);Frame signature=readFrame(serverIn);check(signature.a==2,"client supplies an RSA token signature");
            Signature verifier=Signature.getInstance("SHA1withRSA");verifier.initVerify(identity.certificate);verifier.update(challenge);check(verifier.verify(signature.payload),"daemon verifies the ADB signature");
            frame(serverOut,"AUTH",1,0,token);Frame publicKey=readFrame(serverIn);check(publicKey.a==3&&Arrays.equals(publicKey.payload,identity.adbPublicKey()),"untrusted device receives a key for user authorization");
            frame(serverOut,"CNXN",0x01000000,4096,bytes("device::ro.product.model=Fixture;\0"));authenticated.complete(null);
            int received=0;
            while(true){Frame f=readFrame(serverIn);
                if(f.cmd==AdbWireConnection.code("OPEN")){
                    frame(serverOut,"OKAY",f.a+100,f.a,new byte[0]);String service=new String(f.payload,StandardCharsets.UTF_8).replace("\0","");
                    if(service.startsWith("shell:")){frame(serverOut,"WRTE",f.a+100,f.a,bytes("hello "));frame(serverOut,"WRTE",f.a+100,f.a,bytes(service));frame(serverOut,"CLSE",f.a+100,f.a,new byte[0]);}
                }else if(f.cmd==AdbWireConnection.code("WRTE")){received+=f.payload.length;check(f.payload.length<=4096,"outgoing flow-control chunk size");frame(serverOut,"OKAY",f.b,f.a,new byte[0]);if(received>=12000)uploaded.complete(received);}
            }
        }catch(Exception e){if(!authenticated.isDone())authenticated.completeExceptionally(e);if(!uploaded.isDone())uploaded.completeExceptionally(e);}});
        daemon.setDaemon(true);daemon.start();
        AdbWireConnection connection=new AdbWireConnection(clientIn,clientOut,()->{clientIn.close();clientOut.close();serverIn.close();serverOut.close();},identity);
        check(connection.connect().contains("Fixture"),"authenticated connection banner");authenticated.get(3,TimeUnit.SECONDS);
        try(AdbChannel stream=connection.open("shell:first")){check(new String(stream.input().readAllBytes(),StandardCharsets.UTF_8).equals("hello shell:first"),"remote close preserves queued output");}
        try(AdbChannel stream=connection.open("shell:second")){check(new String(stream.input().readAllBytes(),StandardCharsets.UTF_8).equals("hello shell:second"),"successive ADB streams use separate IDs");}
        try(AdbChannel stream=connection.open("sync-test:")){stream.output().write(new byte[12000]);check(uploaded.get(3,TimeUnit.SECONDS)==12000,"large writes wait for ADB acknowledgements and preserve all bytes");}
        connection.close();check(!connection.isConnected(),"disconnect closes the connection");

        ByteArrayOutputStream directory=new ByteArrayOutputStream();
        for(String name:List.of("file.txt","照片")){byte[] utf=bytes(name);header(directory,"DENT",name.equals("file.txt")?0100644:0040755);directory.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(42).putInt(1234).putInt(utf.length).array());directory.write(utf);}
        header(directory,"DONE",0);directory.write(new byte[12]);FakeChannel listing=new FakeChannel(directory.toByteArray());List<SyncTransfer.Entry> entries=SyncTransfer.list(listing,"/sdcard");
        check(entries.size()==2&&entries.get(0).directory&&entries.get(0).name.equals("照片"),"SYNC directory listing supports Unicode and sorts folders first");check(entries.get(1).size==42&&listing.closed,"directory metadata and channel cleanup");
        ByteArrayOutputStream response=new ByteArrayOutputStream();header(response,"DATA",5);response.write(bytes("hello"));header(response,"DATA",6);response.write(bytes(" world"));header(response,"DONE",0);
        ByteArrayOutputStream saved=new ByteArrayOutputStream();check(SyncTransfer.pull(new FakeChannel(response.toByteArray()),"/sdcard/a b.txt",saved)==11&&saved.toString(StandardCharsets.UTF_8).equals("hello world"),"SYNC pull joins exact binary data");
        ByteArrayOutputStream okay=new ByteArrayOutputStream();header(okay,"OKAY",0);FakeChannel sender=new FakeChannel(okay.toByteArray());byte[] source=new byte[200000];new Random(42).nextBytes(source);check(SyncTransfer.push(sender,"/sdcard/new.bin",new ByteArrayInputStream(source))==source.length,"SYNC upload reports exact size");
        ByteArrayInputStream sent=new ByteArrayInputStream(sender.out.toByteArray());ByteBuffer sendHeader=ByteBuffer.wrap(AdbWireConnection.readFully(sent,8)).order(ByteOrder.LITTLE_ENDIAN);check(sendHeader.getInt()==AdbWireConnection.code("SEND"),"SYNC upload SEND frame");String destination=new String(AdbWireConnection.readFully(sent,sendHeader.getInt()),StandardCharsets.UTF_8);check(destination.equals("/sdcard/new.bin,33188"),"SYNC upload uses regular-file permissions");
        ByteArrayOutputStream recovered=new ByteArrayOutputStream();while(true){ByteBuffer h=ByteBuffer.wrap(AdbWireConnection.readFully(sent,8)).order(ByteOrder.LITTLE_ENDIAN);int id=h.getInt(),n=h.getInt();if(id==AdbWireConnection.code("DONE"))break;check(id==AdbWireConnection.code("DATA")&&n<=65536,"SYNC upload frame size");recovered.write(AdbWireConnection.readFully(sent,n));}check(Arrays.equals(source,recovered.toByteArray()),"SYNC upload byte integrity");
        ByteArrayOutputStream failure=new ByteArrayOutputStream();header(failure,"FAIL",17);failure.write(bytes("Permission denied"));try{SyncTransfer.pull(new FakeChannel(failure.toByteArray()),"/private",new ByteArrayOutputStream());throw new AssertionError("SYNC errors must propagate");}catch(IOException expected){check(expected.getMessage().equals("Permission denied"),"device permission failure reaches caller");}
        ByteArrayOutputStream malformed=new ByteArrayOutputStream();header(malformed,"DATA",70000);try{SyncTransfer.pull(new FakeChannel(malformed.toByteArray()),"/x",new ByteArrayOutputStream());throw new AssertionError("oversized frame accepted");}catch(IOException expected){check(expected.getMessage().contains("Invalid file data"),"malformed file frames rejected before allocation");}
        String marker=AdbResponses.marker();String command="printf '%s' \"a'b\"; exit 7";
        Process process=new ProcessBuilder("sh","-c",AdbResponses.shell(command,marker)).start();
        AdbResponses.ShellResult result=AdbResponses.readShell(process.getInputStream(),marker,1024,null);
        check(result.text.equals("a'b")&&result.exitCode==7,"shell quoting and exit status survive a child command exit");process.waitFor();
        byte[] fragments=bytes("hello 照片"+marker+"0\n");
        InputStream fragmented=new InputStream(){int p;public int read()throws IOException{if(p==fragments.length)throw new IOException("Must not wait for remote close");return fragments[p++]&255;}public int read(byte[] b,int off,int len)throws IOException{int value=read();b[off]=(byte)value;return 1;}};
        check(AdbResponses.readShell(fragmented,marker,1024,null).text.equals("hello 照片"),"fragmented UTF-8 and completion marker require no remote EOF");
        try{AdbResponses.readShell(new ByteArrayInputStream(bytes("incomplete")),marker,1024,null);throw new AssertionError("premature close accepted");}catch(EOFException expected){check(true,"premature command close is reported");}
        check(AdbResponses.readShell(new ByteArrayInputStream(bytes("1234567890")),marker,5,null).truncated,"command output is bounded");
        ByteArrayOutputStream png=new ByteArrayOutputStream();png.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        for(String type:List.of("IHDR","IEND")){byte[] data=type.equals("IHDR")?new byte[]{0,0,0,1,0,0,0,1,8,6,0,0,0}:new byte[0];png.write(ByteBuffer.allocate(4).putInt(data.length).array());png.write(bytes(type));png.write(data);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(bytes(type));crc.update(data);png.write(ByteBuffer.allocate(4).putInt((int)crc.getValue()).array());}
        byte[] image=png.toByteArray();check(Arrays.equals(image,AdbResponses.readPng(new ByteArrayInputStream(image),1024)),"PNG completion uses IEND and validates chunk checksums");
        byte[] corrupt=image.clone();corrupt[20]^=1;try{AdbResponses.readPng(new ByteArrayInputStream(corrupt),1024);throw new AssertionError("corrupt PNG accepted");}catch(IOException expected){check(expected.getMessage().contains("corrupt"),"corrupt screenshot is reported");}
        Path partial=Files.createTempDirectory("adbox-partial-identity-");Files.copy(dir.resolve("identity.pk8"),partial.resolve("identity.pk8"));try{AdbIdentity.loadOrCreate(partial.toFile());throw new AssertionError("incomplete identity silently replaced");}catch(GeneralSecurityException expected){check(expected.getMessage().contains("incomplete"),"incomplete identity is preserved for explicit recovery");}
        try(var paths=Files.walk(partial)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
        System.out.println("PASS: "+checks+" protocol, authentication, identity, file-transfer, and failure checks.");
    }
}
