import { ESLint } from 'eslint'
import { describe, expect, it } from 'vitest'

// Invariant F1 is a lint rule, and a lint rule that stops firing does so silently: `yarn lint`
// stays green either way. This is the paired negative, run on every test run rather than by hand
// after a linter major — which is the upgrade that dropped it last time.
const CALLS_FETCH = `export function Probe() {
  void fetch('/nope')
  return null
}
`

async function fetchBans(filePath: string): Promise<number> {
  const results = await new ESLint().lintText(CALLS_FETCH, { filePath })
  return results
    .flatMap((r) => r.messages)
    .filter((m) => m.ruleId === 'no-restricted-globals').length
}

describe('invariant F1 — components never call fetch directly', () => {
  it('rejects a direct fetch in a component', async () => {
    expect(await fetchBans('src/probe-f1.tsx')).toBe(1)
  })

  it('allows the same call inside src/api/, the one place that may reach the network', async () => {
    expect(await fetchBans('src/api/probe-f1.tsx')).toBe(0)
  })
})
