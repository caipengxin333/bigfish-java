// Compile the unmodified, pinned official Framework separately from strict app code.
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('..', import.meta.url))
execFileSync(process.execPath, [fileURLToPath(new URL('../node_modules/typescript/bin/tsc', import.meta.url)), '-p', 'vendor/cubism-framework/tsconfig.build.json'], { cwd: root, stdio: 'inherit' })
