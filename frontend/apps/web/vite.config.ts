import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';

const workspace = resolve(import.meta.dirname, '../..');

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // Exact-match aliases, not the object form. The object form matches by
    // prefix, so `@coreintra/ui` would also capture `@coreintra/ui/tokens.css`
    // and rewrite it to `.../src/index.ts/tokens.css`. Subpaths must fall
    // through to the package's own `exports` map.
    alias: [
      { find: /^@coreintra\/business-time$/, replacement: resolve(workspace, 'packages/business-time/src/index.ts') },
      { find: /^@coreintra\/money$/, replacement: resolve(workspace, 'packages/money/src/index.ts') },
      { find: /^@coreintra\/i18n$/, replacement: resolve(workspace, 'packages/i18n/src/index.ts') },
      { find: /^@coreintra\/api-client$/, replacement: resolve(workspace, 'packages/api-client/src/index.ts') },
      { find: /^@coreintra\/ui$/, replacement: resolve(workspace, 'packages/ui/src/index.ts') },
      { find: /^@coreintra\/mdv-editor$/, replacement: resolve(workspace, 'packages/mdv-editor/src/index.ts') },
    ],
  },
  server: {
    proxy: {
      // Development only. In every deployment the reverse proxy serves the
      // built SPA and /api from the same origin, so the app never needs CORS
      // and the session cookie stays first-party.
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // An on-prem box may have no outbound internet, so nothing may resolve at
    // runtime: every font, icon and chunk ships in the bundle.
    assetsInlineLimit: 0,
  },
});
