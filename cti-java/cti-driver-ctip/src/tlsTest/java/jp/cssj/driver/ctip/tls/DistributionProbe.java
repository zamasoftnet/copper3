package jp.cssj.driver.ctip.tls;
import java.io.*;
import java.net.URI;
import java.nio.channels.Selector;
import java.util.*;
import jp.cssj.cti2.*;
import jp.cssj.cti2.results.SingleResult;
import jp.cssj.resolver.helpers.MetaSourceImpl;

/** Deliberately depends only on the old public API; runs with distribution JARs. */
public class DistributionProbe {
    public static void main(String[] args)throws Exception {
        try(Selector warmup=Selector.open()) { }
        URI uri=URI.create(args[1]);
        System.out.println("JAVA="+System.getProperty("java.version"));
        System.out.println("DRIVER="+CTIDriverManager.getDriver(uri).getClass().getName());
        if(args[0].equals("full"))System.out.println("REST="+CTIDriverManager.getDriver(URI.create("https://localhost/")).getClass().getName());
        boolean pdf=args[0].equals("product")||args[0].equals("pdf");
        byte[] input=pdf?"<html><body><p>Stage 1 PDF</p></body></html>".getBytes("UTF-8"):new byte[]{1,2,3,4,5};
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(CTISession session=CTIDriverManager.getSession(uri)) {
            session.setResults(new SingleResult(out));
            try(OutputStream stream=session.transcode(new MetaSourceImpl(URI.create("test:/main"),pdf?"text/html":"application/octet-stream","UTF-8",input.length))){stream.write(input);}
        }
        if(pdf){if(!new String(out.toByteArray(),0,5,"US-ASCII").equals("%PDF-"))throw new AssertionError("PDF header");}
        else if(!Arrays.equals(input,out.toByteArray()))throw new AssertionError("distributed JAR round trip");
        System.out.println("DISTRIBUTION_OK bytes="+out.size());
    }
}
