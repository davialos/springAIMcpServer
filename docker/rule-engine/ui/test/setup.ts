import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(() => {
  cleanup();
  // node-environment tests (the codec) have no window
  if (typeof window !== 'undefined') {
    window.sessionStorage.clear();
  }
});
