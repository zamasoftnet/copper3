package jp.cssj.driver.ctip.tls;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DistributionTest {
    @TempDir static Path temp;static Path identity;static Path root;
    @BeforeAll static void setup()throws Exception{root=Paths.get(System.getProperty("stage1.root"));identity=LocalTls.generateIdentity(temp);}
    static String run(String java,List<String> options,String cp,String... args)throws Exception {
        List<String> cmd=new ArrayList<String>();cmd.add(java);cmd.addAll(options);cmd.add("-cp");cmd.add(cp);cmd.add(DistributionProbe.class.getName());cmd.addAll(Arrays.asList(args));
        Path log=temp.resolve(UUID.randomUUID()+".log");Process child=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();boolean done;
        try{done=child.waitFor(25,TimeUnit.SECONDS);}finally{LocalTls.stop(child);}
        String output=new String(Files.readAllBytes(log),"UTF-8");System.out.println(output);assertTrue(done,output);assertEquals(0,child.exitValue(),output);assertTrue(output.contains("DISTRIBUTION_OK"),output);return output;
    }
    @ParameterizedTest @CsvSource({"min,plain","min,TLSv1.2","min,TLSv1.3","full,plain","full,TLSv1.2","full,TLSv1.3"})
    void completedJarsOnJdk21(String kind,String protocol)throws Exception{jar(kind,protocol,LocalTls.javaTool("java"));}
    @ParameterizedTest @CsvSource({"min,plain","min,TLSv1.2","full,plain","full,TLSv1.2"})
    void completedJarsOnJava8(String kind,String protocol)throws Exception {
        String java=System.getProperty("stage1.java8");assertNotNull(java,"Set -Dstage1.java8 to a Java 8 java executable; do not silently skip");
        assertTrue(Files.isRegularFile(Paths.get(java)));String output=jar(kind,protocol,java);assertTrue(output.contains("JAVA=1.8."),output);
    }
    String jar(String kind,String protocol,String java)throws Exception {
        Path jar=root.resolve("cti-java/build/release/cti-driver"+(kind.equals("min")?"-min":"")+"-2.2.4.jar");assertTrue(Files.isRegularFile(jar),"Build :cti-java:release before distribution tests");
        String cp=jar+File.pathSeparator+root.resolve("cti-java/cti-driver-ctip/build/classes/java/tlsTest");
        try(NativeServerProbe.Server server=new NativeServerProbe.Server(identity,protocol)) {
            return run(java,Arrays.asList("-Djavax.net.ssl.trustStore="+identity,"-Djavax.net.ssl.trustStorePassword=ephemeral-test-only","-Djavax.net.ssl.trustStoreType=PKCS12"),cp,kind,server.uri.toString());
        }
    }
    Path profile()throws Exception {
        Path fonts=root.resolve("sakae/sakae-pdf/src/main/resources/jp/cssj/sakae/pdf/font/builtin");
        try(java.util.stream.Stream<Path> paths=Files.walk(fonts)){for(Iterator<Path> it=paths.iterator();it.hasNext();){Path p=it.next();Path dest=temp.resolve(fonts.relativize(p));if(Files.isDirectory(p))Files.createDirectories(dest);else Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING);}}
        Path profile=temp.resolve("default.properties");Files.write(profile,"system.fonts=fonts.xml\n".getBytes("UTF-8"));
        return profile;
    }
    @Test void productPluginDiscoveryAndDirectPdf()throws Exception {
        Path profile=profile();String cp=System.getProperty("stage1.product.classpath")+File.pathSeparator+root.resolve("cti-java/cti-driver-ctip/build/classes/java/tlsTest");
        run(LocalTls.javaTool("java"),Arrays.asList("-Djp.cssj.driver.default="+profile),cp,"product","copper:direct:");
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"plain","TLSv1.2","TLSv1.3"})
    void nativeServerRendersPdf(String protocol)throws Exception {
        String saved=System.getProperty("jp.cssj.driver.default");System.setProperty("jp.cssj.driver.default",profile().toString());
        try(NativeServerProbe.Server server=new NativeServerProbe.Server(identity,protocol,new jp.cssj.homare.driver.DirectDriver())) {
            String cp=root.resolve("cti-java/build/release/cti-driver-min-2.2.4.jar")+File.pathSeparator+root.resolve("cti-java/cti-driver-ctip/build/classes/java/tlsTest");
            run(LocalTls.javaTool("java"),Arrays.asList("-Djavax.net.ssl.trustStore="+identity,"-Djavax.net.ssl.trustStorePassword=ephemeral-test-only","-Djavax.net.ssl.trustStoreType=PKCS12"),cp,"pdf",server.uri.toString());
        }finally{if(saved==null)System.clearProperty("jp.cssj.driver.default");else System.setProperty("jp.cssj.driver.default",saved);}
    }
    @Test void nativeV1FromBaselineProductRendersPdf()throws Exception {
        Path product=root.resolve("build/stage1-old-jars/cssj-copper.jar");assertTrue(Files.isRegularFile(product));
        String saved=System.getProperty("jp.cssj.driver.default");System.setProperty("jp.cssj.driver.default",profile().toString());
        jp.cssj.cti2.CTIDriver direct=new jp.cssj.homare.driver.DirectDriver();
        try(java.net.URLClassLoader loader=new java.net.URLClassLoader(new java.net.URL[]{product.toUri().toURL()},getClass().getClassLoader());
            NativeServerProbe.Server server=new NativeServerProbe.Server(identity,"plain",direct)) {
            jp.cssj.server.socket.ProtocolHandler handler=(jp.cssj.server.socket.ProtocolHandler)loader.loadClass("jp.cssj.copper.v1.V1ProtocolHandler")
                .getConstructor(java.net.URI.class,jp.cssj.cti2.CTIDriver.class).newInstance(java.net.URI.create("copper:direct:"),direct);
            server.server.setProtocolHandlers(new jp.cssj.server.socket.ProtocolHandler[]{handler});
            String cp=root.resolve("cti-java/build/release/cti-driver-min-2.2.4.jar")+File.pathSeparator+root.resolve("cti-java/cti-driver-ctip/build/classes/java/tlsTest");
            run(LocalTls.javaTool("java"),Collections.<String>emptyList(),cp,"pdf",server.uri+"&version=1");
        }finally{if(saved==null)System.clearProperty("jp.cssj.driver.default");else System.setProperty("jp.cssj.driver.default",saved);}
    }
    @ParameterizedTest @CsvSource({"21,http","21,https","8,http","8,https"})
    void fullJarRest(String runtime,String protocol)throws Exception {
        String java=runtime.equals("8")?System.getProperty("stage1.java8"):LocalTls.javaTool("java");assertNotNull(java);
        java.net.InetSocketAddress address=new java.net.InetSocketAddress("127.0.0.1",0);
        com.sun.net.httpserver.HttpServer server;
        if(protocol.equals("https")) {
            com.sun.net.httpserver.HttpsServer https=com.sun.net.httpserver.HttpsServer.create(address,0);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(new LocalTls(identity).server));server=https;
        }else server=com.sun.net.httpserver.HttpServer.create(address,0);
        java.util.concurrent.atomic.AtomicInteger conversions=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",exchange->{
            try {
                NativeServerProbe.read(exchange.getRequestBody());String path=exchange.getRequestURI().getPath();byte[] body;
                if(path.equals("/result"))body=new byte[]{1,2,3,4,5};
                else {
                    String xml;
                    if(path.equals("/open"))xml="<response><message code=\"1012\">test</message></response>";
                    else if(path.equals("/messages"))xml="<response><message code=\"1011\">done</message><result uri=\"test:/result\" mimeType=\"application/octet-stream\" length=\"5\"/></response>";
                    else {if(path.equals("/transcode"))conversions.incrementAndGet();xml="<response><message code=\"1011\">ok</message></response>";}
                    body=xml.getBytes("UTF-8");
                }
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}
        });server.start();
        try {
            String cp=root.resolve("cti-java/build/release/cti-driver-2.2.4.jar")+File.pathSeparator+root.resolve("cti-java/cti-driver-ctip/build/classes/java/tlsTest");
            run(java,Arrays.asList("-Djavax.net.ssl.trustStore="+identity,"-Djavax.net.ssl.trustStorePassword=ephemeral-test-only","-Djavax.net.ssl.trustStoreType=PKCS12"),cp,"full",protocol+"://localhost:"+server.getAddress().getPort()+"/");
            assertEquals(1,conversions.get());
        }finally{server.stop(0);}
    }
}
