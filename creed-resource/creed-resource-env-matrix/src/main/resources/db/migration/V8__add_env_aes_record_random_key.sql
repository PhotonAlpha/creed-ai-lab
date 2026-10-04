-- Env Matrix Viewer — store the randomkey a ciphertext was produced with, for the result list.
--
-- V7 stored no key material at all. The randomkey is now kept per row because the page shows it:
-- the AES key is SHA-256(secretKey + randomKey), so the randomkey on its own decrypts nothing — it
-- plays the part of a salt. The Secret Key and the IV are still never stored.
--
-- Nullable: rows saved before this migration have no recorded randomkey, and "unknown" is not "".
-- A separate migration, not an edit to V7, because V7 is already applied — Flyway rejects a changed
-- checksum.

alter table env_aes_record add column random_key varchar(256);
