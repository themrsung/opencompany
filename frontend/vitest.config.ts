import { defineConfig } from 'vitest/config';
import { resolve } from 'node:path';

const root = import.meta.dirname;

export default defineConfig({
  resolve: {
    alias: {
      '@coreintra/business-time': resolve(root, 'packages/business-time/src/index.ts'),
      '@coreintra/api-client': resolve(root, 'packages/api-client/src/index.ts'),
      '@coreintra/ui': resolve(root, 'packages/ui/src/index.ts'),
      '@coreintra/mdv-editor': resolve(root, 'packages/mdv-editor/src/index.ts'),
    },
  },
  test: {
    globals: true,
    include: ['packages/*/test/**/*.test.ts', 'packages/*/test/**/*.test.tsx', 'apps/*/test/**/*.test.ts'],
  },
});
