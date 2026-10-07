/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Where the dev server forwards the two back ends (the nginx of the Docker stack does the same in production).
const AUTH = process.env.AUTH_URL ?? 'http://localhost:8091';
const RULES = process.env.RULES_URL ?? 'http://localhost:8092';

/** The security headers nginx serves, read from the one file that owns them (security-headers.conf). */
function securityHeaders(): Record<string, string> {
  const text = readFileSync(new URL('./security-headers.conf', import.meta.url), 'utf8');
  const headers: Record<string, string> = {};
  for (const m of text.matchAll(/^add_header\s+(\S+)\s+"([^"]+)"/gm)) {
    headers[m[1] as string] = m[2] as string;
  }
  return headers;
}

const proxy = {
  '/auth': { target: AUTH, changeOrigin: true },
  '/api': { target: RULES, changeOrigin: true },
};

export default defineConfig({
  plugins: [react()],
  // the dev server needs inline scripts for hot reload, so the strict policy applies to the production-like preview only
  preview: { port: 4173, proxy, headers: securityHeaders() },
  server: {
    port: 5173,
    proxy,
    // the protobuf contract lives next to the Java code and is imported from there (one owner)
    fs: { allow: [fileURLToPath(new URL('..', import.meta.url))] },
  },
  build: { sourcemap: true, target: 'es2022' },
  test: {
    environment: 'jsdom',
    setupFiles: ['./test/setup.ts'],
    include: ['test/**/*.test.{ts,tsx}'],
    globals: true,
    css: false,
  },
});
