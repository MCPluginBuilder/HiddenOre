import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

export function assertRestart(before, after) {
  if (after.boot === before.boot) throw new Error('Verification requires a real server restart')
  if (after.world !== before.world || JSON.stringify(after.points) !== JSON.stringify(before.points)) throw new Error('World and target coordinates must persist')
  if (!after.consumed || !after.placed || after.operator || after.actor !== before.actor) throw new Error('Ordinary actor and both persisted markers are required')
}

export default {
  name: 'hiddenore-persistence-overflow',
  description: 'Run twice across a real server stop/start: preserve seeded consumption and player provenance, then verify inventory overflow recovery.',
  async run(context) {
    const evidence = context.report.hiddenore = { requestedPhase: 'prepare', phase: 'running' }
    const progressPath = path.join(context.server.directory, 'plugins/HiddenOreGameplayFixture/scenario.json')
    let saved
    try {
      const content = await readFile(progressPath, 'utf8')
      evidence.requestedPhase = 'verify'
      saved = JSON.parse(content)
    } catch (error) {
      if (error.code !== 'ENOENT') { evidence.requestedPhase = 'verify'; throw error }
    }
    const fixture = await readFile(new URL('./seeded.toml', import.meta.url), 'utf8')
    context.expect(await readFile(path.join(context.server.directory, 'plugins/HiddenOre/hiddenore.toml'), 'utf8') === fixture, 'Install the seeded deterministic fixture before startup')
    context.expect(!saved || saved.phase === 'prepared', 'Persistence scenario already verified; use a fresh isolated instance')
    const actor = await context.connectActor(`HQApersist${context.server.port}`)
    evidence.actor = actor.bot.username
    async function qa(command) {
      const prefix = `HIDDENORE_QA ${command.split(' ')[0]} `
      const message = await context.command(`/hiddenoreqa ${command}`, /^HIDDENORE_QA /, 5000)
      context.expect(message.startsWith(prefix), 'Fixture command accepted', message)
      return JSON.parse(message.slice(prefix.length))
    }
    async function equip(name) {
      await context.waitUntil(() => actor.bot.inventory.items().some(item => item.name === name), { label: name })
      await actor.bot.equip(actor.bot.inventory.items().find(item => item.name === name), 'hand')
    }
    async function target(index) {
      const state = await qa(`target ${index}`)
      const point = state.points[index]
      const position = actor.bot.entity.position.clone().set(point.x, 100, point.z)
      await context.waitUntil(() => actor.bot.entity.position.distanceTo(position) < 4 && actor.bot.blockAt(position), { label: 'fixture target arrival' })
      return position
    }
    async function dig(index) {
      const position = await target(index)
      await equip('iron_pickaxe')
      await context.waitUntil(() => actor.bot.blockAt(position)?.name === 'stone', { label: 'managed stone target' })
      await actor.bot.dig(actor.bot.blockAt(position))
    }
    if (!saved) {
      await context.step('prepare consumed seeded vein and real player placement', async () => {
        await qa(`setup ${actor.bot.username}`)
        await dig(0)
        await context.waitUntil(async () => { const state = await qa('snapshot'); return state.consumed && state.diamonds === 1 }, { label: 'accepted seeded reward and consumption' })
        await qa('restore')
        const position = await target(1)
        await equip('stone')
        await actor.bot.placeBlock(actor.bot.blockAt(position.offset(0, -1, 0)), position.clone().set(0, 1, 0))
        const state = await context.waitUntil(async () => { const value = await qa('snapshot'); return value.placed && value.consumed ? value : false }, { label: 'persistable provenance and consumption' })
        evidence.phase = 'prepared'
        evidence.beforeRestart = state
        await writeFile(progressPath, JSON.stringify({ phase: 'prepared', before: state }))
      })
      return
    }
    await context.step('verify real restart and persisted plugin state', async () => {
      const state = await qa('snapshot')
      assertRestart(saved.before, state)
      evidence.beforeRestart = saved.before
      evidence.afterRestart = state
      for (const index of [0, 1]) {
        const before = await qa('snapshot')
        await dig(index)
        const after = await context.waitUntil(async () => { const value = await qa('snapshot'); return value.cobblestone === before.cobblestone + 1 ? value : false }, { label: 'ordinary base drop on denied remine' })
        context.expect(after.diamonds === before.diamonds, 'Persisted suppression must deny duplicate hidden rewards')
      }
    })
    await context.step('preserve overflow diamond and recover it through player movement', async () => {
      await qa('overflow')
      await dig(2)
      const overflow = await context.waitUntil(async () => { const state = await qa('snapshot'); return state.groundDiamonds === 1 && state.diamonds === 0 ? state : false }, { label: 'full inventory reward dropped on ground' })
      evidence.overflow = overflow
      await qa('space')
      actor.bot.setControlState('forward', true)
      try {
        evidence.recovered = await context.waitUntil(async () => { const state = await qa('snapshot'); return state.diamonds === 1 && state.groundDiamonds === 0 ? state : false }, { label: 'overflow diamond picked up by ordinary player', timeoutMs: 8000 })
      } finally { actor.bot.clearControlStates() }
    })
    evidence.phase = 'verified'
    await writeFile(progressPath, JSON.stringify({ phase: 'verified', before: saved.before, after: evidence.afterRestart }))
  }
}
