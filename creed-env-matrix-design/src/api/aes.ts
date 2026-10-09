import { request, toQuery } from './client';
import type {
  AesBatchCryptoItem,
  AesBatchCryptoResult,
  AesCryptoResult,
  AesKeys,
  AesRecord,
  AesRecordBatchSaveRequest,
  AesRecordDecryptItem,
  AesRecordDecryptResult,
  AesRecordFilterOptions,
  AesRecordPage,
  AesRecordQuery,
  AesRecordSaveRequest,
  AesRecordSort,
  AesRecordSaveResponse,
  AesServer,
  AesServerRef,
} from './types';

const recordParams = (q: AesRecordQuery) => ({
  appSystem: q.appSystem, envInstance: q.envInstances, host: q.hosts, propertyKey: q.propertyKeys,
});

/**
 * AES encryption page. Keys are always POSTed in a body, never put in a query string — URLs end up
 * in access logs and browser history.
 */
export const aesApi = {
  servers: (appSystem?: string) => request<AesServer[]>(`/aes/servers${toQuery({ appSystem })}`),

  /**
   * One server; Secret Key = randomKey + host + ip, key = PBKDF2(Secret Key, salt). 400 naming `iv`
   * when it is not 16 UTF-8 bytes, or `salt` when it is empty.
   */
  encrypt: (keys: AesKeys, plainValue: string) =>
    request<AesCryptoResult>('/aes/encrypt', { method: 'POST', body: JSON.stringify({ ...keys, plainValue }) }),

  /** 422 `decrypt_failed` when the keys do not match. */
  decrypt: (keys: AesKeys, encryptedValue: string) =>
    request<AesCryptoResult>('/aes/decrypt', { method: 'POST', body: JSON.stringify({ ...keys, encryptedValue }) }),

  /** Every row against its own server; failures come back per row, in request order. */
  encryptBatch: (items: AesBatchCryptoItem[]) =>
    request<AesBatchCryptoResult[]>('/aes/encrypt/batch', { method: 'POST', body: JSON.stringify({ items }) }),

  decryptBatch: (items: AesBatchCryptoItem[]) =>
    request<AesBatchCryptoResult[]>('/aes/decrypt/batch', { method: 'POST', body: JSON.stringify({ items }) }),

  records: (appSystem?: string) => request<AesRecord[]>(`/aes/records${toQuery({ appSystem })}`),

  /** One page, filtered and sorted by the backend. `page` is 1-based; `size` at most 100. */
  recordPage: (query: AesRecordQuery, page: number, size: number, sort?: AesRecordSort) =>
    request<AesRecordPage>(`/aes/records/page${toQuery({
      ...recordParams(query), page: String(page), size: String(size), sort: sort?.field, order: sort?.order,
    })}`),

  /** Options for the result list's filters; the query's property keys are not sent (nothing narrows by them). */
  recordFilters: (query: AesRecordQuery) =>
    request<AesRecordFilterOptions>(`/aes/records/filters${toQuery({ ...recordParams(query), propertyKey: undefined })}`),

  /** Servers holding a value for any of the property keys — the server list's "saved" tags. */
  savedServers: (propertyKeys: string[]) =>
    request<AesServerRef[]>(`/aes/records/saved-servers${toQuery({ propertyKey: propertyKeys })}`),

  /** Encrypted per server by the backend; a server that already has the property gets its value replaced. */
  save: (body: AesRecordSaveRequest) =>
    request<AesRecordSaveResponse>('/aes/records', { method: 'POST', body: JSON.stringify(body) }),

  /** Every item encrypted for every server, one transaction; a repeated property key is a 400 naming the item. */
  saveBatch: (body: AesRecordBatchSaveRequest) =>
    request<AesRecordSaveResponse>('/aes/records/batch', { method: 'POST', body: JSON.stringify(body) }),

  remove: (ids: number[]) =>
    request<void>(`/aes/records${toQuery({ ids: ids.map(String) })}`, { method: 'DELETE' }),

  /** Each record with its own Secret Key and the IV and salt given for it; per-row results. */
  decryptRecords: (items: AesRecordDecryptItem[]) =>
    request<AesRecordDecryptResult[]>('/aes/records/decrypt', { method: 'POST', body: JSON.stringify({ items }) }),
};
