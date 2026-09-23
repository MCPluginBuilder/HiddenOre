import test from 'node:test'
import assert from 'node:assert/strict'
import { assertRestart } from './persistence-overflow.mjs'

test('restart evidence rejects same boot, changed world/actor, and missing persisted markers', () => {
  const before = { boot: 'first', world: 'same', actor: 'HQAplayer', points: [{ x: 2, z: 2 }], consumed: true, placed: true, operator: false }
  const after = { ...before, boot: 'second' }
  assert.doesNotThrow(() => assertRestart(before, after))
  for (const patch of [{ boot: 'first' }, { world: 'other' }, { actor: 'other' }, { points: [] }, { consumed: false }, { placed: false }, { operator: true }]) assert.throws(() => assertRestart(before, { ...after, ...patch }))
})

const { default: scenario } = await import('./persistence-overflow.mjs')
const { mkdtemp, mkdir, writeFile, rm } = await import('node:fs/promises')
const { tmpdir } = await import('node:os')
const path = await import('node:path')
test('failed verification retains its requested phase before setup validation', async () => {
  const directory = await mkdtemp(path.join(tmpdir(), 'hiddenore-contract-'))
  try {
    await mkdir(path.join(directory, 'plugins/HiddenOreGameplayFixture'), { recursive: true })
    await mkdir(path.join(directory, 'plugins/HiddenOre'), { recursive: true })
    await writeFile(path.join(directory, 'plugins/HiddenOreGameplayFixture/scenario.json'), JSON.stringify({ phase: 'prepared' }))
    await writeFile(path.join(directory, 'plugins/HiddenOre/hiddenore.toml'), 'invalid fixture')
    const context = { server: { directory }, report: {}, expect: (condition, message) => { if (!condition) throw new Error(message) } }
    await assert.rejects(scenario.run(context), /Install the seeded/)
    assert.equal(context.report.hiddenore.requestedPhase, 'verify')
    assert.equal(context.report.hiddenore.phase, 'running')
  } finally { await rm(directory, { recursive: true, force: true }) }
})
