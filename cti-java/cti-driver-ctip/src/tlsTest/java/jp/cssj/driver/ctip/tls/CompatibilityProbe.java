package jp.cssj.driver.ctip.tls;

import java.io.*;
import java.net.*;
import java.nio.channels.Selector;
import java.nio.file.*;
import java.util.*;
import jp.cssj.cti2.*;
import jp.cssj.cti2.results.SingleResult;
import jp.cssj.resolver.helpers.MetaSourceImpl;

/** Isolates the baseline CTIP implementation while sharing only the old public API. */
public class CompatibilityProbe {
    static byte[] convert(CTISession s, byte[] bytes) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();s.setResults(new SingleResult(out));
        try(OutputStream stream=s.transcode(new MetaSourceImpl(URI.create("test:/main"),"application/octet-stream",null,bytes.length))){stream.write(bytes);}
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        try(Selector warmup=Selector.open()) { }
        boolean old=args[0].equals("old-client");String scenario=args[2];Path identity=Paths.get(args[3]);
        if(!scenario.equals("default")) {
            System.setProperty("javax.net.ssl.trustStore",identity.toString());
            System.setProperty("javax.net.ssl.trustStorePassword","ephemeral-test-only");
            System.setProperty("javax.net.ssl.trustStoreType","PKCS12");
        }
        try(NativeServerProbe.Server server=new NativeServerProbe.Server(identity,args[1]);
            URLClassLoader loader=new URLClassLoader(new URL[]{Paths.get(args[4]).toUri().toURL()},CompatibilityProbe.class.getClassLoader()) {
                protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                    if(name.startsWith("jp.cssj.driver.ctip.")&&!name.startsWith("jp.cssj.driver.ctip.tls.")) {
                        Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;
                    }return super.loadClass(name,resolve);
                }
            }) {
            CTIDriver driver=(CTIDriver)loader.loadClass("jp.cssj.driver.ctip.CTIPDriver").newInstance();
            System.out.println("CLIENT="+driver.getClass().getProtectionDomain().getCodeSource().getLocation());
            System.out.println("SERVER="+jp.cssj.server.socket.ctip.v2.V2ProtocolProcessor.class.getProtectionDomain().getCodeSource().getLocation());
            try(CTISession s=driver.getSession(server.uri,Collections.<String,String>emptyMap())) {
                if(scenario.equals("long")) {char[] chars=new char[32768];Arrays.fill(chars,'a');s.property("large",new String(chars));}
                if(scenario.equals("long-response"))s.property("test.long-result","true");
                if(scenario.equals("abort")) {
                    s.property("test.abort","readable");
                    try {convert(s,new byte[]{7});System.out.println("ABORT_NOT_OBSERVED");}
                    catch(TranscoderException e){System.out.println("ABORT_OBSERVED="+e.getState());}
                    s.property("test.abort","off");
                }
                if(!Arrays.equals(new byte[]{1,2,3},convert(s,new byte[]{1,2,3})))throw new AssertionError("round trip mismatch");
            }
            System.out.println("COMPAT_OK");
        }
    }
}
