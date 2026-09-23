import { readFile } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import path from 'node:path'

export default {
  name: 'hiddenore-mining-load',
  description: 'Four ordinary miners verify guaranteed hidden rewards, tool rejection, and placed-block suppression under concurrent load.',
  async run(context) {
    const fixture = await readFile(new URL('./hiddenore.toml', import.meta.url), 'utf8')
    context.expect(await readFile(path.join(context.server.directory, 'plugins/HiddenOre/hiddenore.toml'), 'utf8') === fixture, 'Install the exact deterministic mining fixture before starting this isolated server')
    const command = text => context.command(text, /filled|no blocks|teleported|game mode|gave|removed|nothing changed|set the block|changed the block|updated/i, 10_000)
    const actors = []
    const suffix = randomBytes(3).toString('hex')
    const evidence = context.report.hiddenore = { configuration: 'pure_random chance=1 at Y100; iron pickaxe only; player placement denied', actors: [], naturalBreaks: 0, placedBreaks: 0, rejectedTools: 0, rounds: 3 }
    const count = (bot, name) => bot.inventory.items().filter(item => item.name === name).reduce((total, item) => total + item.count, 0)
    const equip = async (bot, name) => { await context.waitUntil(() => bot.inventory.items().some(item => item.name === name), { label: name }); await bot.equip(bot.inventory.items().find(item => item.name === name), 'hand') }
    const at = (bot, x, y, z) => bot.entity.position.clone().set(x, y, z)
    async function dig(actor, x, z) {
      await context.waitUntil(() => actor.bot.blockAt(at(actor.bot, x, 100, z))?.name === 'stone', { label: 'stone target' })
      await actor.bot.dig(actor.bot.blockAt(at(actor.bot, x, 100, z)))
      await context.waitUntil(() => actor.bot.blockAt(at(actor.bot, x, 100, z))?.name === 'air', { label: 'accepted mining' })
    }
    await context.step('prepare four ordinary survival miners', async () => {
      if (context.bot.game.gameMode !== 'spectator') await command(`/gamemode spectator ${context.bot.username}`)
      await command(`/tp ${context.bot.username} 12 105 0`)
      await context.waitUntil(() => context.bot.blockAt(at(context.bot, -4, 99, -4)) && context.bot.blockAt(at(context.bot, 30, 99, 4)), { label: 'arena chunks' })
      await command('/fill -4 99 -4 30 99 4 stone')
      await command('/fill -4 100 -4 30 104 4 air')
      for (let index = 0; index < 4; index++) {
        const actor = await context.connectActor(`HQA${index}${suffix}`)
        actors.push(actor)
        evidence.actors.push(actor.bot.username)
        if (actor.bot.game.gameMode !== 'survival') await command(`/gamemode survival ${actor.bot.username}`)
        await command(`/tp ${actor.bot.username} ${index * 8 + .5} 100 .5`)
        for (const item of ['iron_pickaxe', 'wooden_pickaxe', 'stone 16']) await command(`/give ${actor.bot.username} ${item}`)
        await equip(actor.bot, 'iron_pickaxe')
      }
    })
    for (let round = 0; round < evidence.rounds; round++) {
      await context.step(`concurrent natural mining round ${round + 1}`, async () => {
        for (let index = 0; index < actors.length; index++) await command(`/fill ${index * 8 + 2} 100 -1 ${index * 8 + 2} 100 2 stone`)
        await Promise.all(actors.map(async (actor, index) => {
          const before = count(actor.bot, 'diamond')
          await equip(actor.bot, 'iron_pickaxe')
          for (let z = -1; z <= 2; z++) await dig(actor, index * 8 + 2, z)
          await context.waitUntil(() => count(actor.bot, 'diamond') === before + 4, { label: 'exactly four hidden rewards' })
          evidence.naturalBreaks += 4
        }))
      })
      await context.step(`concurrent placed-block suppression round ${round + 1}`, async () => {
        await Promise.all(actors.map(async (actor, index) => {
          const before = count(actor.bot, 'diamond')
          const cobble = count(actor.bot, 'cobblestone')
          for (let z = -1; z <= 2; z++) {
            await equip(actor.bot, 'stone')
            await actor.bot.placeBlock(actor.bot.blockAt(at(actor.bot, index * 8 + 2, 99, z)), at(actor.bot, 0, 1, 0))
            await equip(actor.bot, 'iron_pickaxe')
            await dig(actor, index * 8 + 2, z)
          }
          await context.waitUntil(() => count(actor.bot, 'cobblestone') === cobble + 4, { label: 'four ordinary placed-block drops' })
          context.expect(count(actor.bot, 'diamond') === before, 'Placed stone must never grant hidden rewards')
          evidence.placedBreaks += 4
        }))
      })
    }
    await context.step('reject insufficient tool tiers for every miner', async () => {
      for (let index = 0; index < actors.length; index++) await command(`/setblock ${index * 8 + 3} 100 0 stone`)
      await Promise.all(actors.map(async (actor, index) => {
        const diamond = count(actor.bot, 'diamond')
        const cobble = count(actor.bot, 'cobblestone')
        await equip(actor.bot, 'wooden_pickaxe')
        await dig(actor, index * 8 + 3, 0)
        await context.waitUntil(() => count(actor.bot, 'cobblestone') === cobble + 1, { label: 'base drop for rejected tool' })
        context.expect(count(actor.bot, 'diamond') === diamond, 'Wrong tool must not receive diamond')
        evidence.rejectedTools++
      }))
    })
    context.expect(evidence.naturalBreaks === 48 && evidence.placedBreaks === 48 && evidence.rejectedTools === 4, 'All 100 real mining operations completed')
  }
}
