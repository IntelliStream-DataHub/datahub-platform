// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A TCP relay that counts the bytes crossing it, so "bytes over the network" is measured rather
 * than estimated. Point the SDK at {@link #baseUrl()} and it reaches the real api through here.
 *
 * <p>Deliberately a byte relay and not an HTTP proxy: it counts what actually goes on the wire,
 * request line and headers included, and it cannot alter framing, compression or keep-alive the
 * way a parsing proxy would. The cost is that it says nothing about individual requests, which is
 * what the latency timings are for.
 */
final class ByteCountingProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private volatile boolean running = true;

    ByteCountingProxy(String targetBaseUrl) throws IOException {
        URI target = URI.create(targetBaseUrl);
        this.targetHost = target.getHost();
        this.targetPort = target.getPort() > 0 ? target.getPort()
                : "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
        if ("https".equalsIgnoreCase(target.getScheme())) {
            throw new IllegalArgumentException("the byte counter relays plaintext only; "
                    + "point it at an http:// api or drop the wire-byte measurement");
        }
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        pumps.submit(this::acceptLoop);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** Bytes the client wrote towards the api: request lines, headers and bodies. */
    long bytesSent() {
        return sent.get();
    }

    /** Bytes the api wrote back. */
    long bytesReceived() {
        return received.get();
    }

    void reset() {
        sent.set(0);
        received.set(0);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket downstream = server.accept();
                pumps.submit(() -> relay(downstream));
            } catch (IOException e) {
                if (running) {
                    System.err.println("byte-counting proxy accept failed: " + e);
                }
                return;
            }
        }
    }

    private void relay(Socket downstream) {
        try (downstream; Socket upstream = new Socket(targetHost, targetPort)) {
            downstream.setTcpNoDelay(true);
            upstream.setTcpNoDelay(true);
            // Each direction gets its own thread; the first to end closes both sides, which is
            // what lets a keep-alive connection drain before it disappears.
            var toUpstream = pumps.submit(() -> {
                try {
                    pump(downstream.getInputStream(), upstream.getOutputStream(), sent);
                } catch (IOException ignored) {
                    // the connection went away; counts so far stand
                }
            });
            pump(upstream.getInputStream(), downstream.getOutputStream(), received);
            toUpstream.cancel(true);
        } catch (IOException e) {
            // A client closing mid-request is normal at the end of a run.
        }
    }

    private void pump(InputStream in, OutputStream out, AtomicLong counter) {
        byte[] buffer = new byte[64 * 1024];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
                counter.addAndGet(read);
            }
        } catch (IOException e) {
            // Connection closed; the counts so far stand.
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        pumps.shutdownNow();
    }
}
