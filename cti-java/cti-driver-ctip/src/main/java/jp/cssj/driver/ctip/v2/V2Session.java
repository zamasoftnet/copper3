package jp.cssj.driver.ctip.v2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.TranscoderException;
import jp.cssj.cti2.helpers.AbstractCTISession;
import jp.cssj.cti2.message.MessageHandler;
import jp.cssj.cti2.progress.ProgressListener;
import jp.cssj.cti2.results.Results;
import jp.cssj.resolver.MetaSource;
import jp.cssj.resolver.Source;
import jp.cssj.resolver.SourceResolver;
import jp.cssj.resolver.helpers.MetaSourceImpl;
import jp.cssj.rsr.RandomBuilder;
import jp.cssj.rsr.Sequential;

/**
 * @author MIYABE Tatsuhiko
 * @version $Id: V2Session.java 1552 2018-04-26 01:43:24Z miyabe $
 */
public class V2Session extends AbstractCTISession implements CTISession {
	public static final int BUFFER_SIZE = 8192;

	private final byte[] writeBuff = new byte[BUFFER_SIZE];

	private final byte[] readBuff = new byte[BUFFER_SIZE];

	protected final String encoding;

	protected final URI uri;

	protected final String user, password;

	protected volatile V2ContentProducer producer = null;

	protected volatile V2RequestConsumer request = null;

	protected Results results = null;

	protected SourceResolver resolver = null;

	protected MessageHandler messageHandler = null;

	protected ProgressListener progressListener = null;

	// 1=変換準備OK, 2=変換中, 3=クローズ
	protected volatile int state = 1;
	private final java.util.concurrent.locks.ReentrantLock responseLock = new java.util.concurrent.locks.ReentrantLock();

	protected RandomBuilder builder = null;
    private boolean serial;
    private volatile long streamGeneration;
    private volatile boolean sendingBody;
    private boolean failed;
    private boolean abortSent;
    private static final long RESET_TIMEOUT = 1000;

    private void disposeBuilder() {
        RandomBuilder current = this.builder;
        this.builder = null;
        this.serial = false;
        if (current != null) { current.dispose(); }
    }

	private void closeBuilder() throws IOException {
		RandomBuilder builder = this.builder;
		this.builder = null;
        this.serial = false;
		if (builder != null) {
			try { builder.finish(); } finally { builder.dispose(); }
		}
	}

	public V2Session(URI uri, String encoding, String user, String password) throws IOException {
		this.uri = uri;
		this.encoding = encoding;
		this.user = user == null ? "" : user;
		this.password = password == null ? "" : password;
	}

	protected void init() throws IOException {
        if (state == 3) { throw new java.nio.channels.ClosedChannelException(); }
        if (failed) { throw new IOException("Response failed; reset the session before reuse"); }
        if (sendingBody) { throw new IllegalStateException("A request body is still open"); }
		// 認証
		if (this.producer == null) {
			V2ContentProducer producer;
			if (this.uri.getScheme().equals("ctips")) {
				producer = new TLSV2ContentProducer(this.uri, this.encoding);
			} else {
				producer = new V2ContentProducer(this.uri, this.encoding);
			}
			V2RequestConsumer request = (V2RequestConsumer) producer.connect(this.user, this.password);
			request.setCTIPSession(this);
			this.producer = producer;
			this.request = request;
		}
	}

	public InputStream getServerInfo(URI uri) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.serverInfo(uri);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (;;) {
			this.producer.next();
			byte type = this.producer.getType();
			if (type == V2ServerPackets.EOF) {
				break;
			}
			if (type != V2ServerPackets.DATA) {
				throw new IOException("不正なパケットタイプです: " + type);
			}
			for (int len = this.producer.read(this.readBuff, 0, this.readBuff.length); len != -1; len = this.producer
					.read(this.readBuff, 0, this.readBuff.length)) {
				out.write(this.readBuff, 0, len);
			}
		}
		return new ByteArrayInputStream(out.toByteArray());
	}

	public void setResults(Results results) throws IOException {
		this.results = results;
	}

	public void setMessageHandler(MessageHandler eh) {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.messageHandler = eh;
	}

	public void setProgressListener(ProgressListener l) {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.progressListener = l;
	}

	public void property(String key, String value) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.property(key, value);
	}

	public OutputStream resource(MetaSource metaSource) throws IOException {
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.startResource(metaSource.getURI(), metaSource.getMimeType(), metaSource.getEncoding(),
				metaSource.getLength());
		this.sendingBody = true;
        return bodyStream(false);
	}

    public void resource(Source source) throws IOException {
        try (OutputStream out = resource(new MetaSourceImpl(source)); InputStream in = source.getInputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (int n; (n = in.read(buffer)) != -1;) { out.write(buffer, 0, n); }
        }
    }

	protected boolean buildNext() throws IOException, TranscoderException {
        responseLock.lock();
        try {
            if (this.state != 2) { return false; }
            this.producer.next();
            return processResponse();
        } catch (IOException | RuntimeException e) {
            if (!(e instanceof TranscoderException) || state == 2) { failResponse(e); }
            throw e;
        } finally { responseLock.unlock(); }
    }

    /** Upload polling never blocks on an incomplete frame or another response consumer. */
    boolean canPollResponse() {
        return state == 2 && !responseLock.isLocked();
    }

    boolean pollResponse() throws IOException {
        if (state != 2 || responseLock.isHeldByCurrentThread() || !responseLock.tryLock()) { return false; }
        try {
            if (state != 2 || !producer.pollNext()) { return false; }
            processResponse();
            return true;
        } catch (IOException | RuntimeException e) {
            if (!(e instanceof TranscoderException) || state == 2) { failResponse(e); }
            throw e;
        } finally { responseLock.unlock(); }
    }

    private boolean processResponse() throws IOException {
        if (state != 2) { return false; }
		// System.err.println("type="+Integer.toHexString(this.producer.getType()));

		switch (this.producer.getType()) {
		case V2ServerPackets.START_DATA: {
			this.closeBuilder();
			URI uri = this.producer.getURI();
			String mimeType = this.producer.getMimeType();
			String encoding = this.producer.getEncoding();
			long length = this.producer.getLength();
			MetaSource metaSource = new MetaSourceImpl(uri, mimeType, encoding, length);
			this.builder = this.results.nextBuilder(metaSource);
		}
			break;

		case V2ServerPackets.BLOCK_DATA: {
			assert this.builder != null;
			assert !serial;
			// 結果データ
			int blockId = this.producer.getBlockId();
			for (int len = this.producer.read(this.readBuff, 0, this.readBuff.length); len != -1; len = this.producer
					.read(this.readBuff, 0, this.readBuff.length)) {
				this.builder.write(blockId, this.readBuff, 0, len);
			}
		}
			break;

		case V2ServerPackets.ADD_BLOCK: {
			assert this.builder != null;
			assert !serial;
			this.builder.addBlock();
		}
			break;

		case V2ServerPackets.INSERT_BLOCK: {
			assert this.builder != null;
			assert !serial;
			int anchorId = this.producer.getAnchorId();
			this.builder.insertBlockBefore(anchorId);
		}
			break;

		case V2ServerPackets.CLOSE_BLOCK: {
			assert this.builder != null;
			assert !serial;
			int anchorId = this.producer.getAnchorId();
			this.builder.closeBlock(anchorId);
		}
			break;

		case V2ServerPackets.MESSAGE: {
			if (this.messageHandler != null) {
				short code = this.producer.getCode();
				String mes = this.producer.getMessage();
				String[] args = this.producer.getArgs();
				this.messageHandler.message(code, args, mes);
			}
		}
			break;

		case V2ServerPackets.MAIN_LENGTH: {
			if (this.progressListener != null) {
				long sourceLength = this.producer.getLength();
				this.progressListener.sourceLength(sourceLength);
			}
		}
			break;

		case V2ServerPackets.MAIN_READ: {
			if (this.progressListener != null) {
				long serverRead = this.producer.getLength();
				this.progressListener.progress(serverRead);
			}
		}
			break;

		case V2ServerPackets.DATA: {
			// 結果データ
			assert this.builder != null;
			if (this.builder instanceof Sequential) {
				Sequential builder = (Sequential) this.builder;
				for (int len = this.producer.read(this.readBuff, 0,
						this.readBuff.length); len != -1; len = this.producer.read(this.readBuff, 0,
								this.readBuff.length)) {
					builder.write(this.readBuff, 0, len);
				}
			} else {
				if (!serial) {
					this.builder.addBlock();
				}
				for (int len = this.producer.read(this.readBuff, 0,
						this.readBuff.length); len != -1; len = this.producer.read(this.readBuff, 0,
								this.readBuff.length)) {
					this.builder.write(0, this.readBuff, 0, len);
				}
			}
			if (!serial) {
				serial = true;
			}
		}
			break;

		case V2ServerPackets.RESOURCE_REQUEST: {
			// リソース要求。再入したメイン送信の入力配列を上書きしない。
            byte[] resourceBuffer = new byte[BUFFER_SIZE];
			URI uri = this.producer.getURI();
			if (this.resolver != null) {
				Source source;
				try {
					source = this.resolver.resolve(uri);
				} catch (IOException e) {
					this.request.missingResource(uri);
					source = null;
				}
				if (source != null) {
					try {
						if (source.exists()) {
							this.request.startResource(source.getURI(), source.getMimeType(), source.getEncoding(),
									source.getLength());
							try (InputStream in = source.getInputStream()) {
								try (OutputStream out = new V2RequestConsumerOutputStream(this.request)) {
									for (int len = in.read(resourceBuffer); len != -1; len = in.read(resourceBuffer)) {
										out.write(resourceBuffer, 0, len);
									}
								}
							}
						} else {
							this.request.missingResource(uri);
						}
					} finally {
						this.resolver.release(source);
					}
				}
			} else {
				this.request.missingResource(uri);
			}
		}
			break;

		case V2ServerPackets.EOF:
			this.closeBuilder();
			this.results.end();
		case V2ServerPackets.NEXT: {
			this.finishResponse();
		}
			return false;

		case V2ServerPackets.ABORT: {
			byte state;
			if (this.producer.getMode() == 0) {
				this.closeBuilder();
				this.results.end();
				state = TranscoderException.STATE_READABLE;
			} else {
                this.disposeBuilder();
				state = TranscoderException.STATE_BROKEN;
			}
			this.finishResponse();
			throw new TranscoderException(state, this.producer.getCode(), this.producer.getArgs(),
					this.producer.getMessage());
		}

		default:
			throw new IOException("不正なレスポンスです: " + Integer.toHexString(this.producer.getType()));
		}
		return true;
	}

	public OutputStream transcode(MetaSource metaSource) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		if (this.state >= 2) {
			throw new IllegalStateException("既に本体が変換されています。");
		}
		this.init();
		this.request.startMain(metaSource.getURI(), metaSource.getMimeType(), metaSource.getEncoding(),
				metaSource.getLength());
		this.beginResponse();
        this.sendingBody = true;
        return bodyStream(true);
    }

    /** A generation invalidates all previously returned streams, also on connection reuse. */
    private OutputStream bodyStream(final boolean main) {
        final long generation = streamGeneration;
        final V2RequestConsumer consumer = request;
        return new V2RequestConsumerOutputStream(consumer) {
            private boolean closed;
            private void checkValid() {
                if (generation != streamGeneration || closed || state == 3) {
                    throw new IllegalStateException("This request stream is no longer valid");
                }
            }
            @Override public void write(int b) throws IOException {
                write(new byte[] { (byte) b }, 0, 1);
            }
            @Override public void write(byte[] b) throws IOException { write(b, 0, b.length); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                checkValid();
                try { consumer.data(b, off, len); }
                catch (IOException | RuntimeException e) {
                    if (!(e instanceof TranscoderException) || state == 2) { failResponse(e); }
                    throw e;
                }
            }
            @Override public void close() throws IOException {
                if (closed || generation != streamGeneration || state == 3) { return; }
                closed = true;
                try {
                    consumer.eof();
                    sendingBody = false;
                    if (main) { V2Session.this.next(); }
                } catch (IOException | RuntimeException e) {
                    if (!(e instanceof TranscoderException) || state == 2) { failResponse(e); }
                    throw e;
                } finally { sendingBody = false; }
            }
        };
    }

	public void transcode(URI uri) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
        this.init();
		this.request.serverMain(uri);
		this.beginResponse();
		this.next();
	}

	public void transcode(Source source) throws IOException, TranscoderException {
		if (this.results == null) {
			throw new IllegalStateException("Resultsが設定されていません。");
		}
		this.init();
		try (OutputStream out = this.transcode(new MetaSourceImpl(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength()))) {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.writeBuff); len != -1; len = in.read(this.writeBuff)) {
					out.write(this.writeBuff, 0, len);
				}
			}
		}
	}

	protected void next() throws IOException {
		try {
			while (this.buildNext()) {
				// do nothing
			}
		} finally {
			this.finishResponse();
		}
	}

	public void setContinuous(boolean continuous) throws IOException {
		this.init();
		this.request.continuous(continuous);
	}

	public void join() throws IOException {
        this.init();
		this.request.join();
		this.beginResponse();
		this.next();
	}

	public void setSourceResolver(SourceResolver resolver) throws IOException {
		this.resolver = resolver;
		this.init();
		this.request.clientResource(resolver != null);
	}

	public void sendResource(Source source) throws IOException {
		this.init();
		try (OutputStream out = this.resource(new MetaSourceImpl(source.getURI(), source.getMimeType(), source.getEncoding(), source.getLength()))) {
			try (InputStream in = source.getInputStream()) {
				for (int len = in.read(this.writeBuff); len != -1; len = in.read(this.writeBuff)) {
					out.write(this.writeBuff, 0, len);
				}
			}
		}
	}

	public void abort(byte mode) throws IOException {
		this.request.abort((byte) (mode - 1));
	}

    private void failResponse(Exception failure) {
        responseLock.lock();
        try {
            if (failed || state == 3) { return; }
            failed = true;
            streamGeneration++;
            disposeBuilder();
            if (!abortSent && request != null) {
                abortSent = true;
                try { request.abortAfterFailure(); }
                catch (IOException | RuntimeException e) { if (e != failure) { failure.addSuppressed(e); } }
            }
            if (producer != null) {
                try { producer.close(); }
                catch (IOException e) { if (e != failure) { failure.addSuppressed(e); } }
            }
        } finally { responseLock.unlock(); }
    }

    public void reset() throws IOException {
        if (state == 3) { throw new java.nio.channels.ClosedChannelException(); }
        streamGeneration++;
        boolean reconnect = sendingBody || failed || (request != null && request.hasFailed());
        // Closing first wakes a reader that might own responseLock.
        if (reconnect && producer != null) {
            try { producer.close(); }
            catch (IOException e) {
                responseLock.lock();
                try { failed = true; disposeBuilder(); }
                finally { responseLock.unlock(); }
                throw e;
            }
        }
        responseLock.lock();
        try {
            disposeBuilder();
            if (!reconnect && state == 2 && producer != null) {
                try {
                    long deadline = jp.cssj.driver.ctip.common.ChannelIO.deadline(RESET_TIMEOUT);
                    while (true) {
                        producer.nextUntil(deadline);
                        byte type = producer.getType();
                        if (type == V2ServerPackets.EOF || type == V2ServerPackets.ABORT
                                || type == V2ServerPackets.NEXT) { break; }
                        if (type == V2ServerPackets.RESOURCE_REQUEST) {
                            request.missingResourceUntil(producer.getURI(), deadline);
                        }
                    }
                } catch (IOException e) { reconnect = true; }
            }
            if (reconnect) {
                if (producer != null) { producer.close(); }
                producer = null;
                request = null;
            } else if (request != null) { request.reset(); }
            resolver = null;
            results = null;
            sendingBody = false;
            failed = false;
            abortSent = false;
            finishResponse();
            if (reconnect) { init(); }
        } finally { responseLock.unlock(); }
    }

    private synchronized void finishResponse() {
        if (this.state != 3) { this.state = 1; }
    }

    private synchronized void beginResponse() throws IOException {
        if (this.state == 3) { throw new java.nio.channels.ClosedChannelException(); }
        this.abortSent = false;
        this.state = 2;
    }

    public void close() throws IOException {
        synchronized (this) {
            if (state == 3) { return; }
            state = 3;
            streamGeneration++;
        }
        try {
            if (producer != null) {
                try {
                    if (!sendingBody && !failed && request != null) { request.close(); }
                } finally { producer.close(); }
            }
        } finally {
            responseLock.lock();
            try { disposeBuilder(); } finally { responseLock.unlock(); }
        }
    }
}
