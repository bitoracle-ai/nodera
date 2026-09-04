import { ESLint } from 'eslint'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Invariant F1 is enforced by a lint rule, and a lint rule that stops firing does so silently:
// `eslint .` stays green either way. So the rule is put on trial here, in the step that already
// loads ESLint, before that step is trusted.
const frontendRoot = dirname(fileURLToPath(import.meta.url))

const CALLS_FETCH = `export function Probe() {
  void fetch('/nope')
  return null
}
`

const CASES = [
  { filePath: 'src/probe-f1.tsx', expected: 1 },
  { filePath: 'src/api/probe-f1.tsx', expected: 0 },
]

const eslint = new ESLint({ cwd: frontendRoot })

let failed = false
for (const { filePath, expected } of CASES) {
  const results = await eslint.lintText(CALLS_FETCH, { filePath: resolve(frontendRoot, filePath) })
  const found = results.flatMap((r) => r.messages).filter((m) => m.ruleId === 'no-restricted-globals').length
  if (found !== expected) {
    failed = true
    console.error(`${filePath}: expected ${expected} no-restricted-globals finding(s), got ${found}.`)
  }
}

if (failed) {
  console.error('Invariant F1 is not enforced the way eslint.config.js claims it is.')
  // Not process.exit(): on a runner it can discard the pending stderr write, and a red gate
  // that does not say why is the failure this probe exists to prevent.
  process.exitCode = 1
} else {
  console.log('OK - invariant F1 fires on a component and stays silent in src/api/.')
}
