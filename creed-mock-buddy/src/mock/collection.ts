import { randomUUID } from 'node:crypto';
import type { CollectionDefinition, JsonValue } from './definition.js';

export type Record_ = Record<string, JsonValue>;

export interface ListQuery {
  page?: number;
  limit?: number;
  sort?: string;
  order?: 'asc' | 'desc';
  /** Remaining query params are treated as equality filters on top-level fields. */
  filters?: Record<string, string>;
}

export interface ListResult {
  items: Record_[];
  total: number;
  page: number;
  limit: number;
}

/**
 * In-memory CRUD store backing an auto-generated collection. Insertion order is preserved so
 * `GET /things` is stable between calls, which matters when a frontend snapshot-tests against it.
 */
export class CollectionStore {
  readonly idField: string;

  private readonly seed: readonly Record_[];
  private rows = new Map<string, Record_>();
  private numericIds = true;
  private nextId = 1;

  constructor(definition: CollectionDefinition) {
    this.idField = definition.idField;
    this.seed = structuredClone(definition.seed) as Record_[];
    this.reset();
  }

  reset(): void {
    this.rows = new Map();
    this.nextId = 1;
    // If every seeded id is numeric we keep issuing numbers; otherwise fall back to uuids so we
    // never hand out an id that collides with a string key already in use.
    this.numericIds = this.seed.every((row) => typeof row[this.idField] === 'number');

    for (const row of this.seed) {
      const clone = structuredClone(row) as Record_;
      const id = clone[this.idField];
      const key = id === undefined ? this.mintId() : String(id);
      if (id === undefined) clone[this.idField] = this.numericIds ? Number(key) : key;
      this.rows.set(key, clone);
      if (this.numericIds && typeof id === 'number' && id >= this.nextId) {
        this.nextId = id + 1;
      }
    }
  }

  private mintId(): string {
    if (!this.numericIds) return randomUUID();
    const id = this.nextId;
    this.nextId += 1;
    return String(id);
  }

  size(): number {
    return this.rows.size;
  }

  list(query: ListQuery = {}): ListResult {
    let items = [...this.rows.values()];

    if (query.filters) {
      for (const [field, expected] of Object.entries(query.filters)) {
        items = items.filter((row) => String(row[field] ?? '') === expected);
      }
    }

    if (query.sort) {
      const field = query.sort;
      const direction = query.order === 'desc' ? -1 : 1;
      items.sort((a, b) => {
        const left = a[field];
        const right = b[field];
        if (left === right) return 0;
        if (left === undefined || left === null) return -direction;
        if (right === undefined || right === null) return direction;
        return (left < right ? -1 : 1) * direction;
      });
    }

    const total = items.length;
    const limit = query.limit && query.limit > 0 ? query.limit : total;
    const page = query.page && query.page > 0 ? query.page : 1;
    const start = (page - 1) * limit;

    return { items: items.slice(start, start + limit), total, page, limit };
  }

  get(id: string): Record_ | undefined {
    return this.rows.get(id);
  }

  create(payload: Record_): Record_ {
    const supplied = payload[this.idField];
    const key = supplied === undefined || supplied === null ? this.mintId() : String(supplied);
    if (this.rows.has(key)) {
      throw new ConflictError(`${this.idField} "${key}" already exists`);
    }
    const row: Record_ = { ...payload, [this.idField]: this.numericIds ? Number(key) : key };
    this.rows.set(key, row);
    if (this.numericIds) {
      const numeric = Number(key);
      if (Number.isFinite(numeric) && numeric >= this.nextId) this.nextId = numeric + 1;
    }
    return row;
  }

  /** Full replace; the id in the URL always wins over the id in the payload. */
  replace(id: string, payload: Record_): Record_ | undefined {
    if (!this.rows.has(id)) return undefined;
    const row: Record_ = { ...payload, [this.idField]: this.coerceId(id) };
    this.rows.set(id, row);
    return row;
  }

  patch(id: string, payload: Record_): Record_ | undefined {
    const existing = this.rows.get(id);
    if (!existing) return undefined;
    const row: Record_ = { ...existing, ...payload, [this.idField]: this.coerceId(id) };
    this.rows.set(id, row);
    return row;
  }

  remove(id: string): boolean {
    return this.rows.delete(id);
  }

  private coerceId(id: string): JsonValue {
    if (!this.numericIds) return id;
    const numeric = Number(id);
    return Number.isFinite(numeric) ? numeric : id;
  }
}

export class ConflictError extends Error {}
