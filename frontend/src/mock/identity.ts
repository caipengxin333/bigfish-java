import { IdentityError } from '../identity/editor.ts'
import type { IdentityPort, IdentityStatus, CheckScenario, CheckResult } from '../identity/editor.ts'
export const identityDemoKey = 'bigfish.mock.identity-metadata.v1'
type Store = Pick<Storage, 'getItem' | 'setItem'>
const empty = (generation: number): IdentityStatus => ({ generation, revision: 1, choice: 'visitor', agentPresent: false, manualIdPresent: false, retentionMinutes: 60, configured: false, updatedAt: null, expiresAt: null })
// This mock persists ONLY synthetic metadata so refresh/restart UX can be exercised.
// Cookie, User-Agent and manual ID are never saved, logged, returned or sent to fetch.
export function createIdentityDemo(storage: () => Store = () => localStorage, now = Date.now, delayMs = 150) {
  let writeFailure = false, readFailure = false, scenario: CheckScenario = 'connected'
  function read(): IdentityStatus {
    try {
      const raw = storage().getItem(identityDemoKey)
      if (!raw) return empty(now())
      const v = JSON.parse(raw)
      if (v.version !== 1 || !Number.isSafeInteger(v.generation) || !Number.isSafeInteger(v.revision) || !['visitor', 'cookie'].includes(v.choice) || ![30, 60, 240, 1440].includes(v.retentionMinutes) ||
        !['agentPresent', 'manualIdPresent', 'configured'].every(k => typeof v[k] === 'boolean') ||
        !['updatedAt', 'expiresAt'].every(k => v[k] === null || (Number.isSafeInteger(v[k]) && v[k] >= 0))) throw new Error()
      return { generation: v.generation, revision: v.revision, choice: v.choice, agentPresent: v.agentPresent, manualIdPresent: v.manualIdPresent, retentionMinutes: v.retentionMinutes,
        configured: v.configured && v.expiresAt !== null && v.expiresAt > now(), updatedAt: v.updatedAt, expiresAt: v.expiresAt }
    } catch { throw new IdentityError('READ_FAILED') }
  }
  function write(v: IdentityStatus) {
    try { storage().setItem(identityDemoKey, JSON.stringify({ version: 1, generation: v.generation, revision: v.revision, choice: v.choice,
      agentPresent: v.agentPresent, manualIdPresent: v.manualIdPresent, retentionMinutes: v.retentionMinutes, configured: v.configured, updatedAt: v.updatedAt, expiresAt: v.expiresAt })) }
    catch { throw new IdentityError('SAVE_FAILED') }
  }
  function wait(signal: AbortSignal, timeout = false): Promise<void> {
    return new Promise((resolve, reject) => {
      let timer: ReturnType<typeof setTimeout> | undefined
      const cancel = () => { clearTimeout(timer); signal.removeEventListener('abort', cancel); reject(new IdentityError('TIMEOUT')) }
      signal.addEventListener('abort', cancel, { once: true })
      if (signal.aborted) { cancel(); return }
      if (!timeout) timer = setTimeout(() => { signal.removeEventListener('abort', cancel); resolve() }, delayMs)
    })
  }
  function current(expected: IdentityStatus) {
    const value = read()
    if (value.generation !== expected.generation || value.revision !== expected.revision) throw new IdentityError('CONFLICT')
    return value
  }
  async function change(expected: IdentityStatus, signal: AbortSignal, update: (v: IdentityStatus) => void) {
    await wait(signal); signal.throwIfAborted()
    if (writeFailure) throw new IdentityError('SAVE_FAILED')
    const value = current(expected); update(value); value.revision++; write(value); return value
  }
  const port: IdentityPort = {
    async read(signal) { await wait(signal); if (readFailure) throw new IdentityError('READ_FAILED'); const value = read(); write(value); return value },
    options: (value, expected, signal) => change(expected, signal, v => {
      v.choice = value.choice; v.agentPresent ||= !!value.agent; v.manualIdPresent = value.manual && (!!value.visitorId || v.manualIdPresent); v.retentionMinutes = value.retentionMinutes
    }),
    // Do not retain even fake secrets in the mock store. The UI validates non-empty input first.
    replace: (_secret, expected, signal) => change(expected, signal, v => { v.configured = true; v.updatedAt = now(); v.expiresAt = now() + v.retentionMinutes * 60_000 }),
    clear: (expected, signal) => change(expected, signal, v => { v.configured = false; v.updatedAt = now(); v.expiresAt = null }),
    async check(expected, _room, signal) {
      const selected = scenario
      await wait(signal, selected === 'timeout'); const value = current(expected)
      if (value.choice === 'cookie' && !value.configured) throw new IdentityError('NOT_CONFIGURED')
      if (selected === 'id-required' && !value.manualIdPresent) throw new IdentityError('ID_REQUIRED')
      if (selected === 'failed') throw new IdentityError('CHECK_FAILED')
      const kind = selected === 'comments' || selected === 'gifts' ? selected : 'connected'
      return { kind, comments: kind === 'comments' ? 2 : 0, gifts: kind === 'gifts' ? 1 : 0 } as CheckResult
    },
  }
  return { port, setWriteFailure(value: boolean) { writeFailure = value }, setReadFailure(value: boolean) { readFailure = value }, setScenario(value: CheckScenario) { scenario = value },
    restart() { write(empty(read().generation + 1)) } }
}
