//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package org.java_websocket.client;

import org.java_websocket.SocketChannelIOHelper;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocket.READYSTATE;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketFactory;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WrappedByteChannel;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_10;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.handshake.HandshakeImpl1Client;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshake;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;

public abstract class WebSocketClient extends WebSocketAdapter implements Runnable {
	protected URI uri;
	private WebSocketImpl conn;
	private SocketChannel channel;
	private ByteChannel wrappedchannel;
	private Thread writethread;
	private Thread readthread;
	private Draft draft;
	private Map<String, String> headers;
	private CountDownLatch connectLatch;
	private CountDownLatch closeLatch;
	private int timeout;
	private WebSocketClient.WebSocketClientFactory wsfactory;
	private InetSocketAddress proxyAddress;

	public WebSocketClient(URI serverURI) {
		this(serverURI, new Draft_10());
	}

	public WebSocketClient(URI serverUri, Draft draft) {
		this(serverUri, draft, (Map)null, 0);
	}

	public WebSocketClient(URI serverUri, Draft draft, Map<String, String> headers, int connecttimeout) {
		this.uri = null;
		this.conn = null;
		this.channel = null;
		this.wrappedchannel = null;
		this.connectLatch = new CountDownLatch(1);
		this.closeLatch = new CountDownLatch(1);
		this.timeout = 0;
		this.wsfactory = new DefaultWebSocketClientFactory(this);
		this.proxyAddress = null;
		if(serverUri == null) {
			throw new IllegalArgumentException();
		} else if(draft == null) {
			throw new IllegalArgumentException("null as draft is permitted for `WebSocketServer` only!");
		} else {
			this.uri = serverUri;
			this.draft = draft;
			this.headers = headers;
			this.timeout = connecttimeout;

			try {
				this.channel = SelectorProvider.provider().openSocketChannel();
				this.channel.configureBlocking(true);
			} catch (IOException var6) {
				this.channel = null;
				this.onWebsocketError((WebSocket)null, var6);
			}

			if(this.channel == null) {
				this.conn = (WebSocketImpl)this.wsfactory.createWebSocket(this, draft, (Socket)null);
				this.conn.close(-1, "Failed to create or configure SocketChannel.");
			} else {
				this.conn = (WebSocketImpl)this.wsfactory.createWebSocket(this, draft, this.channel.socket());
			}

		}
	}

	public URI getURI() {
		return this.uri;
	}

	public Draft getDraft() {
		return this.draft;
	}

	public void connect() {
		if(this.writethread != null) {
			throw new IllegalStateException("WebSocketClient objects are not reuseable");
		} else {
			this.writethread = new Thread(this);
			this.writethread.start();
		}
	}

	public boolean connectBlocking() throws InterruptedException {
		this.connect();
		this.connectLatch.await();
		return this.conn.isOpen();
	}

	public void close() {
		if(this.writethread != null) {
			this.conn.close(1000);
		}

	}

	public void closeBlocking() throws InterruptedException {
		this.close();
		this.closeLatch.await();
	}

	public void send(String text) throws NotYetConnectedException {
		this.conn.send(text);
	}

	public void send(byte[] data) throws NotYetConnectedException {
		this.conn.send(data);
	}

	public void run() {
		if(this.writethread == null) {
			this.writethread = Thread.currentThread();
		}

		this.interruptableRun();

		assert !this.channel.isOpen();

	}

	private final void interruptableRun() {
		if(this.channel != null) {
			try {
				String buff;
				int e;
				if(this.proxyAddress != null) {
					buff = this.proxyAddress.getHostName();
					e = this.proxyAddress.getPort();
				} else {
					buff = this.uri.getHost();
					e = this.getPort();
				}

				try {
					this.channel.connect(new InetSocketAddress(buff, e));
				} catch (AssertionError var4) {
					return;
				}

				this.conn.channel = this.wrappedchannel = this.createProxyChannel(this.wsfactory.wrapChannel(this.channel, (SelectionKey)null, buff, e));
				this.timeout = 0;
				this.sendHandshake();
				this.readthread = new Thread(new WebSocketClient.WebsocketWriteThread());
				this.readthread.start();
			} catch (ClosedByInterruptException var5) {
				this.onWebsocketError((WebSocket)null, var5);
				return;
			} catch (Exception var6) {
				this.onWebsocketError(this.conn, var6);
				this.conn.closeConnection(-1, var6.getMessage());
				return;
			}

			ByteBuffer buff1 = ByteBuffer.allocate(WebSocketImpl.RCVBUF);

			try {
				while(this.channel.isOpen()) {
					if(SocketChannelIOHelper.read(buff1, this.conn, this.wrappedchannel)) {
						this.conn.decode(buff1);
					} else {
						this.conn.eot();
					}

					if(this.wrappedchannel instanceof WrappedByteChannel) {
						WrappedByteChannel e1 = (WrappedByteChannel)this.wrappedchannel;
						if(e1.isNeedRead()) {
							while(SocketChannelIOHelper.readMore(buff1, this.conn, e1)) {
								this.conn.decode(buff1);
							}

							this.conn.decode(buff1);
						}
					}
				}
			} catch (CancelledKeyException var7) {
				this.conn.eot();
			} catch (IOException var8) {
				this.conn.eot();
			} catch (RuntimeException var9) {
				this.onError(var9);
				this.conn.closeConnection(1006, var9.getMessage());
			}

		}
	}

	private int getPort() {
		int port = this.uri.getPort();
		if(port == -1) {
			String scheme = this.uri.getScheme();
			if(scheme.equals("wss")) {
				return 443;
			} else if(scheme.equals("ws")) {
				return 80;
			} else {
				throw new RuntimeException("unkonow scheme" + scheme);
			}
		} else {
			return port;
		}
	}

	private void sendHandshake() throws InvalidHandshakeException {
		String part1 = this.uri.getPath();
		String part2 = this.uri.getQuery();
		String path;
		if(part1 != null && part1.length() != 0) {
			path = part1;
		} else {
			path = "/";
		}

		if(part2 != null) {
			path = path + "?" + part2;
		}

		int port = this.getPort();
		String host = this.uri.getHost() + (port != 80?":" + port:"");
		HandshakeImpl1Client handshake = new HandshakeImpl1Client();
		handshake.setResourceDescriptor(path);
		handshake.put("Host", host);
		if(this.headers != null) {
			Iterator i$ = this.headers.entrySet().iterator();

			while(i$.hasNext()) {
				Entry kv = (Entry)i$.next();
				handshake.put((String)kv.getKey(), (String)kv.getValue());
			}
		}

		this.conn.startHandshake(handshake);
	}

	public READYSTATE getReadyState() {
		return this.conn.getReadyState();
	}

	public final void onWebsocketMessage(WebSocket conn, String message) {
		this.onMessage(message);
	}

	public final void onWebsocketMessage(WebSocket conn, ByteBuffer blob) {
		this.onMessage(blob);
	}

	public final void onWebsocketOpen(WebSocket conn, Handshakedata handshake) {
		this.connectLatch.countDown();
		this.onOpen((ServerHandshake) handshake);
	}

	public final void onWebsocketClose(WebSocket conn, int code, String reason, boolean remote) {
		this.connectLatch.countDown();
		this.closeLatch.countDown();
		if(this.readthread != null) {
			this.readthread.interrupt();
		}

		this.onClose(code, reason, remote);
	}

	public final void onWebsocketError(WebSocket conn, Exception ex) {
		this.onError(ex);
	}

	public final void onWriteDemand(WebSocket conn) {
	}

	public void onWebsocketCloseInitiated(WebSocket conn, int code, String reason) {
		this.onCloseInitiated(code, reason);
	}

	public void onWebsocketClosing(WebSocket conn, int code, String reason, boolean remote) {
		this.onClosing(code, reason, remote);
	}

	public void onCloseInitiated(int code, String reason) {
	}

	public void onClosing(int code, String reason, boolean remote) {
	}

	public WebSocket getConnection() {
		return this.conn;
	}

	public final void setWebSocketFactory(WebSocketClient.WebSocketClientFactory wsf) {
		this.wsfactory = wsf;
	}

	public final WebSocketFactory getWebSocketFactory() {
		return this.wsfactory;
	}

	public InetSocketAddress getLocalSocketAddress(WebSocket conn) {
		return this.channel != null?(InetSocketAddress)this.channel.socket().getLocalSocketAddress():null;
	}

	public InetSocketAddress getRemoteSocketAddress(WebSocket conn) {
		return this.channel != null?(InetSocketAddress)this.channel.socket().getLocalSocketAddress():null;
	}

	public abstract void onOpen(ServerHandshake var1);

	public abstract void onMessage(String var1);

	public abstract void onClose(int var1, String var2, boolean var3);

	public abstract void onError(Exception var1);

	public void onMessage(ByteBuffer bytes) {
	}

	public ByteChannel createProxyChannel(ByteChannel towrap) {
		return (ByteChannel)(this.proxyAddress != null?new WebSocketClient.DefaultClientProxyChannel(towrap):towrap);
	}

	public void setProxy(InetSocketAddress proxyaddress) {
		this.proxyAddress = proxyaddress;
	}

	private class WebsocketWriteThread implements Runnable {
		private WebsocketWriteThread() {
		}

		public void run() {
			Thread.currentThread().setName("WebsocketWriteThread");

			try {
				while(!Thread.interrupted()) {
					SocketChannelIOHelper.writeBlocking(WebSocketClient.this.conn, WebSocketClient.this.wrappedchannel);
				}
			} catch (IOException var2) {
				WebSocketClient.this.conn.eot();
			} catch (InterruptedException var3) {
				;
			}

		}
	}

	public interface WebSocketClientFactory extends WebSocketFactory {
		ByteChannel wrapChannel(SocketChannel var1, SelectionKey var2, String var3, int var4) throws IOException;
	}

	public class DefaultClientProxyChannel extends AbstractClientProxyChannel {
		public DefaultClientProxyChannel(ByteChannel towrap) {
			super(towrap);
		}

		public String buildHandShake() {
			StringBuilder b = new StringBuilder();
			String host = WebSocketClient.this.uri.getHost();
			b.append("CONNECT ");
			b.append(host);
			b.append(":");
			b.append(WebSocketClient.this.getPort());
			b.append(" HTTP/1.1\n");
			b.append("Host: ");
			b.append(host);
			b.append("\n");
			return b.toString();
		}
	}
}
