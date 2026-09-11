package jp.cssj.driver.ctip.tls;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.net.ssl.SSLServerSocket;
import jp.cssj.cti2.*;
import jp.cssj.cti2.results.*;
import jp.cssj.driver.ctip.v2.*;
import jp.cssj.plugin.PluginLoader;
import jp.cssj.resolver.*;
import jp.cssj.resolver.helpers.MetaSourceImpl;
import jp.cssj.rsr.*;
import jp.cssj.server.acl.Acl;
import jp.cssj.server.socket.*;
import jp.cssj.server.socket.ctip.v2.V2ProtocolHandler;

/** Runs the unmodified native CTIServer listener with a deterministic conversion backend. */
public class NativeServerProbe {
    static void check(boolean b,String message) {if(!b)throw new AssertionError(message);}
    static Object field(Object o,Class<?> c,String name) throws Exception {Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static final class Backend implements InvocationHandler {
        Results results; RandomBuilder builder; boolean continuous, abort; SourceResolver resolver;
        final Map<String,String> properties=new HashMap<String,String>();
        public Object invoke(Object proxy,Method method,Object[] args) throws Throwable {
            switch(method.getName()) {
            case "setResults": results=(Results)args[0];return null;
            case "setSourceResolver":resolver=(SourceResolver)args[0];return null;
            case "property": properties.put((String)args[0],(String)args[1]);abort="readable".equals(properties.get("test.abort"));return null;
            case "setContinuous":continuous=(Boolean)args[0];return null;
            case "resource": if(args[0] instanceof Source) {try(InputStream in=((Source)args[0]).getInputStream()){read(in);}} return null;
            case "transcode":
                byte[] bytes;
                if(args[0] instanceof Source) {try(InputStream in=((Source)args[0]).getInputStream()){bytes=read(in);}}
                else if(resolver!=null) {Source source=resolver.resolve((URI)args[0]);try(InputStream in=source.getInputStream()){bytes=read(in);}finally{resolver.release(source);}}
                else {bytes="server-main".getBytes("UTF-8");}
                if("empty".equals(properties.get("test.abort")))throw new TranscoderException(TranscoderException.STATE_READABLE,(short)1,new String[0],"readable without output");
                URI resultUri=URI.create("test:/result");
                if("true".equals(properties.get("test.long-result"))){char[] chars=new char[32768];Arrays.fill(chars,'a');resultUri=URI.create("test:/"+new String(chars));}
                if(builder==null)builder=results.nextBuilder(new MetaSourceImpl(resultUri,"application/octet-stream",null,-1));
                ((Sequential)builder).write(bytes,0,bytes.length);
                if(abort) {builder=null;throw new TranscoderException(TranscoderException.STATE_READABLE,(short)1,new String[0],"readable");}
                if(!continuous){builder.finish();builder.dispose();builder=null;results.end();}return null;
            case "join": if(builder!=null){builder.finish();builder.dispose();builder=null;}results.end();return null;
            case "reset":properties.clear();continuous=false;abort=false;builder=null;results=null;resolver=null;return null;
            case "getServerInfo":return new ByteArrayInputStream("<info/>".getBytes("UTF-8"));
            default:return null;
            }
        }
    }
    static byte[] read(InputStream in) throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
    static final class Server implements AutoCloseable {
        final CTIServer server=new CTIServer(); final URI uri;
        Server(Path identity,String protocol) throws Exception {
            this(identity,protocol,null);
        }
        Server(Path identity,String protocol,CTIDriver conversionDriver) throws Exception {
            PluginLoader.getPluginLoader().add(Acl.class,new Acl(){public boolean match(Object key){return true;}public boolean checkAccess(InetAddress a){return a.isLoopbackAddress();}});
            CTIDriver driver=new CTIDriver(){public boolean match(URI u){return true;}public CTISession getSession(URI u,Map<String,String> props){return (CTISession)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CTISession.class},new Backend());}};
            server.setProtocolHandlers(new ProtocolHandler[]{new V2ProtocolHandler(URI.create(conversionDriver==null?"test:/":"copper:direct:"),conversionDriver==null?driver:conversionDriver)});
            Properties p=new Properties();p.setProperty("jp.cssj.cssjd.minThreads","2");p.setProperty("jp.cssj.cssjd.maxThreads","4");p.setProperty("jp.cssj.cssjd.timeout","2");
            boolean tls=!protocol.equals("plain");
            p.setProperty(tls?"jp.cssj.cssjd.tls.port":"jp.cssj.cssjd.port","0");
            p.setProperty("jp.cssj.cssjd.tls.keyStore",identity.getFileName().toString());p.setProperty("jp.cssj.cssjd.tls.keyStorePassword","ephemeral-test-only");p.setProperty("jp.cssj.cssjd.tls.keyPassword","ephemeral-test-only");
            server.setConfigFile(identity.resolveSibling("test.properties").toFile(),p);server.startup();
            ServerSocket socket=(ServerSocket)field(server,CTIServer.class,tls?"tlsServerSocket":"serverSocket");
            check(socket!=null,"Native TLS listener not started");if(tls)((SSLServerSocket)socket).setEnabledProtocols(new String[]{protocol});
            uri=URI.create((tls?"ctips":"ctip")+"://localhost:"+socket.getLocalPort()+"/?timeout=3000");
        }
        public void close() throws Exception {
            // CTIServer.shutdown holds the server monitor while joining workers.
            // Stop only after requests finish, so a worker is not entering freeThread.
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(true) {
                synchronized(server) {
                    if(server.getFreeThreads()==server.getTotalThreads()){server.shutdown();return;}
                }
                if(System.nanoTime()>=deadline)throw new AssertionError("Native server did not become idle before shutdown");
                Thread.sleep(10);
            }
        }
    }
    static final class Session extends V2Session {
        boolean defer;
        Session(URI u)throws IOException{super(u,"UTF-8","","");}
        protected void next() throws IOException {if(!defer)super.next();}
        void finishBodyWithoutClosingStream() throws Exception {
            request.eof();Field f=V2Session.class.getDeclaredField("sendingBody");f.setAccessible(true);f.setBoolean(this,false);
        }
        void consume()throws IOException{super.next();}
    }
    static final MetaSource META=new MetaSourceImpl(URI.create("test:/main"),"application/octet-stream",null,-1);
    static byte[] convert(Session s,byte[] data)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();s.setResults(new SingleResult(out));try(OutputStream in=s.transcode(META)){in.write(data);}return out.toByteArray();}
    public static void main(String[] args)throws Exception {
        String scenario=args[0];Path identity=Paths.get(args[1]);
        try(java.nio.channels.Selector warmup=java.nio.channels.Selector.open()) { }
        System.clearProperty(TLSPolicy.LEGACY_TRUST);System.clearProperty(TLSPolicy.INSECURE);
        System.setProperty("javax.net.ssl.trustStore",identity.toString());System.setProperty("javax.net.ssl.trustStorePassword","ephemeral-test-only");
        String protocol=scenario.startsWith("tls12")?"TLSv1.2":scenario.startsWith("tls13")?"TLSv1.3":"plain";
        try(Server server=new Server(identity,protocol);Session s=new Session(server.uri)) {
            byte[] data=new byte[2*1024*1024+37];new Random(4).nextBytes(data);
            if(scenario.startsWith("reset-")) reset(s,server,scenario);
            else {
                check(Arrays.equals(data,convert(s,data)),"large native round trip");
                check(Arrays.equals(new byte[]{7},convert(s,new byte[]{7})),"repeated native round trip");
                ByteArrayOutputStream out=new ByteArrayOutputStream();s.setResults(new SingleResult(out));s.setContinuous(true);
                try(OutputStream in=s.transcode(META)){in.write(new byte[]{1,2});}
                try(OutputStream in=s.transcode(META)){in.write(new byte[]{3,4});}
                s.join();check(Arrays.equals(out.toByteArray(),new byte[]{1,2,3,4}),"continuous join");s.setContinuous(false);
                s.property("test.abort","readable");out=new ByteArrayOutputStream();s.setResults(new SingleResult(out));
                try {try(OutputStream in=s.transcode(META)){in.write(new byte[]{5,6});}throw new AssertionError("no abort");}
                catch(TranscoderException expected){check(expected.getState()==TranscoderException.STATE_READABLE,"readable state");}
                check(Arrays.equals(out.toByteArray(),new byte[]{5,6}),"flushed partial result");
                s.property("test.abort","off");check(Arrays.equals(new byte[]{9},convert(s,new byte[]{9})),"reuse after START_MAIN abort");
                s.property("test.abort","readable");s.setResults(new SingleResult(new ByteArrayOutputStream()));
                try{s.transcode(URI.create("test:/server"));throw new AssertionError("no server abort");}catch(TranscoderException expected){}
                s.property("test.abort","off");check(Arrays.equals(new byte[]{10},convert(s,new byte[]{10})),"reuse after SERVER_MAIN abort");
                s.property("test.abort","empty");
                try{convert(s,new byte[]{11});throw new AssertionError("no empty START_MAIN abort");}catch(TranscoderException expected){}
                s.property("test.abort","off");check(Arrays.equals(new byte[]{12},convert(s,new byte[]{12})),"reuse after empty START_MAIN abort");
                s.property("test.abort","empty");s.setResults(new SingleResult(new ByteArrayOutputStream()));
                try{s.transcode(URI.create("test:/empty"));throw new AssertionError("no empty SERVER_MAIN abort");}catch(TranscoderException expected){}
                s.property("test.abort","off");check(Arrays.equals(new byte[]{13},convert(s,new byte[]{13})),"reuse after empty SERVER_MAIN abort");
            }
        }
        System.out.println("NATIVE_OK "+scenario);
    }
    static void reset(Session s,Server server,String scenario)throws Exception {
        OutputStream old;
        long before;
        if(scenario.equals("reset-resource")||scenario.equals("reset-reconnect-old")) {
            old=s.resource(META);old.write(1);before=server.server.getAccessCount();s.reset();
            check(server.server.getAccessCount()>before,"resource reset must reconnect");
        } else {
            if(scenario.contains("next")||scenario.equals("reset-reuse-old"))s.setContinuous(true);
            s.setResults(new SingleResult(new ByteArrayOutputStream()));old=s.transcode(META);old.write(1);
            s.finishBodyWithoutClosingStream();
            if(scenario.equals("reset-after-next")||scenario.equals("reset-reuse-old"))s.consume();
            before=server.server.getAccessCount();long start=System.nanoTime();s.reset();
            check(server.server.getAccessCount()==before,"drained response should reuse connection");
            check((System.nanoTime()-start)/1000000<900,"drain should not wait for timeout");
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();s.setResults(new SingleResult(out));OutputStream current=s.transcode(META);
        try{old.write(99);throw new AssertionError("old stream accepted write");}catch(IllegalStateException expected){}
        old.close();current.write(42);current.close();check(Arrays.equals(out.toByteArray(),new byte[]{42}),"reset next conversion");
    }
}

