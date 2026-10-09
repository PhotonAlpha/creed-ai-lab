package com.creed.gatewayproxy.service.splunk;

import java.io.Console;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import com.creed.gatewayproxy.config.SplunkProperties;

/**
 * The broker's secrets, supplied when the process starts and never read from a configuration file:
 *
 * <ol>
 *   <li>{@code NAME} — an environment variable (or {@code -DNAME=…});</li>
 *   <li>{@code NAME_FILE} — a file holding it (Docker/Kubernetes secrets, a Vault agent sink), trimmed;</li>
 *   <li>otherwise, when a terminal is attached, a prompt on it with echo off.</li>
 * </ol>
 *
 * {@code NAME} is {@code SPLUNK_TARGET_<ID>_PASSWORD} per target (only when the real call is on —
 * the mock needs none), {@code SPLUNK_DB_PASSWORD} for the pg/mysql audit store, and
 * {@code ENV_MATRIX_TOTP_SECRET}. A missing Splunk password leaves that target "not configured" (503
 * on login, the code unused); a missing TOTP secret gets a random one for this run. Values are never
 * logged.
 */
@Slf4j
public class SplunkSecrets {

    private final Map<String, String> passwords = new HashMap<>();
    private final String totpSecret;
    private final boolean totpGenerated;
    private final String dbPassword;

    /** A prompt reading one line with echo off, or null when there is no one to ask. */
    public interface Prompt extends Function<String, String> {
    }

    public SplunkSecrets(Environment env, SplunkProperties splunk, Prompt prompt) {
        if (splunk.enabled()) {
            for (SplunkProperties.Target t : splunk.targets()) {
                String who = t.username() != null ? t.username() + " @ " : "";
                String value = resolve(env, t.passwordVariable(), prompt,
                        "Splunk password for target " + t.id() + " (" + who + t.loginUrl() + ")");
                if (value != null) passwords.put(t.id(), value);
            }
        }
        String secret = resolve(env, "ENV_MATRIX_TOTP_SECRET", prompt,
                "TOTP secret (Base32; Enter = a random one for this run)");
        if (secret != null) {
            Totp.base32Decode(secret); // fail at startup, not on the first code
            totpSecret = secret;
            totpGenerated = false;
        } else {
            totpSecret = randomBase32();
            totpGenerated = true;
        }
        dbPassword = "memory".equals(splunk.audit().store()) ? null
                : resolve(env, "SPLUNK_DB_PASSWORD", prompt, "Audit database password for " + splunk.audit().username());
    }

    public String password(SplunkProperties.Target target) {
        return passwords.get(target.id());
    }

    public String totpSecret() {
        return totpSecret;
    }

    /** True when no secret was supplied — codes then only mean something to this process (and its page). */
    public boolean totpGenerated() {
        return totpGenerated;
    }

    public String dbPassword() {
        return dbPassword;
    }

    static String resolve(Environment env, String name, Prompt prompt, String question) {
        String file = env.getProperty(name + "_FILE");
        if (StringUtils.hasText(file)) {
            try {
                // trim: `echo secret > file` leaves a newline, and a trailing \n in a password fails the login.
                return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException(name + "_FILE: cannot read " + file + ": " + e.getMessage(), e);
            }
        }
        String value = env.getProperty(name);
        if (StringUtils.hasText(value)) return value;
        String answer = prompt == null ? null : prompt.apply(question + " [" + name + "]: ");
        if (StringUtils.hasText(answer)) return answer;
        log.warn("{} not supplied (environment, {}_FILE or the startup prompt)", name, name);
        return null;
    }

    private static String randomBase32() {
        byte[] bytes = new byte[20];
        new SecureRandom().nextBytes(bytes);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return out.toString();
    }

    /**
     * The terminal prompt, or null when stdin is not one (systemd, nohup, a CI job) — then a missing
     * secret is simply missing. JDK 22+ hands out a Console even for redirected streams, so
     * {@code isTerminal()} decides where it exists (looked up reflectively: the source level is 21).
     */
    public static Prompt terminalPrompt(boolean enabled) {
        Console console = System.console();
        if (!enabled || console == null || !isTerminal(console)) return null;
        return question -> {
            char[] answer = console.readPassword("%s", question);
            return answer == null ? null : new String(answer);
        };
    }

    private static boolean isTerminal(Console console) {
        try {
            Method m = Console.class.getMethod("isTerminal");
            return (boolean) m.invoke(console);
        } catch (ReflectiveOperationException e) {
            return true; // JDK 21: System.console() is non-null only for a terminal
        }
    }
}
