# HiddenOre placeholders

HiddenOre registers a PlaceholderAPI expansion under the identifier `hiddenore`. It publishes three keys, and
they answer questions about the **server's configuration**, not about a player, a block or a vein. Any consumer
that can resolve a PlaceholderAPI string — a scoreboard plugin, a chat format, a hologram, a GUI item name, or
your own Java code — can read them.

| Placeholder                 | Answers                                              | Possible values          |
|-----------------------------|------------------------------------------------------|--------------------------|
| `%hiddenore_available%`     | HiddenOre has published a runtime configuration        | `true`, `false`          |
| `%hiddenore_seeded%`        | `veins.generation` is `seeded`                        | `true`, `false`, `---`   |
| `%hiddenore_drop-rules%`    | How many entries in `drops:` are live                 | a decimal count, or `---`|

That is the whole set. There are no vein keys, no provenance keys and no per-player keys — see
[Why there is nothing else](#why-there-is-nothing-else) at the end, which is a design constraint rather than an
omission.

---

## Requirements

PlaceholderAPI must be installed. HiddenOre soft-depends on it (`softdepend: [PlaceholderAPI]`) and works
normally without it; the expansion is simply never registered.

You do not need to depend on HiddenOre to read these. Placeholders are resolved by name at runtime through
PlaceholderAPI, so a consumer needs only PlaceholderAPI on its classpath. The Java sample below compiles
against `me.clip:placeholderapi` alone.

## Registration lifecycle, in order

1. **HiddenOre enables**, validates its configuration and publishes its runtime record.
2. **It attempts registration.** If PlaceholderAPI is enabled at that moment, the `hiddenore` expansion
   registers immediately.
3. **If PlaceholderAPI enables later**, HiddenOre notices — it listens for `PluginEnableEvent` at `MONITOR` —
   and registers then. HiddenOre loads at `STARTUP`, before worlds, so this is the ordinary case rather than the
   exception.
4. **Registration is idempotent.** A second attempt while an expansion is already registered does nothing.
5. **The expansion persists across `/papi reload`.** It is registered by HiddenOre in code, not loaded from
   PlaceholderAPI's expansion folder, so PlaceholderAPI does not discard it.
6. **On drain** — HiddenOre disabling, or a hot unload by a development tool — the expansion is unregistered.
   From that moment PlaceholderAPI no longer recognises the keys.

`/papi info hiddenore` lists the published keys, sorted: `available`, `drop-rules`, `seeded`. The expansion
reports author `Volmit Software`, version `1.0.0`, and required plugin `HiddenOre`.

## Threading

Resolution is safe on **any** thread PlaceholderAPI calls you on, including the async threads scoreboard and
hologram plugins commonly use. This is one of the few earned "any thread" claims in the HiddenOre API, and the
reason is narrow: each of the three resolvers reads a single volatile reference to an immutable record and then
either compares an enum constant or reads the size of an immutable list. No Bukkit call, no world access, no
chunk access, no lock, and no allocation for counts below 1024.

The `OfflinePlayer` PlaceholderAPI passes in is ignored by all three keys, so they resolve identically for
every player, for an offline player, and for no player at all — console parsing works.

Nothing here touches the region-owned APIs described in [service.md](service.md), which is exactly why these
three keys are the only ones that exist.

---

## Reading the values

### `%hiddenore_available%`

`true` when HiddenOre has a published runtime configuration. In practice it is `true` whenever the expansion is
being served at all, and `false` only in the window where HiddenOre has registered the expansion without a
valid configuration behind it.

**It is not an installation check.** When HiddenOre is absent, disabled, or drained, the expansion is not
registered, PlaceholderAPI does not recognise the key, and the literal text `%hiddenore_available%` is left in
the output. Testing for the string `false` will therefore never detect an absent HiddenOre — test for the
string `true`:

```java
public static boolean hiddenOreServing(Player player) {
    String available = PlaceholderAPI.setPlaceholders(player, "%hiddenore_available%");

    return "true".equals(available);
}
```

The same holds for every other key: a misspelled or unregistered key is echoed verbatim rather than replaced,
which keeps typos visible in a scoreboard instead of silently blanking a line.

### `%hiddenore_seeded%`

`true` when `veins.generation` is `seeded`, `false` when it is `pure_random`, and `---` if HiddenOre has not
published a configuration yet. This is the placeholder equivalent of `HiddenOreService.isSeeded()`, and it
carries the same consequence: in `pure_random` mode there are no pre-existing vein positions for anything to
find.

```java
public static boolean seededServer(Player player) {
    String seeded = PlaceholderAPI.setPlaceholders(player, "%hiddenore_seeded%");

    return "true".equals(seeded);
}
```

### `%hiddenore_drop-rules%`

The number of entries in the live `drops:` list — item rules and command rules together, after validation. The
shipped configuration answers `9`. It is a plain decimal string with no separators and no suffix, and `---` if
HiddenOre has not published a configuration yet.

Note the hyphen. PlaceholderAPI keys here are `[a-z0-9-]`; there is no `drop_rules` and no `dropRules`.

### The value vocabulary

| Value       | Meaning                                                                       |
|-------------|--------------------------------------------------------------------------------|
| `true`      | Exactly this string, lower case                                                 |
| `false`     | Exactly this string, lower case                                                 |
| `---`       | The answer is unavailable — HiddenOre is registered but has nothing to report, or the resolver failed |
| a number    | A decimal count, no thousands separators                                        |
| the literal `%hiddenore_…%` | PlaceholderAPI does not know this key: HiddenOre is not serving, or the key is misspelled |

Key lookup is case-insensitive — `%hiddenore_SEEDED%` resolves — because the parameter is lower-cased before
the lookup. The returned values are always lower case.

---

## Worked example: a configuration-driven scoreboard line

Any plugin that accepts PlaceholderAPI strings can use these directly. No Java required:

```yaml
scoreboard:
  title: "&6Mining"
  lines:
    - "&7Mode: &f%hiddenore_seeded%"
    - "&7Rules: &f%hiddenore_drop-rules%"
```

## Worked example: reading them from Java

```java
package com.example.quarryguard;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

public final class HiddenOreState {
    private HiddenOreState() {
    }

    public static boolean hiddenOreServing(Player player) {
        String available = PlaceholderAPI.setPlaceholders(player, "%hiddenore_available%");

        return "true".equals(available);
    }

    public static boolean seededServer(Player player) {
        String seeded = PlaceholderAPI.setPlaceholders(player, "%hiddenore_seeded%");

        return "true".equals(seeded);
    }
}
```

If you are writing Java and HiddenOre is on your compile classpath anyway, call
`HiddenOreService.isSeeded()` instead — it is a direct field read with no string parsing, no PlaceholderAPI
dependency, and no ambiguity between "false" and "not installed". Placeholders exist for the consumers that
cannot call Java at all.

---

## Failure policy

| Situation                                              | What happens                                                                      |
|--------------------------------------------------------|------------------------------------------------------------------------------------|
| PlaceholderAPI is not installed                        | No expansion is registered. Every `%hiddenore_…%` string is left verbatim           |
| PlaceholderAPI is installed but disabled               | The same. HiddenOre re-attempts registration if it is enabled later                 |
| PlaceholderAPI refuses the registration                | One warning naming the expansion. HiddenOre continues without placeholders          |
| Building the expansion throws                          | One severe log entry with the stack trace. HiddenOre continues without placeholders |
| A resolver throws                                      | The placeholder returns `---`. The failure is logged once per distinct key, up to 64 distinct keys per run, then silently |
| An unknown key under the `hiddenore` identifier        | Resolves to nothing, so PlaceholderAPI re-emits the literal text                     |
| An empty or blank parameter (`%hiddenore_%`)           | Resolves to nothing; the literal text stands                                        |
| Unregistering fails during drain                       | One warning. The reference is dropped regardless                                    |

No placeholder resolution can slow a tick, block a region thread, or throw into a consumer: the only exit paths
are a value, `---`, or "unknown key".

---

## Why there is nothing else

There are deliberately no vein, provenance or per-player placeholders, and there will not be:

- **The data is region-owned.** `originOf`, `provenanceOf`, `veinAt`, `veinSiblings` and `isVeinConsumed` all
  require the region thread that owns the block, and PlaceholderAPI never resolves on it. A placeholder that
  called them would throw or, worse, read across threads.
- **The cost is unbounded.** A nearby-vein query walks up to 289 chunks. That is not something a scoreboard
  should be able to trigger sixty times a second.
- **It would be an x-ray oracle.** Publishing vein positions to a scoreboard, a hologram or a chat format would
  turn an anti-x-ray plugin into the thing it exists to prevent.

Those answers stay behind the `HiddenOreService` API and the `/hiddenore` command, where the thread, the cost
and the permission are all under control.
