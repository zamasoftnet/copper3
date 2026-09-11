package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jp.cssj.driver.ctip.common.ChannelIO;
import jp.cssj.driver.ctip.v2.*;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.resolver.SourceResolver;

@org.junit.jupiter.api.Timeout(15)
class ProtocolBoundaryTest {
    static String text(int size) { char[] c=new char[size]; Arrays.fill(c,'x'); return new String(c); }
    static byte[] property(String value) throws Exception {
        byte[] b=ChannelIO.toBytes(value,"UTF-8");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(5+b.length); out.writeByte(V2ClientPackets.PROPERTY); out.writeShort(0); out.writeShort(b.length); out.write(b);
        out.writeInt(1); out.writeByte(V2ClientPackets.EOF); return bytes.toByteArray();
    }
    @ParameterizedTest @ValueSource(ints={0,1,32767,32768,65535,65536})
    void clientToServer(int length) throws Exception {
        String value=text(length);
        if(length==65536) { assertThrows(IOException.class,()->property(value)); return; }
        V2RequestProducer p=new V2RequestProducer("UTF-8",new ByteArrayInputStream(property(value)));
        p.next(); assertEquals(length==0?null:value,p.getValue()); p.next(); assertEquals(V2ClientPackets.EOF,p.getType());
    }
    @ParameterizedTest @ValueSource(ints={0,1,32767,32768,65535,65536})
    void serverToClientChannelString(int length) throws Exception {
        String value=text(length);
        if(length==65536) { assertThrows(IOException.class,()->ChannelIO.toBytes(value,"UTF-8")); return; }
        try(ServerSocketChannel listener=ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1",0));
            try(SocketChannel client=SocketChannel.open(listener.getLocalAddress());SocketChannel server=listener.accept()) {
                ByteBuffer bytes=ByteBuffer.allocate(length+2); bytes.putShort((short)length).put(ChannelIO.toBytes(value,"UTF-8")).flip();
                java.util.concurrent.FutureTask<Void> write = new java.util.concurrent.FutureTask<Void>(() -> {
                    while(bytes.hasRemaining()) server.write(bytes);
                    return null;
                });
                Thread writer = new Thread(write, "boundary-writer");writer.setDaemon(true);writer.start();
                ChannelIO io=new ChannelIO(client,2000);
                try {
                    assertEquals(value,io.readString(ByteBuffer.allocate(2),"UTF-8"));
                    write.get(2,java.util.concurrent.TimeUnit.SECONDS);
                } finally {
                    io.close();server.close();writer.join(2000);assertFalse(writer.isAlive());
                }
            }
        }
    }
    static V2ProtocolProcessor processor(ByteArrayOutputStream bytes) throws Exception {
        V2ProtocolProcessor p=new V2ProtocolProcessor(URI.create("ctip://localhost/"),null);
        set(p,"out",new DataOutputStream(bytes)); set(p,"charset","UTF-8"); return p;
    }
    static void set(Object p,String name,Object value) throws Exception {Field f=p.getClass().getDeclaredField(name);f.setAccessible(true);f.set(p,value);}
    static void abort(V2ProtocolProcessor p) throws Exception {
        Method m=V2ProtocolProcessor.class.getDeclaredMethod("abort",TranscoderException.class); m.setAccessible(true);
        m.invoke(p,new TranscoderException(TranscoderException.STATE_READABLE,(short)1,new String[0],"interrupted"));
    }
    @Test void readableAbortFlushesOnlyAbortTerminal() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); V2ProtocolProcessor p=processor(bytes);
        p.write(new byte[]{1,2,3},0,3); abort(p);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(4,in.readInt());assertEquals(V2ServerPackets.DATA,in.readByte());
        byte[] data=new byte[3];in.readFully(data);assertArrayEquals(new byte[]{1,2,3},data);
        int n=in.readInt();assertEquals(V2ServerPackets.ABORT,in.readByte());assertEquals(0,in.readByte());in.skipBytes(n-2);assertEquals(-1,in.read());
    }
    @Test void eofDoesNotAppendAbort() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();V2ProtocolProcessor p=processor(bytes);p.end();abort(p);
        assertArrayEquals(new byte[]{0,0,0,1,V2ServerPackets.EOF},bytes.toByteArray());
    }
    @ParameterizedTest @ValueSource(ints={32768,65535,65536})
    void resourceRequestUriIsNeverTruncated(int length) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();V2ProtocolProcessor p=processor(bytes);
        ByteArrayOutputStream reply=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(reply);
        out.writeInt(3);out.writeByte(V2ClientPackets.MISSING_RESOURCE);out.writeShort(0);
        set(p,"request",new V2RequestProducer("UTF-8",new ByteArrayInputStream(reply.toByteArray())));
        Class<?> c=Class.forName(V2ProtocolProcessor.class.getName()+"$ClientSourceResolver");
        Constructor<?> ctor=c.getDeclaredConstructor(V2ProtocolProcessor.class);ctor.setAccessible(true);
        SourceResolver resolver=(SourceResolver)ctor.newInstance(p);
        URI uri=URI.create("test:/"+text(length-6));
        if(length==65536) {assertThrows(IOException.class,()->resolver.resolve(uri));assertEquals(0,bytes.size());return;}
        assertThrows(FileNotFoundException.class,()->resolver.resolve(uri));
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(length+3,in.readInt());assertEquals(V2ServerPackets.RESOURCE_REQUEST,in.readByte());assertEquals(length,in.readUnsignedShort());
        byte[] actual=new byte[length];in.readFully(actual);assertEquals(uri.toString(),new String(actual,"UTF-8"));assertEquals(-1,in.read());
    }
    @Test void messageDisplayStillLimitedTo3000Characters() throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();V2ProtocolProcessor p=processor(bytes);
        p.message((short)1,new String[]{text(4000)},text(4000));
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        in.readInt();assertEquals(V2ServerPackets.MESSAGE,in.readByte());in.readShort();
        assertEquals(3000,in.readUnsignedShort());in.skipBytes(3000);assertEquals(3000,in.readUnsignedShort());
    }
}
