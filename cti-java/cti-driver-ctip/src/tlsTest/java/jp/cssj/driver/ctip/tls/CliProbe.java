package jp.cssj.driver.ctip.tls;
import java.net.URI;
import java.util.*;
import java.lang.reflect.*;
import jp.cssj.cti2.*;
import jp.cssj.plugin.PluginLoader;

public class CliProbe {
    public static void main(String[] args)throws Exception {
        try(java.nio.channels.Selector warmup=java.nio.channels.Selector.open()) { }
        final TreeMap<String,String> props=new TreeMap<String,String>();
        if(args[0].equals("properties"))PluginLoader.getPluginLoader().add(CTIDriver.class,new jp.cssj.driver.ctip.CTIPDriver(){
            public boolean match(URI uri){return uri.getScheme().equals("probe");}
            public CTISession getSession(URI uri,Map<String,String> options){return (CTISession)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CTISession.class},(p,m,a)->{if(m.getName().equals("property"))props.put((String)a[0],(String)a[1]);return null;});}
        });
        try {Class.forName("jp.cssj.driver.cli.Main").getMethod("main",String[].class).invoke(null,(Object)Arrays.copyOfRange(args,1,args.length));}
        catch(InvocationTargetException e){System.out.println("CLI_ERROR="+e.getCause().getClass().getName());throw e;}
        for(Map.Entry<String,String> e:props.entrySet())System.out.println("PROP="+e.getKey()+":"+Base64.getEncoder().encodeToString(e.getValue().getBytes("UTF-8")));
        System.out.println("CLI_OK");
    }
}
