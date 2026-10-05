/**
 * Writes text to the clipboard, also on plain HTTP.
 *
 * `navigator.clipboard` only exists in a secure context, and this app is usually reached over plain
 * HTTP (the BFF on :3002, `vite --host` from another machine), where it is `undefined`. The hidden
 * textarea + `execCommand('copy')` path is deprecated but is still the only thing that works there,
 * and it must run inside the click handler's user activation — so callers invoke this directly from
 * the click, never after an `await`.
 */
export async function copyText(text: string): Promise<void> {
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  // Off-screen rather than display:none — a hidden element cannot hold a selection.
  textarea.style.position = 'fixed';
  textarea.style.top = '-9999px';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  const previousFocus = document.activeElement as HTMLElement | null;
  textarea.select();
  try {
    if (!document.execCommand('copy')) throw new Error('copy command was rejected');
  } finally {
    document.body.removeChild(textarea);
    previousFocus?.focus?.();
  }
}

/**
 * Tab-separated rows with a header line — what Excel, Sheets and Confluence tables paste as cells.
 *
 * A cell containing a tab, newline or double quote is quoted with inner quotes doubled (Excel's own
 * rule), otherwise a note with a line break would spill into the next row on paste.
 */
export function toTsv(header: string[], rows: (string | number | null | undefined)[][]): string {
  const cell = (value: string | number | null | undefined) => {
    const text = value == null ? '' : String(value);
    return /[\t\r\n"]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  return [header, ...rows].map((row) => row.map(cell).join('\t')).join('\n');
}
