package jp.cssj.driver.ctip.tls;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
class PolicyTest {
    @TempDir Path temp;
    @ParameterizedTest @CsvSource({"true,absent","false,absent","true,false","false,true"})
    void warningAndInitialization(String legacy,String modern)throws Exception {
        Path log=temp.resolve("policy.log");Process child=new ProcessBuilder(LocalTls.javaTool("java"),"-cp",System.getProperty("tls.test.classpath"),PolicyProbe.class.getName(),legacy,modern).redirectErrorStream(true).redirectOutput(log.toFile()).start();boolean done;
        try{done=child.waitFor(10,TimeUnit.SECONDS);}finally{LocalTls.stop(child);}
        String output=new String(Files.readAllBytes(log),"UTF-8");System.out.println(output);assertTrue(done,output);assertEquals(0,child.exitValue(),output);assertTrue(output.contains("POLICY_OK"),output);
    }
}
