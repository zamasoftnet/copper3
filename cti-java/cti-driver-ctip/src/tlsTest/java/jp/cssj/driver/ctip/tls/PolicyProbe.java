package jp.cssj.driver.ctip.tls;
import java.util.logging.*;
import jp.cssj.cti2.TLSPolicy;
public class PolicyProbe {
    public static void main(String[] args)throws Exception {
        final int[] warnings={0};Logger.getLogger(TLSPolicy.class.getName()).addHandler(new Handler(){public void publish(LogRecord r){if(r.getLevel()==Level.WARNING)warnings[0]++;}public void flush(){}public void close(){}});
        System.setProperty(TLSPolicy.LEGACY_TRUST,args[0]);if(!args[1].equals("absent"))System.setProperty(TLSPolicy.INSECURE,args[1]);
        boolean expected=Boolean.parseBoolean(args[1].equals("absent")?args[0]:args[1]);
        for(int i=0;i<3;i++)if(TLSPolicy.isInsecure()!=expected)throw new AssertionError("precedence");
        if(warnings[0]!=1)throw new AssertionError("warnings="+warnings[0]);
        System.setProperty(TLSPolicy.INSECURE,"true");java.security.Security.setProperty("ssl.TrustManagerFactory.algorithm","stage1-nonexistent");
        try {new jp.cssj.driver.rest.RestSession(java.net.URI.create("https://127.0.0.1:1/"),null,null);throw new AssertionError("initialization failure swallowed");}
        catch(java.io.IOException e){if(!(e.getCause() instanceof java.security.NoSuchAlgorithmException))throw e;}
        if(warnings[0]!=1)throw new AssertionError("REST repeated warning");
        System.out.println("POLICY_OK warnings=1 initialization-cause-preserved");
    }
}
