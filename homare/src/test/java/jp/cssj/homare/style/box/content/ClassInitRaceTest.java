package jp.cssj.homare.style.box.content;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * サーバーの起動直後の変換は同時に始まるので、最初に出会うクラスの初期化も同時に走ります。
 * 親の初期化子が自分のサブクラスを生成していると、サブクラスから初期化を始めたスレッドと親を初期化中のスレッドが
 * 互いの初期化ロックを待ってデッドロックし、割り込みも時間切れも効きません(2026-11-01)。
 *
 * <p>
 * クラス名を文字列で持ち、巡ごとに別のクラスローダーで定義し直すことで、毎回を最初の初期化にします。
 * </p>
 */
public class ClassInitRaceTest {
	private static final String BREAK_MODE = "jp.cssj.homare.style.box.content.BreakMode";

	private static final int ROUNDS = 1000;

	@Test
	public void breakModeFromItsSubclasses() throws Exception {
		assertNoDeadlock(BREAK_MODE, BREAK_MODE + "$AutoBreakMode", BREAK_MODE + "$ForceBreakMode");
	}

	private static void assertNoDeadlock(final String prefix, final String... entries) throws Exception {
		for (int round = 0; round < ROUNDS; ++round) {
			final ClassLoader loader = new FreshLoader(prefix);
			final List<Class<?>> classes = new ArrayList<Class<?>>();
			for (final String entry : entries) {
				final Class<?> c = Class.forName(entry, false, loader);
				// 初期化せずにリンクだけ済ませ、下のスレッドが初期化そのものの直前で揃うようにする
				c.getDeclaredMethods();
				classes.add(c);
			}
			final CyclicBarrier start = new CyclicBarrier(entries.length);
			final CountDownLatch done = new CountDownLatch(entries.length);
			final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
			for (final Class<?> c : classes) {
				final Thread thread = new Thread(() -> {
					try {
						start.await();
						Class.forName(c.getName(), true, loader);
					} catch (final Throwable e) {
						failure.compareAndSet(null, e);
					} finally {
						done.countDown();
					}
				}, "class-init-race");
				// デッドロックしたスレッドは止められないので、試験の JVM の終了を妨げないようにする
				thread.setDaemon(true);
				thread.start();
			}
			assertTrue("class initialization deadlocked in round " + round + ": " + classes,
					done.await(10, TimeUnit.SECONDS));
			assertNull("class initialization failed in round " + round + ": " + failure.get(), failure.get());
		}
	}

	/** prefix で始まるクラスを自分で定義し、巡ごとに未初期化の状態から始めます。 */
	private static final class FreshLoader extends ClassLoader {
		private static final Map<String, byte[]> BYTES = new ConcurrentHashMap<String, byte[]>();

		private final String prefix;

		FreshLoader(final String prefix) {
			super(ClassInitRaceTest.class.getClassLoader());
			this.prefix = prefix;
		}

		@Override
		protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
			if (!name.startsWith(this.prefix)) {
				return super.loadClass(name, resolve);
			}
			synchronized (this.getClassLoadingLock(name)) {
				Class<?> c = this.findLoadedClass(name);
				if (c == null) {
					final byte[] bytes = BYTES.computeIfAbsent(name, FreshLoader::read);
					c = this.defineClass(name, bytes, 0, bytes.length);
				}
				return c;
			}
		}

		private static byte[] read(final String name) {
			try (InputStream in = ClassInitRaceTest.class.getClassLoader()
					.getResourceAsStream(name.replace('.', '/') + ".class")) {
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				final byte[] buffer = new byte[8192];
				for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
					out.write(buffer, 0, n);
				}
				return out.toByteArray();
			} catch (final IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
