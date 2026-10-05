package com.creed.resource.envmatrix.service;

import com.creed.resource.envmatrix.api.dto.AesRecordBatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordDecryptRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordDecryptResult;
import com.creed.resource.envmatrix.api.dto.AesRecordDto;
import com.creed.resource.envmatrix.api.dto.AesRecordSaveRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordSaveResponse;
import com.creed.resource.envmatrix.api.dto.AesRecordUpdateRequest;
import com.creed.resource.envmatrix.domain.EnvAesRecord;
import com.creed.resource.envmatrix.domain.EnvAesRecordRepository;
import com.creed.resource.envmatrix.domain.EnvEndpointRepository;
import com.creed.resource.envmatrix.domain.EnvServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stored AES ciphertexts, one per {@code (appSystem, host, ip, propertyKey)}, plus the server list
 * they are saved against.
 *
 * <p>Stores the IV and salt with each ciphertext (V9, by request), so a record decrypts with nothing
 * supplied — and therefore a copy of the table decrypts with nothing else either. Records saved
 * before V9 have neither, and decrypting them takes the ones supplied with the request.
 * The Secret Key is never stored either, but it needs no storing — it is
 * {@code randomKey + host + ip}, all of which a record holds. Log lines carry ids and counts, never
 * values.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AesRecordService {

    private final EnvAesRecordRepository repository;
    private final EnvEndpointRepository endpointRepository;
    private final AesCryptoService crypto;

    /** Distinct {@code (appSystem, host, ip)} from the endpoint table — the page's server list. */
    @Transactional(readOnly = true)
    public List<EnvServer> servers(String appSystem) {
        return StringUtils.hasText(appSystem)
                ? endpointRepository.findDistinctServersByAppSystem(appSystem)
                : endpointRepository.findDistinctServers();
    }

    @Transactional(readOnly = true)
    public List<AesRecordDto> list(String appSystem, String propertyKey) {
        List<EnvAesRecord> rows = StringUtils.hasText(appSystem)
                ? repository.findByAppSystemOrderByPropertyKeyAscHostAscIpAsc(appSystem)
                : repository.findAllByOrderByAppSystemAscPropertyKeyAscHostAscIpAsc();
        return rows.stream()
                .filter(r -> !StringUtils.hasText(propertyKey) || r.getPropertyKey().equals(propertyKey.strip()))
                .map(AesRecordDto::of)
                .toList();
    }

    /** One plain value, encrypted for and saved against every listed server — a batch of one. */
    @Transactional
    public AesRecordSaveResponse save(AesRecordSaveRequest request) {
        return saveBatch(new AesRecordBatchSaveRequest(
                List.of(new AesRecordBatchSaveRequest.Item(
                        request.propertyKey(), request.plainValue(), request.iv(), request.salt(),
                        request.randomKey(), request.note())),
                request.servers()));
    }

    /**
     * Encrypts every item for every listed server and saves the result, in one transaction: an insert
     * where the server has no row for that property yet, a replacement where it has, and nothing
     * where the stored ciphertext is already identical — CBC with a fixed IV is deterministic, so an
     * unchanged value re-encrypts to the same bytes, and "saved to 10 servers" reports what changed.
     *
     * <p>Each server gets its own ciphertext: the Secret Key is {@code randomKey + host + ip}. The
     * plain value is used and dropped; the ciphertext, randomkey, IV and salt are stored. Keys are
     * derived once per (server, randomkey, salt), not once per item.
     */
    @Transactional
    public AesRecordSaveResponse saveBatch(AesRecordBatchSaveRequest request) {
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < request.items().size(); i++) {
            AesRecordBatchSaveRequest.Item item = request.items().get(i);
            Integer first = seen.putIfAbsent(item.propertyKey().strip(), i);
            if (first != null) {
                throw new DuplicatePropertyKeyException(i, first);
            }
            try {
                crypto.requireUsableKeyMaterial(item.iv(), item.salt());
            } catch (AesCryptoService.InvalidKeyMaterialException e) {
                // Checked for every item before anything is written, and named by position.
                throw new AesCryptoService.InvalidKeyMaterialException("items[" + i + "]." + e.field(), e.getMessage());
            }
        }

        Set<AesRecordSaveRequest.Server> servers = new LinkedHashSet<>();
        request.servers().forEach(s -> servers.add(new AesRecordSaveRequest.Server(
                s.appSystem().strip(), s.host().strip(), s.ip().strip())));

        int inserted = 0;
        int updated = 0;
        List<AesRecordDto> saved = new ArrayList<>();
        AesCryptoService.KeyCache keys = new AesCryptoService.KeyCache();
        for (AesRecordBatchSaveRequest.Item item : request.items()) {
            String propertyKey = item.propertyKey().strip();
            String note = blankToNull(item.note());
            // Not stripped: unlike a property key, a randomkey's spaces are part of the Secret Key.
            String randomKey = emptyToNull(item.randomKey());

            for (AesRecordSaveRequest.Server server : servers) {
                String value = crypto.encrypt(
                        keys.key(AesCryptoService.secretKey(randomKey, server.host(), server.ip()), item.salt()),
                        item.iv(), item.plainValue());
                EnvAesRecord row = repository
                        .findByAppSystemAndHostAndIpAndPropertyKey(server.appSystem(), server.host(), server.ip(), propertyKey)
                        .orElse(null);
                if (row == null) {
                    row = new EnvAesRecord();
                    row.setAppSystem(server.appSystem());
                    row.setHost(server.host());
                    row.setIp(server.ip());
                    row.setPropertyKey(propertyKey);
                    inserted++;
                } else if (Objects.equals(row.getEncryptedValue(), value) && Objects.equals(row.getNote(), note)
                        && Objects.equals(row.getRandomKey(), randomKey)
                        && Objects.equals(row.getIv(), item.iv()) && Objects.equals(row.getSalt(), item.salt())) {
                    saved.add(AesRecordDto.of(row));
                    continue;
                } else {
                    updated++;
                }
                row.setEncryptedValue(value);
                row.setRandomKey(randomKey);
                row.setIv(item.iv());
                row.setSalt(item.salt());
                row.setNote(note);
                saved.add(AesRecordDto.of(repository.saveAndFlush(row)));
            }
        }
        log.info("aes records saved items={} servers={} inserted={} updated={}",
                request.items().size(), servers.size(), inserted, updated);
        return new AesRecordSaveResponse(inserted, updated, saved);
    }

    @Transactional
    public AesRecordDto update(Long id, AesRecordUpdateRequest request) {
        EnvAesRecord row = require(id);
        String appSystem = request.appSystem().strip();
        String host = request.host().strip();
        String ip = request.ip().strip();
        String propertyKey = request.propertyKey().strip();
        repository.findByAppSystemAndHostAndIpAndPropertyKey(appSystem, host, ip, propertyKey)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new DuplicateAesRecordException(other.getId());
                });
        row.setAppSystem(appSystem);
        row.setHost(host);
        row.setIp(ip);
        row.setPropertyKey(propertyKey);
        row.setEncryptedValue(request.encryptedValue().strip());
        row.setRandomKey(emptyToNull(request.randomKey()));
        row.setIv(emptyToNull(request.iv()));
        row.setSalt(emptyToNull(request.salt()));
        row.setNote(blankToNull(request.note()));
        return AesRecordDto.of(repository.saveAndFlush(row));
    }

    /** Deletes every listed row that exists; an unknown id is ignored, so a retried delete is harmless. */
    @Transactional
    public int delete(List<Long> ids) {
        List<EnvAesRecord> rows = repository.findAllById(ids);
        repository.deleteAll(rows);
        log.info("aes records deleted ids={}", rows.stream().map(EnvAesRecord::getId).toList());
        return rows.size();
    }

    /**
     * Decrypts stored rows, one result per requested id in request order (a repeated id is answered
     * once). The Secret Key comes from the record — {@code randomKey + host + ip} — and so do the IV
     * and salt when it has them (since V9); the request's are only used for older records. Every failure is that row's own result: an unusable IV or salt
     * ({@code invalid_key_material}), a value that does not decrypt ({@code decrypt_failed}), or an
     * unknown id ({@code not_found}).
     */
    @Transactional(readOnly = true)
    public List<AesRecordDecryptResult> decrypt(AesRecordDecryptRequest request) {
        Map<Long, EnvAesRecord> byId = repository
                .findAllById(request.items().stream().map(AesRecordDecryptRequest.Item::id).toList()).stream()
                .collect(Collectors.toMap(EnvAesRecord::getId, Function.identity()));
        List<AesRecordDecryptResult> results = new ArrayList<>();
        Set<Long> answered = new HashSet<>();
        AesCryptoService.KeyCache keys = new AesCryptoService.KeyCache();
        for (AesRecordDecryptRequest.Item item : request.items()) {
            Long id = item.id();
            if (!answered.add(id)) {
                continue;
            }
            EnvAesRecord row = byId.get(id);
            if (row == null) {
                results.add(new AesRecordDecryptResult(id, null, "not_found", "no AES record with id " + id));
                continue;
            }
            // The record's own IV/salt win: they are what it was encrypted with. The request's are the
            // fallback for rows saved before V9, which have none.
            String iv = row.getIv() != null ? row.getIv() : item.iv();
            String salt = row.getSalt() != null ? row.getSalt() : item.salt();
            try {
                crypto.requireUsableKeyMaterial(iv, salt);
                String secretKey = AesCryptoService.secretKey(row.getRandomKey(), row.getHost(), row.getIp());
                results.add(new AesRecordDecryptResult(id,
                        crypto.decrypt(keys.key(secretKey, salt), iv, row.getEncryptedValue()), null, null));
            } catch (AesCryptoService.InvalidKeyMaterialException e) {
                results.add(new AesRecordDecryptResult(id, null, "invalid_key_material", e.field() + ": " + e.getMessage()));
            } catch (AesCryptoService.DecryptException e) {
                results.add(new AesRecordDecryptResult(id, null, "decrypt_failed", e.getMessage()));
            }
        }
        long failed = results.stream().filter(r -> r.error() != null).count();
        log.info("aes records decrypted requested={} failed={}", results.size(), failed);
        return results;
    }

    private EnvAesRecord require(Long id) {
        return repository.findById(id).orElseThrow(() -> new AesRecordNotFoundException(id));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
    }

    public static class AesRecordNotFoundException extends RuntimeException {
        public AesRecordNotFoundException(Long id) {
            super("no AES record with id " + id);
        }
    }

    /** The same property key twice in one batch save — rejected as a 400 naming the later item. */
    public static class DuplicatePropertyKeyException extends RuntimeException {
        private final int index;

        public DuplicatePropertyKeyException(int index, int firstIndex) {
            super("the same property key is already item " + firstIndex + " of this save");
            this.index = index;
        }

        public String field() {
            return "items[" + index + "].propertyKey";
        }
    }

    public static class DuplicateAesRecordException extends RuntimeException {
        public DuplicateAesRecordException(Long existingId) {
            super("that server already has a value for this property key, as record #" + existingId);
        }
    }
}
