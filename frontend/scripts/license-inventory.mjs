import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const manifest = JSON.parse(readFileSync('package.json', 'utf8'));
const lock = JSON.parse(readFileSync('package-lock.json', 'utf8'));
const direct = { ...manifest.dependencies, ...manifest.devDependencies };
const packages = Object.entries(lock.packages)
  .filter(([path]) => path)
  .map(([path, pkg]) => {
    const local = join(path, 'package.json');
    const installed = existsSync(local)
      ? JSON.parse(readFileSync(local, 'utf8'))
      : null;
    return {
      path,
      name: installed?.name ?? path.split('node_modules/').at(-1),
      version: pkg.version,
      license: installed?.license ?? pkg.license ?? 'UNKNOWN',
      resolved: pkg.resolved,
      integrity: pkg.integrity,
      direct: Object.hasOwn(
        direct,
        installed?.name ?? path.replace('node_modules/', ''),
      ),
      installed: !!installed,
      hasInstallScript: pkg.hasInstallScript ?? false,
      optional: pkg.optional ?? false,
    };
  });
console.log(
  JSON.stringify(
    {
      note: 'Inventory only; not binary distribution approval. Optional platform packages may be uninstalled. Review license texts and notices for future distribution.',
      packages,
    },
    null,
    2,
  ),
);
process.exitCode = packages.some(
  (p) =>
    p.license === 'UNKNOWN' ||
    (p.resolved && !p.resolved.startsWith('https://registry.npmjs.org/')),
)
  ? 1
  : 0;
