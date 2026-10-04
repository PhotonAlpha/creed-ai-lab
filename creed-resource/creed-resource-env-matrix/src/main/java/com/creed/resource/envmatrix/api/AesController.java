package com.creed.resource.envmatrix.api;

import com.creed.resource.envmatrix.api.dto.AesBatchCryptoRequest;
import com.creed.resource.envmatrix.api.dto.AesBatchCryptoResult;
import com.creed.resource.envmatrix.api.dto.AesCryptoResponse;
import com.creed.resource.envmatrix.api.dto.AesDecryptRequest;
import com.creed.resource.envmatrix.api.dto.AesEncryptRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordBatchSaveRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordDecryptRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordDecryptResult;
import com.creed.resource.envmatrix.api.dto.AesRecordDto;
import com.creed.resource.envmatrix.api.dto.AesRecordSaveRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordSaveResponse;
import com.creed.resource.envmatrix.api.dto.AesRecordUpdateRequest;
import com.creed.resource.envmatrix.domain.EnvServer;
import com.creed.resource.envmatrix.service.AesCryptoService;
import com.creed.resource.envmatrix.service.AesRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AES encryption page.
 *
 * <pre>
 * GET    /aes/servers?appSystem=        distinct (appSystem, host, ip) from the endpoint table
 * POST   /aes/encrypt                   {iv, randomKey, host, ip, plainValue}     -> {encryptedValue}
 * POST   /aes/decrypt                   {iv, randomKey, host, ip, encryptedValue} -> {plainValue} | 422
 * POST   /aes/encrypt/batch             {items: [{iv, randomKey, host, ip, value}]} -> per-row results
 * POST   /aes/decrypt/batch             same shape, value = ciphertext                -> per-row results
 * GET    /aes/records?appSystem=&amp;propertyKey=
 * POST   /aes/records                   {propertyKey, plainValue, iv, randomKey, note, servers[]} -> encrypt + upsert per server
 * POST   /aes/records/batch             {items: [{propertyKey, plainValue, iv, randomKey, note}], servers[]}
 * PUT    /aes/records/{id}
 * DELETE /aes/records?ids=1&amp;ids=2      -> 204
 * POST   /aes/records/decrypt           {items: [{id, iv}]} -> per-row results
 * </pre>
 *
 * The Secret Key is never an input: it is randomKey + host + ip (see AesCryptoService). Keys are
 * POSTed, never put in a URL: a query string ends up in access logs and browser history.
 * Responses carrying a plaintext are {@code no-store}.
 */
@RestController
@RequestMapping("/api/env-matrix/aes")
@RequiredArgsConstructor
public class AesController {

    private final AesCryptoService crypto;
    private final AesRecordService records;

    @GetMapping("/servers")
    public List<EnvServer> servers(@RequestParam(required = false) String appSystem) {
        return records.servers(appSystem);
    }

    @PostMapping("/encrypt")
    public AesCryptoResponse encrypt(@Valid @RequestBody AesEncryptRequest body) {
        String encrypted = crypto.encrypt(AesCryptoService.secretKey(body.randomKey(), body.host(), body.ip()), body.iv(), body.plainValue());
        return new AesCryptoResponse(encrypted, null, AesCryptoService.ALGORITHM);
    }

    @PostMapping("/decrypt")
    public ResponseEntity<AesCryptoResponse> decrypt(@Valid @RequestBody AesDecryptRequest body) {
        String plain = crypto.decrypt(AesCryptoService.secretKey(body.randomKey(), body.host(), body.ip()), body.iv(), body.encryptedValue());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AesCryptoResponse(null, plain, AesCryptoService.ALGORITHM));
    }

    @PostMapping("/encrypt/batch")
    public List<AesBatchCryptoResult> encryptBatch(@Valid @RequestBody AesBatchCryptoRequest body) {
        return crypto.encryptAll(body.items());
    }

    @PostMapping("/decrypt/batch")
    public ResponseEntity<List<AesBatchCryptoResult>> decryptBatch(@Valid @RequestBody AesBatchCryptoRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(crypto.decryptAll(body.items()));
    }

    @GetMapping("/records")
    public List<AesRecordDto> list(@RequestParam(required = false) String appSystem,
                                   @RequestParam(required = false) String propertyKey) {
        return records.list(appSystem, propertyKey);
    }

    @PostMapping("/records")
    public AesRecordSaveResponse save(@Valid @RequestBody AesRecordSaveRequest body) {
        return records.save(body);
    }

    @PostMapping("/records/batch")
    public AesRecordSaveResponse saveBatch(@Valid @RequestBody AesRecordBatchSaveRequest body) {
        return records.saveBatch(body);
    }

    @PutMapping("/records/{id}")
    public AesRecordDto update(@PathVariable Long id, @Valid @RequestBody AesRecordUpdateRequest body) {
        return records.update(id, body);
    }

    @DeleteMapping("/records")
    public ResponseEntity<Void> delete(@RequestParam List<Long> ids) {
        records.delete(ids);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/records/decrypt")
    public ResponseEntity<List<AesRecordDecryptResult>> decryptRecords(@Valid @RequestBody AesRecordDecryptRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(records.decrypt(body));
    }
}
