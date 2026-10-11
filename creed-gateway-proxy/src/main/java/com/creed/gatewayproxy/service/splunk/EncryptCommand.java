package com.creed.gatewayproxy.service.splunk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * {@code java -jar creed-gateway-proxy.jar encrypt} — turns a password into the {@code ENC(…)} value
 * for {@code creed.splunk.targets[].password}, without starting the application.
 *
 * <p>The master key comes from {@code CREED_SECRETS_KEY_FILE} / {@code CREED_SECRETS_KEY} or a prompt;
 * the password from a prompt (asked twice), or the first line of stdin when there is no terminal
 * ({@code java -jar … encrypt < pw.txt}). Neither is ever an argument — that would put it in
 * {@code ps} and the shell history, the very thing this exists to avoid. Only the value goes to stdout.
 */
public final class EncryptCommand {

    private EncryptCommand() {
    }

    public static int run(Environment env, SplunkSecrets.Prompt prompt, InputStream stdin, PrintStream out, PrintStream err) {
        try {
            String key = SplunkSecrets.lookup(env, SecretCipher.KEY_VARIABLE);
            if (key == null && prompt != null) {
                key = prompt.apply("Master key [" + SecretCipher.KEY_VARIABLE + "]: ");
                if (key != null && !key.equals(prompt.apply("Master key again: "))) {
                    err.println("encrypt: the two master keys differ");
                    return 1;
                }
            }
            if (key == null || key.isEmpty()) {
                err.println("encrypt: no master key — set " + SecretCipher.KEY_VARIABLE + "_FILE or "
                        + SecretCipher.KEY_VARIABLE + ", or run on a terminal");
                return 1;
            }
            SecretCipher cipher = new SecretCipher(key);

            String plain;
            if (prompt != null) {
                plain = prompt.apply("Password to encrypt: ");
                if (plain != null && !plain.equals(prompt.apply("Password again: "))) {
                    err.println("encrypt: the two passwords differ");
                    return 1;
                }
            } else {
                plain = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8)).readLine();
            }
            if (plain == null || plain.isEmpty()) {
                err.println("encrypt: empty password");
                return 1;
            }
            String value = cipher.encrypt(plain);
            if (!Objects.equals(cipher.decrypt(value), plain)) throw new IllegalStateException("round trip failed");
            out.println(value);
            return 0;
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            err.println("encrypt: " + e.getMessage());
            return 1;
        }
    }

    public static int run() {
        return run(new StandardEnvironment(), SplunkSecrets.terminalPrompt(true), System.in, System.out, System.err);
    }
}
