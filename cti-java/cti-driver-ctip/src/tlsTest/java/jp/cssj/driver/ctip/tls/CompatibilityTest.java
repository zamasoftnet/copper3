package jp.cssj.driver.ctip.tls;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class CompatibilityTest {
    // Windows では子 JVM を強制終了した直後にログファイルの削除が一瞬失敗し、JUnit の自動削除が
    // executionError になる(2026-09-11 に Claude の再実行で実測)。自動削除をやめ、終了時に再試行して消す。
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) static Path temp;static Path identity;

    @AfterAll static void deleteTemp() throws Exception{
        if(temp==null)return;
        for(int attempt=0;attempt<10;++attempt){
            try(Stream<Path> paths=Files.walk(temp)){
                for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)){Files.deleteIfExists(path);}
                return;
            }catch(IOException e){Thread.sleep(300);}
        }
    }
    @BeforeAll static void setup()throws Exception{identity=LocalTls.generateIdentity(temp);}
    static Stream<Arguments> cases(){List<Arguments> cases=new ArrayList<Arguments>();for(String side:new String[]{"old-client","old-server"})for(String protocol:new String[]{"plain","TLSv1.2","TLSv1.3"})for(String scenario:new String[]{"roundtrip","default","long","long-response","abort"})cases.add(Arguments.of(side,protocol,scenario));return cases.stream();}
    @ParameterizedTest @MethodSource("cases") void matrix(String side,String protocol,String scenario)throws Exception {
        Path old=Paths.get(System.getProperty("stage1.root"),"build/stage1-old-jars");
        assertTrue(Files.isRegularFile(old.resolve("cti-driver-ctip-2.2.3.jar")),"Extract stage 0 baseline JARs first");
        String cp=System.getProperty("tls.test.classpath");if(side.equals("old-server"))cp=old.resolve("cti-server-ctip-2.2.3.jar")+File.pathSeparator+old.resolve("cti-driver-ctip-2.2.3.jar")+File.pathSeparator+cp;
        Path client=side.equals("old-client")?old.resolve("cti-driver-ctip-2.2.3.jar"):Paths.get(System.getProperty("stage1.root"),"cti-java/cti-driver-ctip/build/libs/cti-driver-ctip-2.2.4.jar");
        Path log=temp.resolve(side+"-"+protocol+"-"+scenario+".log");
        Process child=new ProcessBuilder(LocalTls.javaTool("java"),"-cp",cp,CompatibilityProbe.class.getName(),side,protocol,scenario,identity.toString(),client.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done;try{done=child.waitFor(18,TimeUnit.SECONDS);}finally{LocalTls.stop(child);}
        String output=new String(Files.readAllBytes(log),"UTF-8");System.out.println(side+" "+protocol+" "+scenario+" done="+done+" exit="+child.exitValue()+" reaped="+!child.isAlive()+"\n"+output);
        assertTrue(done,output);
        assertFalse(output.contains("Self-suppression not permitted"),output);
        assertFalse(output.contains("NoSuchMethodError")||output.contains("NoClassDefFoundError"),output);
        boolean rejection=(side.equals("old-client")&&scenario.equals("long-response"))||(side.equals("old-server")&&((scenario.equals("default")&&!protocol.equals("plain"))||scenario.equals("long")||scenario.equals("abort")));
        if(rejection){
            assertNotEquals(0,child.exitValue(),"Expected documented baseline incompatibility: "+output);
            if(side.equals("old-client"))assertTrue(output.contains("NegativeArraySizeException"),output);
            else if(scenario.equals("default"))assertTrue(output.contains("SSLHandshakeException"),output);
            else if(scenario.equals("abort"))assertTrue(output.contains("ABORT_NOT_OBSERVED")&&output.contains("TranscoderException"),output);
            else assertTrue(output.contains("Bad request")&&output.contains("IOException"),output);
        }
        else {assertEquals(0,child.exitValue(),output);assertTrue(output.contains("COMPAT_OK"),output);}
    }
    @Test void baselineTls13CoalescedRecordsStopWithinWatchdog()throws Exception {
        Path old=Paths.get(System.getProperty("stage1.root"),"build/stage1-old-jars/cti-driver-ctip-2.2.3.jar");
        Path log=temp.resolve("baseline-tls13-coalesced.log");
        Process child=new ProcessBuilder(LocalTls.javaTool("java"),"-Xmx96m","-cp",old+File.pathSeparator+System.getProperty("tls.test.classpath"),TlsProbe.class.getName(),"tls13",identity.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done;try{done=child.waitFor(12,TimeUnit.SECONDS);}finally{LocalTls.stop(child);}
        String output=new String(Files.readAllBytes(log),"UTF-8");System.out.println("BASELINE_TLS13 done="+done+" reaped="+!child.isAlive()+"\n"+output);
        assertFalse(done,output);assertFalse(child.isAlive());
        assertTrue(output.contains("NO_PROGRESS_10000 client.unwrap OK/NEED_WRAP/0/0"),"Must prove the real TLS 1.3 spin rather than an unrelated timeout: "+output);
    }
}
