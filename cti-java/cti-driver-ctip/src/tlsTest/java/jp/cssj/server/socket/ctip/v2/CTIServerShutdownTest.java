package jp.cssj.server.socket.ctip.v2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import jp.cssj.cti2.CTISession;
import jp.cssj.cti2.results.NopResults;
import jp.cssj.driver.ctip.CTIPDriver;
import jp.cssj.plugin.PluginLoader;
import jp.cssj.resolver.helpers.MetaSourceImpl;
import jp.cssj.server.acl.Acl;
import jp.cssj.server.socket.CTIServer;
import jp.cssj.server.socket.ProtocolHandler;

/**
 * 停止はワーカーを待つ間サーバーの錠を持たない(2026-09-28)。
 *
 * <p>
 * CTIServer.shutdown はサーバーの錠を持ったままワーカーの錠を取って join していた。ワーカーは自分の錠を持ったまま
 * 要求を処理し、終わると freeThread でサーバーの錠を取るので、要求を終えたばかりのワーカーと互いに相手を待って
 * 止まることがあった(copperd の停止と接続の終わりが重なると起きる。移植した中断の試験の後片付けで踏んだ)。
 * 重なるのは一瞬で外から確実には作れないので、原因のほう(待つ間に錠を持っていること)を見る。
 * 偽のエンジンの close(CLOSE を受けたワーカーが自分の錠を持ったまま呼ぶ)で 1 秒働かせ、その間に止める。
 * </p>
 */
@org.junit.jupiter.api.Timeout(60)
class CTIServerShutdownTest {
	@Test
	void shutdownReleasesTheServerLockWhileWaitingForABusyWorker() throws Exception {
		PluginLoader.getPluginLoader().add(Acl.class, new Acl() {
			public boolean match(final Object key) {
				return true;
			}

			public boolean checkAccess(final InetAddress address) {
				return address.isLoopbackAddress();
			}
		});
		final MidBodyAbortRoundTripTest.Engine engine = new MidBodyAbortRoundTripTest.Engine();
		engine.closeDelayMillis = 1000;
		final int port = freePort();
		final CTIServer server = new CTIServer();
		final Properties props = new Properties();
		props.setProperty("jp.cssj.cssjd.port", String.valueOf(port));
		props.setProperty("jp.cssj.cssjd.maxThreads", "2");
		props.setProperty("jp.cssj.cssjd.minThreads", "2");
		props.setProperty("jp.cssj.cssjd.timeout", "5");
		server.setConfigFile(new File("."), props);
		server.setProtocolHandlers(new ProtocolHandler[] { new V2ProtocolHandler(URI.create("ctip://fake/"), engine) });
		server.startup();
		try (CTISession session = new CTIPDriver().getSession(URI.create("ctip://127.0.0.1:" + port + "/"),
				new HashMap<String, String>())) {
			session.setResults(NopResults.SHARED_INSTANCE);
			try (OutputStream out = session.transcode(new MetaSourceImpl(URI.create("."), "text/plain", "UTF-8", -1L))) {
				out.write("x".getBytes("UTF-8"));
			}
		}
		// ワーカーは CLOSE を受けてエンジンの close で 1 秒働いている。その間に止める
		final Thread stopper = new Thread(server::shutdown, "shutdown");
		stopper.setDaemon(true);
		stopper.start();
		Thread.sleep(200);

		final CountDownLatch locked = new CountDownLatch(1);
		final Thread probe = new Thread(() -> {
			synchronized (server) {
				locked.countDown();
			}
		}, "lock probe");
		probe.setDaemon(true);
		probe.start();
		assertTrue(locked.await(300, TimeUnit.MILLISECONDS),
				"停止がワーカーを待つ間サーバーの錠を持っている(要求を終えたワーカーが freeThread で止まる)");

		stopper.join(5000);
		assertFalse(stopper.isAlive(), "停止が終わらない");
	}

	private static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			socket.setReuseAddress(true);
			return socket.getLocalPort();
		}
	}
}
