-- Env Matrix Viewer — store the IV and the salt each ciphertext was produced with.
--
-- Until now both were typed in for every decrypt and never stored. They are stored from here on, by
-- request, so the result list can decrypt a record with nothing typed in. With the Secret Key derived
-- from randomkey + host + ip (all stored or public), this means A COPY OF THIS TABLE DECRYPTS EVERY
-- VALUE IN IT — treat env_aes_record, and every backup of it, as holding the plaintexts.
--
-- Nullable: rows saved before this migration have neither, and decrypting them still takes the IV
-- and salt from the request.

alter table env_aes_record add column iv varchar(64);
alter table env_aes_record add column salt varchar(256);
