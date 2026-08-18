import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

const root = import.meta.dirname;

export default defineConfig({
  resolve: {
    alias: {
      '@coreintra/business-time': resolve(root, 'packages/business-time/src/index.ts'),
      '@coreintra/money': resolve(root, 'packages/money/src/index.ts'),
      '@coreintra/i18n': resolve(root, 'packages/i18n/src/index.ts'),
      '@coreintra/api-client': resolve(root, 'packages/api-client/src/index.ts'),
      '@coreintra/ui': resolve(root, 'packages/ui/src/index.ts'),
      '@coreintra/mdv-editor': resolve(root, 'packages/mdv-editor/src/index.ts'),
    },
  },
  test: {
    globals: true,
    // jsdom everywhere rather than per-file docblocks: the component tests need
    // a DOM and the pure ones do not care, so one setting beats a convention
    // that a new test file can forget.
    environment: 'jsdom',
    include: [
      'packages/*/test/**/*.test.ts',
      'packages/*/test/**/*.test.tsx',
      'apps/*/test/**/*.test.ts',
      'apps/*/test/**/*.test.tsx',
    ],
  },
});
