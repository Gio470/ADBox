package dev.adbox.toolkit;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;

/** A persistent, app-private RSA identity for USB ADB. */
public final class AdbIdentity {
    public final PrivateKey privateKey;
    public final X509Certificate certificate;
    public AdbIdentity(PrivateKey key, X509Certificate cert) { privateKey = key; certificate = cert; }

    public static AdbIdentity loadOrCreate(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create ADB identity directory");
        File keyFile = new File(directory, "identity.pk8"), certFile = new File(directory, "identity.crt");
        if (keyFile.isFile() && certFile.isFile()) {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            X509Certificate cert = (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
            // Refuse mismatched files instead of silently changing the trusted identity.
            byte[] challenge = new byte[20]; new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update(challenge);
            Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(cert); verifier.update(challenge);
            if (!verifier.verify(signer.sign())) throw new GeneralSecurityException("The saved ADB identity is inconsistent");
            return new AdbIdentity(key, cert);
        }
        if (keyFile.exists() || certFile.exists()) throw new GeneralSecurityException("The saved ADB identity is incomplete. Clear ADBox's app storage to create a new identity.");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        byte[] algorithm = seq(oid(1,2,840,113549,1,1,11), der(5,new byte[0]));
        byte[] name = seq(der(0x31, seq(oid(2,5,4,3), der(12,"ADBox ADB".getBytes(StandardCharsets.UTF_8)))));
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss'Z'",Locale.US); format.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] validity = seq(der(24,format.format(new Date(now-86400000L)).getBytes(StandardCharsets.US_ASCII)),der(24,format.format(new Date(now+315360000000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs = seq(der(0xa0,integer(BigInteger.valueOf(2))),integer(new BigInteger(128,new SecureRandom()).add(BigInteger.ONE)),algorithm,name,validity,name,pair.getPublic().getEncoded());
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(pair.getPrivate()); signature.update(tbs);
        byte[] encoded = seq(tbs,algorithm,der(3,join(new byte[]{0},signature.sign())));
        X509Certificate cert = (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
        cert.verify(pair.getPublic());
        writePrivate(keyFile,pair.getPrivate().getEncoded()); writePrivate(certFile,encoded);
        return new AdbIdentity(pair.getPrivate(),cert);
    }
    private static void writePrivate(File path,byte[] bytes) throws IOException {
        File temp = new File(path.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
        temp.setReadable(false,false);temp.setWritable(false,false);temp.setReadable(true,true);temp.setWritable(true,true);
        if(!temp.renameTo(path))throw new IOException("Cannot save ADB identity");
    }
    public byte[] signToken(byte[] token) throws Exception {
        if(token.length!=20)throw new GeneralSecurityException("Unexpected ADB authentication token length");
        byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
        Signature signature=Signature.getInstance("NONEwithRSA");signature.initSign(privateKey);signature.update(prefix);signature.update(token);return signature.sign();
    }
    public byte[] adbPublicKey() {
        RSAPublicKey pub=(RSAPublicKey)certificate.getPublicKey();BigInteger n=pub.getModulus(),mod=BigInteger.ONE.shiftLeft(32);
        long inverse=n.modInverse(mod).negate().mod(mod).longValue();
        BigInteger rr=BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer buffer=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);buffer.putInt(64).putInt((int)inverse);buffer.put(little(n,256));buffer.put(little(rr,256));buffer.putInt(pub.getPublicExponent().intValue());
        return (Base64.getEncoder().encodeToString(buffer.array())+" adbox@android\0").getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] little(BigInteger n,int length){byte[] big=n.toByteArray(),out=new byte[length];for(int i=0;i<length&&i<big.length;i++)out[i]=big[big.length-1-i];return out;}
    private static byte[] integer(BigInteger n){return der(2,n.toByteArray());}
    private static byte[] seq(byte[]...parts){return der(0x30,join(parts));}
    private static byte[] join(byte[]...parts){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] p:parts)out.write(p,0,p.length);return out.toByteArray();}
    private static byte[] der(int tag,byte[] data){ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);if(data.length<128)out.write(data.length);else{int n=data.length,count=0;while(n>0){count++;n>>>=8;}out.write(0x80|count);for(int i=count-1;i>=0;i--)out.write((data.length>>>(8*i))&255);}out.write(data,0,data.length);return out.toByteArray();}
    private static byte[] oid(int...arcs){ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(arcs[0]*40+arcs[1]);for(int i=2;i<arcs.length;i++){int n=arcs[i],count=1;for(int t=n;t>=128;t>>>=7)count++;for(int b=count-1;b>=0;b--)out.write(((n>>>(7*b))&127)|(b>0?128:0));}return der(6,out.toByteArray());}
}
