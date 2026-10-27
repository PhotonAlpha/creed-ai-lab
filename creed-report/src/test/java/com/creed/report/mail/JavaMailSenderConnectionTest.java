package com.creed.report.mail;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Proves how many SMTP connections {@code JavaMailSenderImpl} opens.
 *
 * <ul>
 *   <li><b>One thread, one {@code send(msg...)} call</b> — one connection carries every message.</li>
 *   <li><b>One thread, {@code send(msg1, msg2, msg3)}</b> — the <i>same</i> connection is reused: the
 *       server sees three messages and two NOOP liveness checks on one socket.</li>
 *   <li><b>One thread, a loop of {@code send(msg)}</b> — one connection per call, but strictly one
 *       after another: never more than one open at a time.</li>
 *   <li><b>N threads, {@code send(msg)} each</b> — N connections open at the same time. The sender
 *       is thread-safe precisely because it shares no connection, and it has no pool to cap them.</li>
 * </ul>
 *
 * <p>Runs against {@link FakeSmtpServer} on loopback, so it needs no network and sends no real
 * mail. Watch the log: {@code [client]} lines come from the sender, {@code [server]} lines from the
 * socket side, and the number of distinct {@code client port=} values is the number of TCP
 * connections.
 *
 * <p>{@link Live} repeats the multi-thread case against the {@code spring.mail.*} server in
 * application.yml, opt-in only:
 * <pre>mvn -pl creed-report test -Dtest='JavaMailSenderConnectionTest$Live' -Dmail.live.to=you@example.com</pre>
 */
@Slf4j
class JavaMailSenderConnectionTest {

    private static final int THREADS = 5;
    private static final int MESSAGES = 5;

    private static FakeSmtpServer server;
    private ConnectionCountingMailSender sender;

    @BeforeAll
    static void startServer() throws Exception {
        server = new FakeSmtpServer(Duration.ofMillis(300));
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.close();
    }

    @BeforeEach
    void newSender() {
        server.reset();
        sender = new ConnectionCountingMailSender();
        sender.setHost("127.0.0.1");
        sender.setPort(server.port());
        sender.getJavaMailProperties().putAll(timeouts());
    }

    @Test
    void singleThreadBatchSendReusesOneConnection() {
        log.info("===== single thread, send(msg x{}) in ONE call =====", MESSAGES);

        sender.send(messages(MESSAGES, "batch"));

        report();
        assertThat(server.totalMessages()).isEqualTo(MESSAGES);
        assertThat(sender.totalConnections()).isEqualTo(1);
        assertThat(server.totalConnections()).isEqualTo(1);
        assertThat(server.maxConcurrentConnections()).isEqualTo(1);
    }

    @Test
    void severalMessagesInOneSendReuseTheSameConnection() {
        log.info("===== single thread, send(msg1, msg2, msg3): is the connection reused? =====");
        SimpleMailMessage[] batch = messages(3, "reuse");
        SimpleMailMessage msg1 = batch[0];
        SimpleMailMessage msg2 = batch[1];
        SimpleMailMessage msg3 = batch[2];

        sender.send(msg1, msg2, msg3);

        report();
        server.connections().forEach(c -> log.info("===== conn#{} client port={} carried {} message(s), {} NOOP(s) =====",
                c.id(), c.clientPort(), c.messages().get(), c.noops().get()));

        // Server side: one socket carried all three messages...
        assertThat(server.connections()).singleElement().satisfies(c -> {
            assertThat(c.messages()).hasValue(3);
            // ...and doSend checked it was still alive before msg2 and before msg3 (L375,
            // Transport.isConnected() -> NOOP) instead of dialling again.
            assertThat(c.noops()).hasValue(2);
        });
        // Client side: one transport, opened once and closed once.
        assertThat(sender.events())
                .extracting(ConnectionCountingMailSender.ConnectionEvent::transport,
                        ConnectionCountingMailSender.ConnectionEvent::type)
                .containsExactly(
                        tuple(1, ConnectionCountingMailSender.EventType.OPEN),
                        tuple(1, ConnectionCountingMailSender.EventType.CLOSE));
    }

    @Test
    void singleThreadSequentialSendsNeverOverlap() {
        log.info("===== single thread, send(msg) called {} times =====", MESSAGES);

        for (SimpleMailMessage message : messages(MESSAGES, "loop")) {
            sender.send(message);
        }

        report();
        assertThat(server.totalMessages()).isEqualTo(MESSAGES);
        // Each call opens and closes its own connection...
        assertThat(server.totalConnections()).isEqualTo(MESSAGES);
        // ...but the previous one is always closed first.
        assertThat(sender.maxConcurrentConnections()).isEqualTo(1);
        assertThat(server.maxConcurrentConnections()).isEqualTo(1);
    }

    @Test
    void multipleThreadsOpenConcurrentConnections() throws Exception {
        log.info("===== {} threads, one send(msg) each, released together =====", THREADS);

        sendConcurrently(sender, messages(THREADS, "thread"));

        report();
        assertThat(server.totalMessages()).isEqualTo(THREADS);
        assertThat(sender.totalConnections()).isEqualTo(THREADS);
        assertThat(server.totalConnections()).isEqualTo(THREADS);
        // Not pinned to THREADS: on a loaded machine a thread may finish before the last one
        // connects. More than one open at once is the claim.
        assertThat(sender.maxConcurrentConnections()).isGreaterThan(1);
        assertThat(server.maxConcurrentConnections()).isGreaterThan(1);
    }

    /** The multi-thread case against the real server from application.yml. Sends real mail. */
    @Nested
    @EnabledIfSystemProperty(named = "mail.live.to", matches = ".+@.+")
    class Live {

        @Test
        void multipleThreadsOpenConcurrentConnectionsToRealServer() throws Exception {
            ConnectionCountingMailSender live = senderFromApplicationYml();

            List<SimpleMailMessage> batch = new ArrayList<>();
            for (SimpleMailMessage m : messages(3, "live")) {
                m.setFrom(live.getUsername());
                m.setTo(System.getProperty("mail.live.to"));
                batch.add(m);
            }
            log.info("===== LIVE {}:{} — {} threads =====", live.getHost(), live.getPort(), batch.size());

            sendConcurrently(live, batch.toArray(SimpleMailMessage[]::new));

            log.info("===== client: {} connection(s), max {} concurrent =====",
                    live.totalConnections(), live.maxConcurrentConnections());
            assertThat(live.totalConnections()).isEqualTo(batch.size());
            assertThat(live.maxConcurrentConnections()).isGreaterThan(1);
        }
    }

    private static void sendConcurrently(ConnectionCountingMailSender sender, SimpleMailMessage[] messages) throws Exception {
        AtomicInteger threadSeq = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(messages.length,
                r -> new Thread(r, "mail-sender-" + threadSeq.incrementAndGet()));
        // Start gate: without it the first thread can finish before the last one is scheduled.
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (SimpleMailMessage message : messages) {
                results.add(pool.submit(() -> {
                    go.await();
                    sender.send(message);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> result : results) {
                result.get();  // rethrows a failed send
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void report() {
        log.info("===== client: {} connection(s), max {} concurrent | server: {} connection(s), max {} concurrent, {} message(s) =====",
                sender.totalConnections(), sender.maxConcurrentConnections(),
                server.totalConnections(), server.maxConcurrentConnections(), server.totalMessages());
    }

    private static SimpleMailMessage[] messages(int count, String tag) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom("report@creed.local");
            m.setTo("ops@creed.local");
            m.setSubject("[creed-report] connection probe " + tag + " #" + i);
            m.setText("JavaMailSender connection probe, " + tag + " message " + i);
            return m;
        }).toArray(SimpleMailMessage[]::new);
    }

    private static Properties timeouts() {
        Properties p = new Properties();
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        return p;
    }

    /**
     * Builds the sender from {@code spring.mail.*} the way Boot's {@code MailSenderPropertiesConfiguration}
     * does, so the live case connects exactly as the application would — an SSL setting missing from
     * application.yml shows up here as a connect timeout, not as a test that quietly fixed it.
     *
     * <p>Mirrors {@code ssl.enabled} / {@code ssl.verify-hostname} and passes {@code properties.*}
     * through; {@code ssl.bundle} is not mirrored (it needs the SslBundles bean, i.e. a context).
     */
    private static ConnectionCountingMailSender senderFromApplicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties yml = yaml.getObject();
        // YamlPropertiesFactoryBean does not resolve placeholders, e.g. password: ${CREED_MAIL_PASSWORD:}
        StandardEnvironment env = new StandardEnvironment();
        Function<String, String> prop = key -> {
            String raw = yml.getProperty(key);
            return raw == null ? null : env.resolvePlaceholders(raw);
        };

        ConnectionCountingMailSender sender = new ConnectionCountingMailSender();
        sender.setHost(prop.apply("spring.mail.host"));
        if (prop.apply("spring.mail.port") != null) {
            sender.setPort(Integer.parseInt(prop.apply("spring.mail.port")));
        }
        sender.setUsername(prop.apply("spring.mail.username"));
        sender.setPassword(prop.apply("spring.mail.password"));
        String protocol = Objects.requireNonNullElse(prop.apply("spring.mail.protocol"), "smtp");
        sender.setProtocol(protocol);

        Properties javaMail = sender.getJavaMailProperties();
        String passThrough = "spring.mail.properties.";
        for (String key : yml.stringPropertyNames()) {
            if (key.startsWith(passThrough)) {
                javaMail.setProperty(key.substring(passThrough.length()), prop.apply(key));
            }
        }
        if (Boolean.parseBoolean(prop.apply("spring.mail.ssl.enabled"))) {
            javaMail.setProperty("mail." + protocol + ".ssl.enable", "true");
            if (!"false".equals(prop.apply("spring.mail.ssl.verify-hostname"))) {
                javaMail.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
            }
        }
        // Only where application.yml sets none: JavaMail's default is to wait forever.
        timeouts().forEach(javaMail::putIfAbsent);

        log.info("[client] spring.mail -> {}:{} protocol={} ssl.enabled={} javaMailProperties={}",
                sender.getHost(), sender.getPort(), protocol, prop.apply("spring.mail.ssl.enabled"), javaMail);
        return sender;
    }
}
