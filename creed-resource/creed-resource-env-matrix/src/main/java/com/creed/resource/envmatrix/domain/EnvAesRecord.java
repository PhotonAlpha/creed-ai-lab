package com.creed.resource.envmatrix.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One AES-encrypted property value for one server.
 *
 * <p>Holds the ciphertext and the randomkey it was saved with — never the Secret Key or the IV, so
 * nothing in this table can be decrypted without the user supplying the Secret Key again. The
 * randomkey alone is no help: it is only hashed together with the Secret Key (see {@code V8}).
 *
 * <p>The identity constraint is declared here as well as in {@code V7}, so the H2 schema the tests
 * generate from the entities enforces the same rule.
 */
@Entity
@Table(
        name = "env_aes_record",
        uniqueConstraints = @UniqueConstraint(
                name = "ux_env_aes_record_identity",
                columnNames = {"app_system", "host", "ip", "property_key"}))
@Getter
@Setter
@NoArgsConstructor
public class EnvAesRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_system", nullable = false, length = 64)
    private String appSystem;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "ip", nullable = false, length = 45)
    private String ip;

    @Column(name = "property_key", nullable = false, length = 255)
    private String propertyKey;

    /** Base64 of {@code AES/CBC/PKCS5Padding} — see {@code AesCryptoService}. */
    @Column(name = "encrypted_value", nullable = false, length = 16384)
    private String encryptedValue;

    /**
     * The randomkey in the form when the row was saved — recorded, not verified: a pasted ciphertext
     * cannot be checked against it without the Secret Key. {@code null} for rows saved before V8.
     */
    @Column(name = "random_key", length = 256)
    private String randomKey;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
