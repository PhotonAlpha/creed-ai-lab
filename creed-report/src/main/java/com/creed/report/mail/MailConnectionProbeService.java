package com.creed.report.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Sends real mail through the {@code spring.mail.*} server and reports how many SMTP connections
 * {@code JavaMailSenderImpl} opened for it, in one of three modes (see {@link Mode}).
 *
 * <p>Each probe gets its own {@link ConnectionCountingMailSender} built on <b>Boot's</b> session
 * ({@code setSession(bootSender.getSession())}): host, port, credentials and every
 * {@code spring.mail.ssl.*} / {@code spring.mail.properties.*} setting are the application's, not
 * a copy that could drift, and the application's own sender bean is left untouched. A fresh
 * instance per probe also keeps two concurrent probes from adding up each other's counts.
 */
@Slf4j
@Service
public class MailConnectionProbeService {

    public enum Mode {
        /** One thread, one {@code send(msg1..msgN)}: one connection carries every message. */
        BATCH,
        /** One thread, N calls of {@code send(msg)}: N connections, never two at once. */
        SEQUENTIAL,
        /** N threads, one {@code send(msg)} each, released together: N connections at once. */
        CONCURRENT;

        static Mode parse(String value) {
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new InvalidMailProbeException("mode must be batch, sequential or concurrent, got '" + value + "'");
            }
        }
    }

    public record MessageResult(int index, String thread, boolean sent, String error) {
    }

    public record ProbeResult(Mode mode, String server, int messages, int connections, int maxConcurrentConnections,
                              long elapsedMillis, List<MessageResult> results,
                              List<ConnectionCountingMailSender.ConnectionEvent> events) {
    }

    private final JavaMailSenderImpl bootSender;
    private final int maxMessages;

    public MailConnectionProbeService(JavaMailSenderImpl bootSender,
                                      @Value("${creed.report.mail-probe.max-messages:10}") int maxMessages) {
        this.bootSender = bootSender;
        this.maxMessages = maxMessages;
    }

    public ProbeResult probe(String modeValue, int count, String to) {
        Mode mode = Mode.parse(modeValue);
        if (count < 1 || count > maxMessages) {
            // Real mail to a real mailbox: a typo of 500 is 500 mails and a rate-limited account.
            throw new InvalidMailProbeException("count must be 1.." + maxMessages + ", got " + count);
        }
        if (to == null || !to.contains("@")) {
            throw new InvalidMailProbeException("to must be an e-mail address, got '" + to + "'");
        }

        ConnectionCountingMailSender sender = newProbeSender();
        String runId = DateTimeFormatter.ofPattern("HHmmss").withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        List<SimpleMailMessage> messages = IntStream.rangeClosed(1, count)
                .mapToObj(i -> message(to, mode, runId, i, count))
                .toList();

        log.info("===== mail probe {} x{} -> {}:{} =====", mode, count, sender.getHost(), sender.getPort());
        long start = System.nanoTime();
        List<MessageResult> results = switch (mode) {
            case BATCH -> sendBatch(sender, messages);
            case SEQUENTIAL -> sendSequential(sender, messages);
            case CONCURRENT -> sendConcurrent(sender, messages);
        };
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.info("===== mail probe {} done: {} connection(s), max {} concurrent, {} ms =====",
                mode, sender.totalConnections(), sender.maxConcurrentConnections(), elapsed);

        return new ProbeResult(mode, sender.getHost() + ":" + sender.getPort(), count,
                sender.totalConnections(), sender.maxConcurrentConnections(), elapsed, results, sender.events());
    }

    private ConnectionCountingMailSender newProbeSender() {
        ConnectionCountingMailSender sender = new ConnectionCountingMailSender();
        sender.setSession(bootSender.getSession());
        sender.setHost(bootSender.getHost());
        sender.setPort(bootSender.getPort());
        sender.setUsername(bootSender.getUsername());
        sender.setPassword(bootSender.getPassword());
        sender.setProtocol(bootSender.getProtocol());
        return sender;
    }

    private SimpleMailMessage message(String to, Mode mode, String runId, int index, int count) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(bootSender.getUsername());
        m.setTo(to);
        m.setSubject("[creed-report] mail probe " + mode + " " + runId + " #" + index + "/" + count);
        m.setText("JavaMailSender connection probe: mode=" + mode + ", run=" + runId + ", message " + index + " of " + count);
        return m;
    }

    private static List<MessageResult> sendBatch(ConnectionCountingMailSender sender, List<SimpleMailMessage> messages) {
        String thread = Thread.currentThread().getName();
        try {
            sender.send(messages.toArray(SimpleMailMessage[]::new));
            return IntStream.rangeClosed(1, messages.size()).mapToObj(i -> new MessageResult(i, thread, true, null)).toList();
        } catch (RuntimeException ex) {
            // MailSendException does say which messages failed, but one connection failure fails
            // them all and that is what the caller needs to see.
            return IntStream.rangeClosed(1, messages.size()).mapToObj(i -> new MessageResult(i, thread, false, ex.toString())).toList();
        }
    }

    private static List<MessageResult> sendSequential(ConnectionCountingMailSender sender, List<SimpleMailMessage> messages) {
        List<MessageResult> results = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            results.add(sendOne(sender, messages.get(i), i + 1));
        }
        return results;
    }

    private static List<MessageResult> sendConcurrent(ConnectionCountingMailSender sender, List<SimpleMailMessage> messages) {
        AtomicInteger threadSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(messages.size(),
                r -> new Thread(r, "mail-probe-" + threadSeq.incrementAndGet()));
        // Start gate: without it the first thread can finish before the last one is scheduled.
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MessageResult>> futures = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                SimpleMailMessage message = messages.get(i);
                int index = i + 1;
                futures.add(pool.submit(() -> {
                    go.await();
                    return sendOne(sender, message, index);
                }));
            }
            go.countDown();
            List<MessageResult> results = new ArrayList<>();
            for (Future<MessageResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the probe threads", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(ex.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static MessageResult sendOne(ConnectionCountingMailSender sender, SimpleMailMessage message, int index) {
        String thread = Thread.currentThread().getName();
        try {
            sender.send(message);
            return new MessageResult(index, thread, true, null);
        } catch (RuntimeException ex) {
            log.warn("[client] message #{} failed on {}: {}", index, thread, ex.toString());
            return new MessageResult(index, thread, false, ex.toString());
        }
    }
}
