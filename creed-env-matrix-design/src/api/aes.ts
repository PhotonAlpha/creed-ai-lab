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
  AesRecordSaveRequest,
  AesRecordSaveResponse,
  AesServer,
} from './types';

/**
 * AES encryption page. Keys are always POSTed in a body, never put in a query string — URLs end up
 * in access logs and browser history.
 */
export const aesApi = {
  servers: (appSystem?: string) => request<AesServer[]>(`/aes/servers${toQuery({ appSystem })}`),

  /** 400 `validation_failed` naming `iv` when it is not 16 UTF-8 bytes. */
  encrypt: (keys: AesKeys, plainValue: string) =>
    request<AesCryptoResult>('/aes/encrypt', { method: 'POST', body: JSON.stringify({ ...keys, plainValue }) }),

  /** 422 `decrypt_failed` when the keys do not match. */
  decrypt: (keys: AesKeys, encryptedValue: string) =>
    request<AesCryptoResult>('/aes/decrypt', { method: 'POST', body: JSON.stringify({ ...keys, encryptedValue }) }),

  /** Every row with its own keys; failures come back per row, in request order. */
  encryptBatch: (items: AesBatchCryptoItem[]) =>
    request<AesBatchCryptoResult[]>('/aes/encrypt/batch', { method: 'POST', body: JSON.stringify({ items }) }),

  decryptBatch: (items: AesBatchCryptoItem[]) =>
    request<AesBatchCryptoResult[]>('/aes/decrypt/batch', { method: 'POST', body: JSON.stringify({ items }) }),

  records: (appSystem?: string) => request<AesRecord[]>(`/aes/records${toQuery({ appSystem })}`),

  /** One row per server; a server that already has the property gets its value replaced. */
  save: (body: AesRecordSaveRequest) =>
    request<AesRecordSaveResponse>('/aes/records', { method: 'POST', body: JSON.stringify(body) }),

  /** Every item to every server, one transaction; a repeated property key is a 400 naming the item. */
  saveBatch: (body: AesRecordBatchSaveRequest) =>
    request<AesRecordSaveResponse>('/aes/records/batch', { method: 'POST', body: JSON.stringify(body) }),

  remove: (ids: number[]) =>
    request<void>(`/aes/records${toQuery({ ids: ids.map(String) })}`, { method: 'DELETE' }),

  /** Each record with its own keys; per-row results, so one failure does not hide the rest. */
  decryptRecords: (items: AesRecordDecryptItem[]) =>
    request<AesRecordDecryptResult[]>('/aes/records/decrypt', { method: 'POST', body: JSON.stringify({ items }) }),
};
