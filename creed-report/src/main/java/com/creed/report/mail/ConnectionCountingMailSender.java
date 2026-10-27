package com.creed.report.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link JavaMailSenderImpl} that logs and counts every SMTP connection it opens.
 *
 * <p>Hooks the two places {@code JavaMailSenderImpl} decides the connection's lifetime: every
 * {@code send(...)} call ends up in one {@code doSend}, which calls {@code connectTransport()} once
 * (again only if the server dropped it mid-batch) and closes that transport in its {@code finally}.
 * So a connection lives exactly as long as one {@code doSend} — the sender holds no pool and shares
 * nothing between calls or threads.
 *
 * <p>Counters are per instance; use a fresh one per measurement.
 */
@Slf4j
public class ConnectionCountingMailSender extends JavaMailSenderImpl {

    public enum EventType { OPEN, CLOSE }

    public record ConnectionEvent(int transport, EventType type, String thread, int active, Instant at) {
    }

    private final AtomicInteger transportSeq = new AtomicInteger();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private final List<ConnectionEvent> events = new CopyOnWriteArrayList<>();
    // connectTransport and doSend run on the caller's thread, so this pairs each open with its close.
    private final ThreadLocal<Deque<Integer>> openedInThisSend = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    protected Transport connectTransport() throws MessagingException {
        Transport transport = super.connectTransport();
        int id = transportSeq.incrementAndGet();
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        openedInThisSend.get().push(id);
        events.add(new ConnectionEvent(id, EventType.OPEN, Thread.currentThread().getName(), now, Instant.now()));
        log.info("[client] transport#{} OPEN  thread={} transport@{} active={}",
                id, Thread.currentThread().getName(), Integer.toHexString(System.identityHashCode(transport)), now);
        return transport;
    }

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object @Nullable [] originalMessages) throws MailException {
        log.info("[client] send({} message(s)) thread={}", mimeMessages.length, Thread.currentThread().getName());
        try {
            super.doSend(mimeMessages, originalMessages);
        } finally {
            Deque<Integer> opened = openedInThisSend.get();
            openedInThisSend.remove();
            while (!opened.isEmpty()) {
                int id = opened.pop();
                int now = active.decrementAndGet();
                events.add(new ConnectionEvent(id, EventType.CLOSE, Thread.currentThread().getName(), now, Instant.now()));
                log.info("[client] transport#{} CLOSE thread={} active={}", id, Thread.currentThread().getName(), now);
            }
        }
    }

    public int totalConnections() {
        return transportSeq.get();
    }

    public int maxConcurrentConnections() {
        return maxActive.get();
    }

    public List<ConnectionEvent> events() {
        return List.copyOf(events);
    }
}
