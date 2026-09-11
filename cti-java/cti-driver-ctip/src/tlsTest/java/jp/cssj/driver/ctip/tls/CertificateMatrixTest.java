package jp.cssj.driver.ctip.tls;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import jp.cssj.cti2.TLSPolicy;
import jp.cssj.driver.ctip.v2.TLSSocketChannel;
import jp.cssj.driver.rest.RestSession;

class CertificateMatrixTest {
    @TempDir static Path temp;static final String PASS="ephemeral-test-only";
    static void keytool(String... args)throws Exception {
        List<String> cmd=new ArrayList<String>();cmd.add(LocalTls.javaTool("keytool"));cmd.addAll(Arrays.asList(args));
        Path log=temp.resolve("keytool-"+UUID.randomUUID()+".log");Process p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue(p.waitFor(20,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(Files.readAllBytes(log),"UTF-8"));}finally{LocalTls.stop(p);}
    }
    static void generate(String name,String san,boolean expired)throws Exception {
        keytool("-genkeypair","-alias","leaf","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-validity","2","-ext","SAN="+san,"-storetype","PKCS12","-keystore",temp.resolve(name+".p12").toString(),"-storepass",PASS,"-keypass",PASS,"-noprompt");
        keytool("-certreq","-alias","leaf","-keystore",temp.resolve(name+".p12").toString(),"-storepass",PASS,"-file",temp.resolve(name+".csr").toString());
        keytool("-gencert","-alias","ca","-keystore",temp.resolve("ca.p12").toString(),"-storepass",PASS,"-infile",temp.resolve(name+".csr").toString(),"-outfile",temp.resolve(name+".cer").toString(),"-startdate",expired?"2020/01/01 00:00:00":"-1d","-validity",expired?"1":"30","-ext","SAN="+san,"-ext","EKU=serverAuth");
        keytool("-importcert","-alias","ca","-keystore",temp.resolve(name+".p12").toString(),"-storepass",PASS,"-file",temp.resolve("ca.cer").toString(),"-noprompt");
        keytool("-importcert","-alias","leaf","-keystore",temp.resolve(name+".p12").toString(),"-storepass",PASS,"-file",temp.resolve(name+".cer").toString(),"-noprompt");
    }
    @BeforeAll static void setup()throws Exception {
        try(java.nio.channels.Selector warmup=java.nio.channels.Selector.open()) { }
        keytool("-genkeypair","-alias","ca","-keyalg","RSA","-keysize","2048","-dname","CN=Stage1 test CA","-ext","bc:c","-validity","365","-storetype","PKCS12","-keystore",temp.resolve("ca.p12").toString(),"-storepass",PASS,"-keypass",PASS,"-noprompt");
        keytool("-exportcert","-alias","ca","-keystore",temp.resolve("ca.p12").toString(),"-storepass",PASS,"-file",temp.resolve("ca.cer").toString());
        keytool("-importcert","-alias","ca","-keystore",temp.resolve("trust.p12").toString(),"-storetype","PKCS12","-storepass",PASS,"-file",temp.resolve("ca.cer").toString(),"-noprompt");
        generate("valid","dns:localhost,ip:127.0.0.1,ip:::1",false);generate("expired","dns:localhost,ip:127.0.0.1",true);
        LocalTls.generateIdentity(temp);
    }
    static SSLContext context(String name,boolean single)throws Exception {
        KeyStore ks=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(temp.resolve(name+".p12"))){ks.load(in,PASS.toCharArray());}
        if(single){Key key=ks.getKey("leaf",PASS.toCharArray());Certificate cert=ks.getCertificate("leaf");ks.deleteEntry("ca");ks.setKeyEntry("leaf",key,PASS.toCharArray(),new Certificate[]{cert});}
        KeyManagerFactory kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(ks,PASS.toCharArray());SSLContext c=SSLContext.getInstance("TLS");c.init(kmf.getKeyManagers(),null,null);return c;
    }
    @ParameterizedTest @CsvSource({
        "ctip,valid,localhost,true,false,false,true","rest,valid,localhost,true,false,false,true",
        "ctip,identity,localhost,false,false,false,false","rest,identity,localhost,false,false,false,false",
        "ctip,valid,127.0.0.2,true,false,false,false","rest,valid,127.0.0.2,true,false,false,false",
        "ctip,expired,localhost,true,false,false,false","rest,expired,localhost,true,false,false,false",
        "ctip,valid,127.0.0.1,true,false,false,true","rest,valid,127.0.0.1,true,false,false,true",
        "ctip,valid,::1,true,false,false,true","rest,valid,::1,true,false,false,true",
        "ctip,identity,localhost,false,true,false,true","rest,identity,localhost,false,true,false,true",
        "ctip,valid,localhost,false,true,false,true","rest,valid,localhost,false,true,false,false",
        "ctip,valid,localhost,false,true,true,true","rest,valid,localhost,false,true,true,true",
        "ctip,expired,localhost,false,true,true,true","rest,expired,localhost,false,true,true,true",
        "ctip,expired,localhost,false,true,false,true","rest,expired,localhost,false,true,false,false",
        "ctip,valid,127.0.0.2,true,true,false,true","rest,valid,127.0.0.2,true,true,false,true"})
    void certificates(String kind,String cert,String host,boolean trusted,boolean insecure,boolean single,boolean success)throws Exception {
        String[] names={TLSPolicy.INSECURE,TLSPolicy.LEGACY_TRUST,"javax.net.ssl.trustStore","javax.net.ssl.trustStorePassword","javax.net.ssl.trustStoreType"};String[] saved=new String[names.length];for(int i=0;i<names.length;i++){saved[i]=System.getProperty(names[i]);System.clearProperty(names[i]);}
        System.setProperty(TLSPolicy.INSECURE,String.valueOf(insecure));if(trusted){System.setProperty(names[2],temp.resolve("trust.p12").toString());System.setProperty(names[3],PASS);System.setProperty(names[4],"PKCS12");}
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(SSLServerSocket listener=(SSLServerSocket)context(cert,single).getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName(kind.equals("rest")?host:(host.equals("::1")?"::1":"127.0.0.1")))) {
            listener.setSoTimeout(4000);Future<List<SNIServerName>> peer=worker.submit(()->{
                try(SSLSocket sock=(SSLSocket)listener.accept()) {
                    sock.setSoTimeout(4000);sock.startHandshake();List<SNIServerName> sni=((ExtendedSSLSession)sock.getSession()).getRequestedServerNames();
                    if(kind.equals("rest")) {
                        BufferedReader r=new BufferedReader(new InputStreamReader(sock.getInputStream(),"US-ASCII"));String line;while((line=r.readLine())!=null&&!line.isEmpty()){}
                        byte[] body="<response><message code=\"1012\">test</message></response>".getBytes("UTF-8");OutputStream out=sock.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(body);out.flush();
                    }
                    return sni;
                } catch(IOException e){if(success)throw e;return Collections.emptyList();}
            });
            Exception failure=null;
            try {
                if(kind.equals("ctip"))try(TLSSocketChannel channel=new TLSSocketChannel(SocketChannel.open())) {channel.connect(new InetSocketAddress(host.equals("::1")?"::1":"127.0.0.1",listener.getLocalPort()),host,listener.getLocalPort(),3000);peer.get(5,TimeUnit.SECONDS);}
                else {
                    String authority=host.equals("::1")?"[::1]":host;
                    RestSession session=new RestSession(URI.create("https://"+authority+":"+listener.getLocalPort()+"/"),null,null);
                    java.lang.reflect.Field client=RestSession.class.getDeclaredField("client");client.setAccessible(true);((Closeable)client.get(session)).close();
                }
            }catch(Exception e){failure=e;}
            assertEquals(success,failure==null,String.valueOf(failure));
            if(failure!=null){assertTrue(failure instanceof SSLException,String.valueOf(failure));}
            List<SNIServerName> sni=peer.get(5,TimeUnit.SECONDS);
            if(success&&kind.equals("ctip")){if(host.equals("localhost"))assertTrue(sni.contains(new SNIHostName(host)));else assertTrue(sni.isEmpty());}
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));for(int i=0;i<names.length;i++){if(saved[i]==null)System.clearProperty(names[i]);else System.setProperty(names[i],saved[i]);}}
    }
}
