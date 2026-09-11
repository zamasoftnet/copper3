package jp.cssj.driver.ctip.tls;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliRegressionTest {
    @TempDir static Path temp;static Path identity,root;
    @BeforeAll static void setup()throws Exception{root=Paths.get(System.getProperty("stage1.root"));identity=LocalTls.generateIdentity(temp);}
    String run(boolean old,String mode,List<String> arguments)throws Exception {
        String cp=root.resolve("cti-java/build/release/cti-driver-2.2.4.jar")+File.pathSeparator+root.resolve("cti-java/cti-driver-ctip/build/classes/java/tlsTest");
        if(old) {
            StringBuilder baseline=new StringBuilder();
            try(java.util.stream.Stream<Path> jars=Files.list(root.resolve("build/stage1-old-jars"))){for(Iterator<Path> it=jars.filter(p->p.toString().endsWith(".jar")&&!p.getFileName().toString().equals("cssj-copper.jar")).sorted().iterator();it.hasNext();)baseline.append(it.next()).append(File.pathSeparator);}
            cp=baseline+cp; // Baseline classes first; unchanged external dependencies from full JAR.
        }
        List<String> cmd=new ArrayList<String>(Arrays.asList(LocalTls.javaTool("java"),"-cp",cp,CliProbe.class.getName(),mode));cmd.addAll(arguments);
        Path log=temp.resolve(UUID.randomUUID()+".log");Process child=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();boolean done;
        try{done=child.waitFor(22,TimeUnit.SECONDS);}finally{LocalTls.stop(child);}
        String output=new String(Files.readAllBytes(log),"UTF-8");System.out.println("old="+old+" "+arguments+" exit="+child.exitValue()+"\n"+output);assertTrue(done,output);return output;
    }
    @ParameterizedTest @ValueSource(strings={"multiple","empty","equals","quotes"}) void propertiesMatchBaseline(String scenario)throws Exception {
        List<String> args=new ArrayList<String>(Arrays.asList("-s","probe:/","-uri","test:/input","-p"));
        if(scenario.equals("multiple"))args.addAll(Arrays.asList("first=one","second=two"));
        if(scenario.equals("empty"))args.add("empty=");
        if(scenario.equals("equals"))args.add("expression=a=b");
        if(scenario.equals("quotes"))args.add("quoted=\"hello world\"");
        String before=run(true,"properties",args),after=run(false,"properties",args);
        // The preserved 1.11.0/MAX_VALUE baseline rejects these inputs. Compare its
        // diagnostic explicitly; do not mistake parser System.exit(0) for success.
        List<String> a=new ArrayList<String>(),b=new ArrayList<String>();for(String line:before.split("\\R"))if(line.startsWith("PROP=")||line.startsWith("CLI_")||line.startsWith("Missing argument for option:"))a.add(line);for(String line:after.split("\\R"))if(line.startsWith("PROP=")||line.startsWith("CLI_")||line.startsWith("Missing argument for option:"))b.add(line);
        assertFalse(a.isEmpty(),before);assertEquals(a,b,after);
        assertEquals(Collections.singletonList("Missing argument for option: p"),a,"Update the documented baseline if its behavior changes");
    }
    @ParameterizedTest @ValueSource(strings={"-t","--trust","--insecure","both-t","both-trust"}) void trustOptions(String option)throws Exception {
        try(NativeServerProbe.Server server=new NativeServerProbe.Server(identity,"TLSv1.2")) {
            Path input=temp.resolve("input-"+option+".dat"),output=temp.resolve("output-"+option+".dat");Files.write(input,new byte[]{1,2,3});
            List<String> args=new ArrayList<String>(Arrays.asList("-s",server.uri.toString(),"-in",input.toString(),"-out",output.toString()));
            if(option.startsWith("both"))args.addAll(Arrays.asList("--insecure",option.equals("both-t")?"-t":"--trust"));else args.add(option);
            String result=run(false,"network",args);assertTrue(result.contains("CLI_OK"),result);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(output));
            assertEquals(option.startsWith("both")?1:0,result.split("takes precedence",-1).length-1,result);
            if(option.equals("-t")||option.equals("--trust")){result=run(true,"network",args);assertTrue(result.contains("CLI_OK"),result);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(output));}
        }
    }
    @Test void version()throws Exception{assertTrue(run(false,"network",Arrays.asList("-v")).contains("2.2.4"));}
}
