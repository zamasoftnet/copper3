package jp.cssj.driver.ctip.tls;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class NativeServerTest {
    @TempDir static Path temp;static Path identity;
    @BeforeAll static void setup()throws Exception{identity=LocalTls.generateIdentity(temp);}
    @ParameterizedTest @ValueSource(strings={"plain","tls12","tls13","reset-resource","reset-unread","reset-unread-next","reset-after-next","reset-reconnect-old","reset-reuse-old"})
    void nativeServer(String scenario)throws Exception {
        Path log=temp.resolve(scenario+".log");Process child=new ProcessBuilder(LocalTls.javaTool("java"),"-Xmx256m","-cp",System.getProperty("tls.test.classpath"),NativeServerProbe.class.getName(),scenario,identity.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done;try{done=child.waitFor(20,TimeUnit.SECONDS);}finally{LocalTls.stop(child);}
        String output=new String(Files.readAllBytes(log),"UTF-8");System.out.println(scenario+" reaped="+!child.isAlive()+"\n"+output);
        assertTrue(done,output);assertEquals(0,child.exitValue(),output);assertTrue(output.contains("NATIVE_OK"),output);
    }
}
