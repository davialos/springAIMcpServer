/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { fileURLToPath } from 'node:url';

// Where the dev server forwards the two back ends (the nginx of the Docker stack does the same in production).
const AUTH = process.env.AUTH_URL ?? 'http://localhost:8091';
const RULES = process.env.RULES_URL ?? 'http://localhost:8092';

const proxy = {
  '/auth': { target: AUTH, changeOrigin: true },
  '/api': { target: RULES, changeOrigin: true },
};

export default defineConfig({
  plugins: [react()],
  preview: { port: 4173, proxy },
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
