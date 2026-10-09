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
import com.creed.resource.envmatrix.api.dto.AesRecordFilterOptions;
import com.creed.resource.envmatrix.api.dto.AesRecordPage;
import com.creed.resource.envmatrix.api.dto.AesRecordSaveRequest;
import com.creed.resource.envmatrix.api.dto.AesRecordSaveResponse;
import com.creed.resource.envmatrix.api.dto.AesRecordUpdateRequest;
import com.creed.resource.envmatrix.api.dto.AesServerRef;
import com.creed.resource.envmatrix.domain.EnvServer;
import com.creed.resource.envmatrix.service.AesCryptoService;
import com.creed.resource.envmatrix.service.AesRecordQuery;
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
 * GET    /aes/records?appSystem=&amp;propertyKey=        every record (kept for scripts; the page uses /page)
 * GET    /aes/records/page?appSystem=&amp;envInstance=*&amp;host=*&amp;propertyKey=*&amp;page=1&amp;size=100&amp;sort=&amp;order=
 *                                -> {items, total, page, size}; size &lt;= 100, sort host|propertyKey|updatedAt|appSystem
 * GET    /aes/records/filters?appSystem=&amp;envInstance=*&amp;host=*   -> {envInstances, hosts, propertyKeys}
 * GET    /aes/records/saved-servers?propertyKey=*       -> [{appSystem, host, ip}] holding any of them
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
        String encrypted = crypto.encrypt(AesCryptoService.secretKey(body.randomKey(), body.host(), body.ip()), body.salt(), body.iv(), body.plainValue());
        return new AesCryptoResponse(encrypted, null, AesCryptoService.ALGORITHM);
    }

    @PostMapping("/decrypt")
    public ResponseEntity<AesCryptoResponse> decrypt(@Valid @RequestBody AesDecryptRequest body) {
        String plain = crypto.decrypt(AesCryptoService.secretKey(body.randomKey(), body.host(), body.ip()), body.salt(), body.iv(), body.encryptedValue());
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

    @GetMapping("/records/page")
    public AesRecordPage page(@RequestParam(required = false) String appSystem,
                              @RequestParam(name = "envInstance", required = false) List<String> envInstances,
                              @RequestParam(name = "host", required = false) List<String> hosts,
                              @RequestParam(name = "propertyKey", required = false) List<String> propertyKeys,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "" + AesRecordService.MAX_PAGE_SIZE) int size,
                              @RequestParam(required = false) String sort,
                              @RequestParam(required = false) String order) {
        return records.page(new AesRecordQuery(appSystem, envInstances, hosts, propertyKeys), page, size, sort, order);
    }

    @GetMapping("/records/filters")
    public AesRecordFilterOptions filters(@RequestParam(required = false) String appSystem,
                                          @RequestParam(name = "envInstance", required = false) List<String> envInstances,
                                          @RequestParam(name = "host", required = false) List<String> hosts) {
        return records.filterOptions(new AesRecordQuery(appSystem, envInstances, hosts, null));
    }

    @GetMapping("/records/saved-servers")
    public List<AesServerRef> savedServers(@RequestParam(name = "propertyKey", required = false) List<String> propertyKeys) {
        return records.savedServers(propertyKeys);
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
