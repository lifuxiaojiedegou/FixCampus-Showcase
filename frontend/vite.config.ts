import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { Plugin, ResolvedConfig } from 'vite';

const publicFiles = [
  'manifest.webmanifest',
  'icons/icon-192.svg',
  'icons/icon-512.svg',
];

function pwa(): Plugin {
  let config: ResolvedConfig;
  return {
    name: 'fixcampus-pwa',
    apply: 'build',
    configResolved(resolved) {
      config = resolved;
    },
    transformIndexHtml(html) {
      return {
        html,
        tags: [
          {
            tag: 'meta',
            attrs: { name: 'theme-color', content: '#1559e8' },
          },
          {
            tag: 'link',
            attrs: {
              rel: 'manifest',
              href: config.base + 'manifest.webmanifest',
            },
          },
          {
            tag: 'link',
            attrs: {
              rel: 'icon',
              href: config.base + 'icons/icon-192.svg',
              type: 'image/svg+xml',
            },
          },
        ],
      };
    },
    generateBundle: {
      order: 'post',
      handler(_, bundle) {
        const worker = readFileSync(join(config.root, 'pwa/worker.js'), 'utf8');
        const shell = bundle['index.html'];
        if (config.base !== '/app/' || !shell || shell.type !== 'asset') {
          this.error('Expected the static FixCampus shell under /app/');
        }
        const entries = Object.values(bundle).filter((entry) =>
          entry.fileName.startsWith('assets/'),
        );
        const files = [
          ...[shell, ...entries].map((entry) => ({
            path: entry.fileName,
            content: entry.type === 'chunk' ? entry.code : entry.source,
          })),
          ...publicFiles.map((path) => ({
            path,
            content: readFileSync(join(config.publicDir, path)),
          })),
        ]
          .map(({ path, content }) => ({
            path,
            integrity:
              'sha256-' + createHash('sha256').update(content).digest('base64'),
          }))
          .sort((a, b) => a.path.localeCompare(b.path));
        const version = createHash('sha256')
          .update(worker)
          .update(JSON.stringify(files))
          .digest('hex');
        this.emitFile({
          type: 'asset',
          fileName: 'service-worker.js',
          source: `const shellBuild = ${JSON.stringify({ version, files })};\n${worker}`,
        });
      },
    },
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [react(), pwa()],
  base: '/app/',
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    ...(mode === 'web-slice' || mode === 'demo'
      ? {
          proxy: {
            '/api/v1': {
              target: 'http://127.0.0.1:18080',
              changeOrigin: false,
            },
          },
        }
      : {}),
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true },
}));
