package jp.cssj.driver.ctip.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.results.Results;
import jp.cssj.resolver.MetaSource;
import jp.cssj.rsr.RandomBuilder;
import jp.cssj.rsr.impl.FileRandomBuilder;

class V2SessionContractTest {
    @TempDir Path temp;
    static final class Builder implements RandomBuilder {
        int finishes, disposes, blocks;
        boolean fail;
        public void finish() throws IOException { finishes++; if (fail) throw new IOException("finish sentinel"); }
        public void dispose() { disposes++; }
        public void addBlock() { blocks++; }
        public void insertBlockBefore(int id) { }
        public void write(int id, byte[] b, int off, int len) { }
        public void closeBlock(int id) { }
        public boolean supportsPositionInfo() { return false; }
        public PositionInfo getPositionInfo() { throw new UnsupportedOperationException(); }
    }
    static final class Output implements Results {
        final List<Builder> builders = new ArrayList<Builder>(); int ends; boolean fail;
        public boolean hasNext() { return true; }
        public RandomBuilder nextBuilder(MetaSource meta) {
            Builder b = new Builder(); b.fail = fail; builders.add(b); return b;
        }
        public void end() { ends++; }
    }
    static final class Producer extends V2ContentProducer {
        final Queue<Byte> packets = new ArrayDeque<Byte>(); byte type, mode; int closes; boolean failClose; IOException readFailure;
        Producer() throws IOException { super(URI.create("ctip://localhost/"), "UTF-8"); }
        public void next() throws IOException { if(readFailure!=null)throw readFailure; if (packets.isEmpty()) throw new EOFException("wire sentinel"); type=packets.remove(); }
        boolean pollNext() throws IOException { if(packets.isEmpty()) return false; next(); return true; }
        void nextUntil(long deadline) throws IOException { next(); }
        public byte getType() { return type; }
        public byte getMode() { return mode; }
        public URI getURI() { return URI.create("test:/result"); }
        public String getMimeType() { return "application/pdf"; }
        public String getEncoding() { return null; }
        public long getLength() { return -1; }
        public short getCode() { return 1; }
        public String[] getArgs() { return new String[0]; }
        public String getMessage() { return "aborted"; }
        protected void close() throws IOException { closes++; if(failClose)throw new IOException("close sentinel"); }
    }
    static final class Request extends V2RequestConsumer {
        int aborts, resets; IOException abortFailure;
        Request() throws IOException { super(null,"UTF-8"); }
        void abortAfterFailure() throws IOException { aborts++; if(abortFailure!=null)throw abortFailure; }
        public void reset() { resets++; }
        public void close() { }
    }
    static final class Session extends V2Session {
        final Producer p = new Producer(); final Request r = new Request(); final Output o=new Output();
        Session() throws IOException { super(URI.create("ctip://localhost/"),"UTF-8","",""); producer=p;request=r;results=o;state=2; }
        void packet(byte type) throws IOException { p.packets.add(type); buildNext(); }
        void resume() { state=2; }
    }
    static void counts(Session s, int finish, int dispose, int ends) {
        assertEquals(ends,s.o.ends);
        for(Builder b:s.o.builders) { assertEquals(finish,b.finishes); assertEquals(dispose,b.disposes); }
    }
    @Test void normalEof() throws Exception {
        Session s=new Session(); s.packet(V2ServerPackets.START_DATA); s.packet(V2ServerPackets.EOF); s.close(); counts(s,1,1,1);
    }
    @Test void readableAbort() throws Exception {
        Session s=new Session(); s.packet(V2ServerPackets.START_DATA);
        assertThrows(TranscoderException.class,()->s.packet(V2ServerPackets.ABORT)); s.close(); counts(s,1,1,1);
    }
    @Test void readableAbortWithoutBuilder() throws Exception {
        Session s=new Session(); assertThrows(TranscoderException.class,()->s.packet(V2ServerPackets.ABORT)); s.close(); counts(s,0,0,1);
    }
    @Test void brokenAbort() throws Exception {
        Session s=new Session(); s.p.mode=1; s.packet(V2ServerPackets.START_DATA);
        assertThrows(TranscoderException.class,()->s.packet(V2ServerPackets.ABORT)); s.close(); counts(s,0,1,0);
    }
    @Test void ioFailureAbortsOnceAndDisposes() throws Exception {
        Session s=new Session(); s.packet(V2ServerPackets.START_DATA);
        assertThrows(EOFException.class,s::buildNext); assertThrows(IOException.class,s::buildNext);
        s.close(); counts(s,0,1,0); assertEquals(1,s.r.aborts);
    }
    @Test void finishFailurePreservesCauseAndPreventsReuse() throws Exception {
        Session s=new Session(); s.o.fail=true; s.packet(V2ServerPackets.START_DATA);
        IOException e=assertThrows(IOException.class,()->s.packet(V2ServerPackets.EOF));
        assertEquals("finish sentinel",e.getMessage()); assertThrows(IllegalStateException.class,()->s.property("x","y"));
        s.close(); counts(s,1,1,0);
    }
    @Test void repeatedTransportFailureDoesNotSuppressItself() throws Exception {
        Session s=new Session();s.packet(V2ServerPackets.START_DATA);
        IOException failure=new IOException("same transport failure");s.p.readFailure=failure;s.r.abortFailure=failure;
        assertSame(failure,assertThrows(IOException.class,s::buildNext));s.close();counts(s,0,1,0);assertEquals(1,s.r.aborts);
    }
    @Test void controlFailureThenAutomaticClosePreservesOriginalException() throws Exception {
        Session s=new Session();s.packet(V2ServerPackets.START_DATA);s.packet(V2ServerPackets.NEXT);
        java.nio.channels.SocketChannel channel=java.nio.channels.SocketChannel.open();
        jp.cssj.driver.ctip.common.ChannelIO io=new jp.cssj.driver.ctip.common.ChannelIO(channel,1000);
        s.request=new V2RequestConsumer(io,"UTF-8");io.close();
        final IOException[] original={null};
        IOException thrown=assertThrows(IOException.class,()->{
            try(V2Session ignored=s) {
                try{s.property("x","y");}catch(IOException e){original[0]=e;throw e;}
            }
        });
        assertSame(original[0],thrown);assertEquals(0,thrown.getSuppressed().length);assertFalse(channel.isOpen());counts(s,0,1,0);
    }
    @Test void multipleResultsEndOnce() throws Exception {
        Session s=new Session(); s.packet(V2ServerPackets.START_DATA); s.packet(V2ServerPackets.START_DATA);
        s.packet(V2ServerPackets.EOF); s.close(); assertEquals(2,s.o.builders.size()); counts(s,1,1,1);
    }
    @Test void nextKeepsBuilderUntilFinalEofAcrossTwoSeries() throws Exception {
        Session s=new Session();
        for(int i=0;i<2;i++) {
            s.resume(); s.packet(V2ServerPackets.START_DATA); s.packet(V2ServerPackets.NEXT);
            assertEquals(0,s.o.builders.get(i).disposes); assertEquals(i,s.o.ends);
            s.resume(); s.packet(V2ServerPackets.EOF);
        }
        s.close(); counts(s,1,1,2);
    }
    @Test void resetDiscardsUnreadResultAndNext() throws Exception {
        Session s=new Session(); s.packet(V2ServerPackets.START_DATA); s.p.packets.add(V2ServerPackets.NEXT);
        s.reset(); s.close(); counts(s,0,1,0); assertEquals(1,s.r.resets);
    }
    @Test void closeDuringResponseDisposesWithoutFinishing() throws Exception {
        Session s=new Session(); s.packet(V2ServerPackets.START_DATA); s.close(); s.close(); counts(s,0,1,0);
    }
    @Test void resetCloseFailureStillDisposes() throws Exception {
        Session s=new Session();s.packet(V2ServerPackets.START_DATA);
        Field sending=V2Session.class.getDeclaredField("sendingBody");sending.setAccessible(true);sending.setBoolean(s,true);
        s.p.failClose=true;IOException e=assertThrows(IOException.class,s::reset);assertEquals("close sentinel",e.getMessage());
        s.p.failClose=false;s.close();counts(s,0,1,0);
    }
    @Test void fileOutputAbortReleasesStreamAndTemporaryHandle() throws Exception {
        Session s=new Session(); File target=temp.resolve("result.pdf").toFile();
        FileRandomBuilder file=new FileRandomBuilder(target,1,1,1);
        file.write(new byte[]{1,2,3},0,3);
        Field stream=FileRandomBuilder.class.getDeclaredField("out"); stream.setAccessible(true);
        FileOutputStream open=(FileOutputStream)stream.get(file);
        file.addBlock(); file.write(0,new byte[10000],0,10000);
        Field raf=FileRandomBuilder.class.getSuperclass().getDeclaredField("raf"); raf.setAccessible(true);
        RandomAccessFile handle=(RandomAccessFile)raf.get(file); assertNotNull(handle);
        s.builder=file; s.p.mode=1; assertThrows(TranscoderException.class,()->s.packet(V2ServerPackets.ABORT));
        assertFalse(open.getFD().valid()); assertThrows(IOException.class,handle::getFilePointer);
        Path moved=temp.resolve("moved.pdf"); Files.move(target.toPath(),moved); Files.delete(moved); s.close();
    }
}
