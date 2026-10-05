// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/** The one file that owns the security headers (nginx includes it; vite preview serves the same values). */
const conf = readFileSync(new URL('../security-headers.conf', import.meta.url), 'utf8');
const nginx = readFileSync(new URL('../nginx.conf.template', import.meta.url), 'utf8');

function header(name: string): string {
  const m = new RegExp(`^add_header\\s+${name}\\s+"([^"]+)"`, 'm').exec(conf);
  if (!m) throw new Error(`${name} missing from security-headers.conf`);
  return m[1] as string;
}

describe('security headers', () => {
  const csp = header('Content-Security-Policy');

  it('allows scripts and styles from the same origin only (protobuf codec is static: no eval, no inline)', () => {
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("style-src 'self'");
    expect(csp).not.toMatch(/unsafe-eval|unsafe-inline|wasm-unsafe-eval/);
    expect(csp).toContain("default-src 'self'");
  });

  it('forbids framing, plugins and foreign form targets', () => {
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    expect(csp).toContain("form-action 'self'");
    expect(csp).toContain("connect-src 'self'");
  });

  it('sets the companion headers', () => {
    expect(header('X-Content-Type-Options')).toBe('nosniff');
    expect(header('X-Frame-Options')).toBe('DENY');
    expect(header('Referrer-Policy')).toBeTruthy();
  });

  it('is re-included in every nginx location that sets its own headers (add_header does not inherit)', () => {
    const blocks = nginx.split(/\n\s*location /).slice(1);
    for (const block of blocks) {
      if (/add_header|proxy_pass|try_files/.test(block)) {
        expect(block, block.split('{')[0]).toContain('include /etc/nginx/security-headers.conf');
      }
    }
  });
});
