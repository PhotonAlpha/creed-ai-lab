package com.creed.report.mail;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Just enough SMTP for JavaMail to deliver to, counting TCP connections on the server side.
 *
 * <p>The counts here are the independent half of the proof: the client can only say how often it
 * called {@code connectTransport()}, the server sees the sockets. Each connection is logged with
 * the client's ephemeral port, so "one connection" vs "N connections" is visible in the log as one
 * port vs N distinct ports.
 *
 * <p>{@code dataDelay} holds every DATA reply open for a while; without it a local send finishes in
 * a millisecond and concurrent senders would rarely overlap, making "max concurrent" luck.
 */
@Slf4j
final class FakeSmtpServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final Duration dataDelay;
    private final AtomicInteger connectionSeq = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicInteger messages = new AtomicInteger();
    // Counted as each command is answered, not when the socket closes: the client's send() can
    // return before this server's handler thread has run its finally.
    private final Map<Integer, ConnectionStats> connections = new ConcurrentHashMap<>();

    /** What one TCP connection carried. */
    record ConnectionStats(int id, int clientPort, AtomicInteger messages, AtomicInteger noops) {
    }

    FakeSmtpServer(Duration dataDelay) throws IOException {
        this.dataDelay = dataDelay;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::acceptLoop, "smtp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("[server] fake SMTP listening on 127.0.0.1:{}", port());
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    int totalConnections() {
        return connectionSeq.get();
    }

    int maxConcurrentConnections() {
        return maxActive.get();
    }

    int totalMessages() {
        return messages.get();
    }

    /** Per connection, in accept order. */
    List<ConnectionStats> connections() {
        return connections.values().stream().sorted(Comparator.comparingInt(ConnectionStats::id)).toList();
    }

    void reset() {
        connectionSeq.set(0);
        maxActive.set(0);
        messages.set(0);
        connections.clear();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                int id = connectionSeq.incrementAndGet();
                Thread handler = new Thread(() -> handle(id, socket), "smtp-conn-" + id);
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException closed) {
                return;
            } catch (IOException ex) {
                log.warn("[server] accept failed", ex);
            }
        }
    }

    private void handle(int id, Socket socket) {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        log.info("[server] conn#{} ACCEPT  client port={} active={}", id, socket.getPort(), now);
        ConnectionStats stats = new ConnectionStats(id, socket.getPort(), new AtomicInteger(), new AtomicInteger());
        connections.put(id, stats);
        int delivered = 0;
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.US_ASCII)) {
            reply(out, "220 fake-smtp ready");
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
                switch (cmd) {
                    case "EHLO", "HELO" -> reply(out, "250 fake-smtp");
                    case "DATA" -> {
                        reply(out, "354 end with <CRLF>.<CRLF>");
                        while ((line = in.readLine()) != null && !line.equals(".")) {
                            // swallow the message body
                        }
                        Thread.sleep(dataDelay);
                        delivered++;
                        messages.incrementAndGet();
                        stats.messages().incrementAndGet();
                        log.info("[server] conn#{} MESSAGE #{} received  client port={}", id, delivered, socket.getPort());
                        reply(out, "250 queued as conn" + id + "-msg" + delivered);
                    }
                    case "QUIT" -> {
                        reply(out, "221 bye");
                        return;
                    }
                    // Transport.isConnected(): doSend's check before every message after the first
                    // (RSET instead with mail.smtp.userset=true).
                    case "NOOP", "RSET" -> {
                        stats.noops().incrementAndGet();
                        log.info("[server] conn#{} {} (connection check) client port={}", id, cmd, socket.getPort());
                        reply(out, "250 OK");
                    }
                    // MAIL FROM, RCPT TO
                    default -> reply(out, "250 OK");
                }
            }
        } catch (IOException ex) {
            log.warn("[server] conn#{} I/O error: {}", id, ex.toString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            log.info("[server] conn#{} CLOSE   client port={} messages={} active={}",
                    id, socket.getPort(), delivered, active.decrementAndGet());
        }
    }

    private static void reply(PrintWriter out, String text) {
        out.print(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
