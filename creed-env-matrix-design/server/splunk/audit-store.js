/**
 * Where the broker's audit rows go. Three stores, one shape (camelCase, as the page reads them):
 *
 *  - memory — the default (SPLUNK_AUDIT_STORE unset), and always the mock's; newest 500, gone on restart.
 *  - pg     — SPLUNK_AUDIT_STORE=pg; `<schema>.splunk_audit` in the env_matrix database.
 *  - mysql  — SPLUNK_AUDIT_STORE=mysql; `splunk_audit` in the database named by SPLUNK_DB_URL.
 *
 * The pg table lives in its own schema (`splunk_broker`), not in `public`, on purpose.
 * `public.splunk_audit` is Flyway V6 of creed-resource-env-matrix, which can never be removed from
 * that module (Flyway refuses an applied migration that has gone missing). If this store created
 * `public.splunk_audit` on a fresh database before the Java service first started, V6 would then
 * fail with "relation already exists" and take the Java service's startup with it. A separate schema
 * makes the two independent in either start order. On first start, rows already in the legacy table
 * are copied across once; the legacy table itself is left alone.
 *
 * The MySQL table needs no schema of its own: the Flyway clash above is a PostgreSQL one, and in
 * MySQL a schema *is* a database — creating one takes privileges an application account rarely has.
 *
 * Drivers (`pg`, `mysql2`) are imported lazily, in init(), so the mock (memory store only) runs
 * without either, and a pg deployment never loads mysql2.
 */
const COLUMNS = [
  ['correlationId', 'correlation_id'],
  ['eventType', 'event_type'],
  ['outcome', 'outcome'],
  ['reason', 'reason'],
  ['detail', 'detail'],
  ['clientIp', 'client_ip'],
  ['forwardedFor', 'forwarded_for'],
  ['userAgent', 'user_agent'],
  ['serverStep', 'server_step'],
  ['matchedStep', 'matched_step'],
  ['splunkMode', 'splunk_mode'],
  ['httpStatus', 'http_status'],
  ['cookieFingerprint', 'cookie_fingerprint'],
  ['durationMs', 'duration_ms'],
];

/** Same columns as V6, so the one-time copy is a plain insert … select. */
const ddl = (table, schema) => `
  create schema if not exists ${schema};
  create table if not exists ${table} (
      id                 bigserial    primary key,
      correlation_id     varchar(36)  not null,
      event_type         varchar(16)  not null,   -- OTP_VERIFY | SPLUNK_LOGIN
      outcome            varchar(8)   not null,   -- SUCCESS | FAILURE
      reason             varchar(32),
      detail             varchar(512),
      client_ip          varchar(64),
      forwarded_for      varchar(256),
      user_agent         varchar(256),
      server_step        bigint,
      matched_step       bigint,
      splunk_mode        varchar(8),
      http_status        integer,
      cookie_fingerprint varchar(16),            -- 16 hex of SHA-256; the cookie itself is never stored
      duration_ms        bigint,
      created_at         timestamptz  not null default now()
  );
  create index if not exists ix_splunk_audit_created_at on ${table} (created_at desc);
  create index if not exists ix_splunk_audit_correlation on ${table} (correlation_id);
`;

export class MemoryAuditStore {
  rows = [];
  seq = 0;

  async init() {}

  async save(row) {
    this.rows.unshift({ id: (this.seq += 1), createdAt: new Date().toISOString(), ...row });
    this.rows.length = Math.min(this.rows.length, 500);
  }

  async list(limit) {
    return this.rows.slice(0, limit);
  }

  async close() {}
}

export class PgAuditStore {
  constructor(config, log = console) {
    this.config = config;
    this.schema = config.schema;
    this.table = `${config.schema}.splunk_audit`;
    this.log = log;
  }

  async init() {
    const { default: pg } = await import('pg');
    const config = this.config;
    const log = this.log;
    // Credentials go INTO the URL: pg lets every field parsed from connectionString override the
    // separate user/password options, so a URL without a password silently erased dbPassword.
    const url = new URL(config.dbUrl);
    if (config.dbUser) url.username = encodeURIComponent(config.dbUser);
    if (config.dbPassword) url.password = encodeURIComponent(config.dbPassword);
    this.pool = new pg.Pool({
      connectionString: url.href,
      max: 5,
      connectionTimeoutMillis: 5000,
    });
    // An idle client losing its connection emits here; unhandled, it would kill the process.
    this.pool.on('error', (e) => log.error('[splunk] audit pool error:', e.message));

    const client = await this.pool.connect();
    try {
      await client.query('begin');
      // Two broker instances starting together would otherwise both see an empty table and copy twice.
      await client.query("select pg_advisory_xact_lock(hashtext('splunk_broker.audit.init'))");
      await client.query(ddl(this.table, this.schema));
      const { rows: [state] } = await client.query(
        `select to_regclass('public.splunk_audit') is not null as legacy,
                not exists (select 1 from ${this.table}) as empty`);
      if (state.legacy && state.empty) {
        const cols = ['id', ...COLUMNS.map(([, c]) => c), 'created_at'].join(', ');
        const { rowCount } = await client.query(
          `insert into ${this.table} (${cols}) select ${cols} from public.splunk_audit order by id`);
        // Ids were copied verbatim, so the sequence has to be moved past them.
        await client.query(
          `select setval(pg_get_serial_sequence('${this.table}', 'id'), coalesce(max(id), 0) + 1, false) from ${this.table}`);
        if (rowCount) this.log.info(`[splunk] copied ${rowCount} rows from public.splunk_audit into ${this.table}`);
      }
      await client.query('commit');
    } catch (e) {
      await client.query('rollback').catch(() => {});
      throw e;
    } finally {
      client.release();
    }
  }

  async save(row) {
    const values = COLUMNS.map(([key]) => row[key] ?? null);
    const placeholders = values.map((_, i) => `$${i + 1}`).join(', ');
    await this.pool.query(
      `insert into ${this.table} (${COLUMNS.map(([, c]) => c).join(', ')}) values (${placeholders})`, values);
  }

  async list(limit) {
    const { rows } = await this.pool.query(
      `select id, ${COLUMNS.map(([key, c]) => `${c} as "${key}"`).join(', ')}, created_at as "createdAt"
         from ${this.table} order by id desc limit $1`, [limit]);
    // bigint comes back as a string from pg (it can exceed 2^53); every value here is far below that.
    const num = (v) => (v == null ? null : Number(v));
    return rows.map((r) => ({
      ...r,
      id: num(r.id),
      serverStep: num(r.serverStep),
      matchedStep: num(r.matchedStep),
      durationMs: num(r.durationMs),
      createdAt: r.createdAt.toISOString(),
    }));
  }

  async close() {
    await this.pool?.end();
  }
}

/**
 * MySQL 8 / MariaDB 10.5+. Same columns as the pg table; `created_at` is `datetime(3)` written by this
 * store in UTC (pool `timezone: 'Z'`), not a `timestamp` defaulted by the server — a `timestamp` is
 * converted through the session time zone on every read and write, so a server or connection in
 * Asia/Shanghai would shift every audit time by eight hours.
 */
export class MysqlAuditStore {
  constructor(config, log = console) {
    this.config = config;
    this.log = log;
  }

  async init() {
    const { default: mysql } = await import('mysql2/promise');
    const url = new URL(this.config.dbUrl);
    // Credentials go into the URL for the same reason as the pg store: one source, no override rules.
    if (this.config.dbUser) url.username = encodeURIComponent(this.config.dbUser);
    if (this.config.dbPassword) url.password = encodeURIComponent(this.config.dbPassword);
    this.pool = mysql.createPool({
      uri: url.href,
      connectionLimit: 5,
      connectTimeout: 5000,
      timezone: 'Z',
      // bigint columns as JS numbers: every value here (ids, TOTP steps, durations) is far below 2^53.
      supportBigNumbers: true,
      bigNumberStrings: false,
    });
    await this.pool.query(`
      create table if not exists splunk_audit (
          id                 bigint       not null auto_increment primary key,
          correlation_id     varchar(36)  not null,
          event_type         varchar(16)  not null,
          outcome            varchar(8)   not null,
          reason             varchar(32),
          detail             varchar(512),
          client_ip          varchar(64),
          forwarded_for      varchar(256),
          user_agent         varchar(256),
          server_step        bigint,
          matched_step       bigint,
          splunk_mode        varchar(8),
          http_status        int,
          cookie_fingerprint varchar(16),
          duration_ms        bigint,
          created_at         datetime(3)  not null,
          index ix_splunk_audit_created_at (created_at),
          index ix_splunk_audit_correlation (correlation_id)
      ) engine = InnoDB default charset = utf8mb4`);
  }

  async save(row) {
    const columns = [...COLUMNS.map(([, c]) => c), 'created_at'];
    const values = [...COLUMNS.map(([key]) => row[key] ?? null), new Date()];
    await this.pool.query(
      `insert into splunk_audit (${columns.join(', ')}) values (${columns.map(() => '?').join(', ')})`, values);
  }

  async list(limit) {
    const [rows] = await this.pool.query(
      `select id, ${COLUMNS.map(([key, c]) => `${c} as \`${key}\``).join(', ')}, created_at as createdAt
         from splunk_audit order by id desc limit ?`, [limit]);
    return rows.map((r) => ({ ...r, createdAt: r.createdAt.toISOString() }));
  }

  async close() {
    await this.pool?.end();
  }
}

export function createAuditStore(config, log) {
  switch (config.store) {
    case 'pg': return new PgAuditStore(config, log);
    case 'mysql': return new MysqlAuditStore(config, log);
    default: return new MemoryAuditStore();
  }
}
