import type { ConfluenceAuth, JiraAuth } from '../../api/veracode';

/**
 * The user's Jira and Confluence sign-ins as a file of their own (asked for, 2026-11-16: browser data gets cleared,
 * the tokens should come back from a file). Written and read in the browser only - the backend never sees the file.
 *
 * With a password the content is AES-GCM encrypted under a PBKDF2-SHA256 key, so a copied file is useless without it;
 * without one it is plain JSON and the dialog says so. WebCrypto needs a secure context (https, or localhost): over
 * plain http the password is unavailable and an encrypted file cannot be opened there.
 */
export const TOKEN_FILE_TYPE = 'creed-veracode-tokens';

export interface TokenFileContent {
  jira: { auth: JiraAuth; remember: boolean } | null;
  confluence: { auth: ConfluenceAuth; remember: boolean } | null;
}

interface Encrypted {
  alg: 'AES-GCM';
  kdf: 'PBKDF2-SHA256';
  iterations: number;
  salt: string;
  iv: string;
  data: string;
}

interface TokenFile {
  type: typeof TOKEN_FILE_TYPE;
  version: 1;
  exportedAt: string;
  /** Plain files carry the content here... */
  content?: TokenFileContent;
  /** ...encrypted ones here. */
  encrypted?: Encrypted;
}

/** OWASP's 2023 figure for PBKDF2-HMAC-SHA256. */
const ITERATIONS = 600_000;

export const canEncrypt = () => typeof window !== 'undefined' && window.isSecureContext && !!window.crypto?.subtle;

export class TokenFileError extends Error {
  constructor(readonly reason: 'notTokenFile' | 'unsupported' | 'passwordNeeded' | 'wrongPassword' | 'insecureContext') {
    super(reason);
  }
}

const toBase64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
/** Over its own ArrayBuffer: WebCrypto's BufferSource refuses a view that might sit on a SharedArrayBuffer. */
const fromBase64 = (text: string): Uint8Array<ArrayBuffer> => {
  const binary = atob(text);
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
};

const deriveKey = async (password: string, salt: Uint8Array<ArrayBuffer>, iterations: number) => {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations, hash: 'SHA-256' }, base,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
};

/** The file's text; `password` blank = plain. */
export async function writeTokenFile(content: TokenFileContent, password: string, now = new Date()): Promise<string> {
  const file: TokenFile = { type: TOKEN_FILE_TYPE, version: 1, exportedAt: now.toISOString() };
  if (password) {
    if (!canEncrypt()) throw new TokenFileError('insecureContext');
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await deriveKey(password, salt, ITERATIONS);
    const data = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key,
      new TextEncoder().encode(JSON.stringify(content))));
    file.encrypted = { alg: 'AES-GCM', kdf: 'PBKDF2-SHA256', iterations: ITERATIONS, salt: toBase64(salt), iv: toBase64(iv), data: toBase64(data) };
  } else {
    file.content = content;
  }
  return JSON.stringify(file, null, 2);
}

/** Whether the file needs a password - read before asking for one. */
export function inspectTokenFile(text: string): { encrypted: boolean; exportedAt: string } {
  const file = parseFile(text);
  return { encrypted: !!file.encrypted, exportedAt: file.exportedAt };
}

export async function readTokenFile(text: string, password: string): Promise<TokenFileContent> {
  const file = parseFile(text);
  if (!file.encrypted) return clean(file.content);
  if (!password) throw new TokenFileError('passwordNeeded');
  if (!canEncrypt()) throw new TokenFileError('insecureContext');
  const { salt, iv, data, iterations } = file.encrypted;
  let plain: ArrayBuffer;
  try {
    const key = await deriveKey(password, fromBase64(salt), iterations);
    plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromBase64(iv) }, key, fromBase64(data));
  } catch {
    // GCM's tag check: a wrong password and a changed file look the same
    throw new TokenFileError('wrongPassword');
  }
  return clean(JSON.parse(new TextDecoder().decode(plain)) as TokenFileContent);
}

function parseFile(text: string): TokenFile {
  let file: Partial<TokenFile>;
  try {
    file = JSON.parse(text) as Partial<TokenFile>;
  } catch {
    throw new TokenFileError('notTokenFile');
  }
  if (!file || file.type !== TOKEN_FILE_TYPE) throw new TokenFileError('notTokenFile');
  if (file.version !== 1) throw new TokenFileError('unsupported');
  if (file.encrypted && (file.encrypted.alg !== 'AES-GCM' || file.encrypted.kdf !== 'PBKDF2-SHA256')) {
    throw new TokenFileError('unsupported');
  }
  return file as TokenFile;
}

/** Only the fields the page keeps, as strings - a hand-edited file cannot slip anything else into storage. */
function clean(content: TokenFileContent | undefined): TokenFileContent {
  const str = (v: unknown) => (typeof v === 'string' ? v : '');
  const deployment = (v: unknown) => (v === 'cloud' ? 'cloud' as const : 'dataCenter' as const);
  const jira = content?.jira;
  const confluence = content?.confluence;
  return {
    jira: jira && str(jira.auth?.token) ? {
      auth: { deployment: deployment(jira.auth.deployment), token: str(jira.auth.token), email: str(jira.auth.email), issueType: str(jira.auth.issueType) },
      remember: jira.remember === true,
    } : null,
    confluence: confluence && str(confluence.auth?.token) ? {
      auth: { deployment: deployment(confluence.auth.deployment), token: str(confluence.auth.token), email: str(confluence.auth.email) },
      remember: confluence.remember === true,
    } : null,
  };
}
