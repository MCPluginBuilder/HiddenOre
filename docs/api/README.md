# HiddenOre API

HiddenOre replaces visible ore distribution with rewards hidden behind ordinary blocks. Its API lets another
plugin **ask what a block is** (was it placed by a player, or has it simply never been recorded?), **find the
hidden veins** a seeded world holds, **refuse** a reward before it is computed, and **edit** the reward before
it is delivered.

The six contract types listed at the end of this page expose Bukkit types, `java.*` types and their own types
only — no VolmLib, no Adventure, no shaded types — so they link against a plain Spigot or Paper compile
classpath. That guarantee covers those six types, not the whole of `art.arcane.hiddenore.api`: the
implementation class that lives beside them does reference VolmLib, which is why you hold the interface and not
the implementation. The `HiddenOreDropsEvent` accessors carry JetBrains nullability annotations; those are
class-retained and need no dependency of their own.

| You want to…                                                     | Read                                    |
|------------------------------------------------------------------|-----------------------------------------|
| ask where a block came from, or where the veins are               | [service.md](service.md)                |
| refuse a reward, or add to one                                    | [events.md](events.md)                  |
| show HiddenOre state on a scoreboard or in chat                   | [placeholders.md](placeholders.md)      |

Two entry points exist and they are not interchangeable:

- **`HiddenOreService`** (ServicesManager) answers questions. It never changes anything, and every question
  about a block is answered only on the thread that owns that block's region.
- **`HiddenOreBreakEvent` / `HiddenOreDropsEvent`** are pushed to you during a break. One is cancellable and is
  where you refuse; the other is not cancellable and is where you edit.

---

## Depending on HiddenOre

Bukkit plugin (`plugin.yml`):

```yaml
softdepend: [HiddenOre]
```

Paper plugin (`paper-plugin.yml`):

```yaml
dependencies:
  server:
    HiddenOre:
      load: BEFORE
      required: false
      join-classpath: true
```

`join-classpath: true` is mandatory on Paper — plugin classloaders are isolated, and without it you get
`NoClassDefFoundError` on `art.arcane.hiddenore.api.*` even though the classes ship unrelocated. HiddenOre
relocates only `io.github.slimjar`, `org.bstats` and `net.kyori`; nothing under `art.arcane.hiddenore` is
relocated, so the class names you compile against are the class names present at runtime.

HiddenOre publishes no Maven artifact. Compile against the jar:

```groovy
dependencies {
    compileOnly files("libs/HiddenOre.jar")
}
```

Two jars come out of a HiddenOre build. The unclassified `HiddenOre-<version>.jar` is the thin compile-facing
artifact — HiddenOre's own classes and nothing else. The `HiddenOre-<version>-plugin.jar` is the shaded
deployable that goes in `plugins/`. Either one satisfies the compiler; prefer the thin one so your build cannot
accidentally resolve a shaded internal type.

HiddenOre requires Java 25 and a Paper API 26.2 server, and declares `folia-supported: true`. Its classes are
Java 25 bytecode, so your own build needs JDK 25 or newer to read them, whatever release level you target.

---

## Acquiring the service

```java
package com.example.quarryguard;

import art.arcane.hiddenore.api.HiddenOreService;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class QuarryGuard extends JavaPlugin {
    private HiddenOreService hiddenOre;

    @Override
    public void onEnable() {
        hiddenOre = resolveHiddenOre();

        if (hiddenOre == null) {
            getLogger().info("HiddenOre is not installed; provenance checks are disabled");
            return;
        }

        getServer().getPluginManager().registerEvents(new QuarryListener(hiddenOre), this);
    }

    public HiddenOreService hiddenOre() {
        return hiddenOre;
    }

    private HiddenOreService resolveHiddenOre() {
        RegisteredServiceProvider<HiddenOreService> registration =
            getServer().getServicesManager().getRegistration(HiddenOreService.class);

        return registration == null ? null : registration.getProvider();
    }
}
```

`getRegistration` returns `null` when HiddenOre is absent, failed to enable, or has already drained. There is
exactly one registration, at `ServicePriority.Normal`; HiddenOre never registers a second provider and no
other plugin is expected to.

Events do not need the service, but they do need the classes. **Keep every HiddenOre listener in a class of its
own and register it only when HiddenOre is present.** `registerEvents` resolves the parameter types of your
handler methods, so registering a listener that mentions `HiddenOreBreakEvent` on a server without HiddenOre
throws `NoClassDefFoundError` — a `softdepend` makes the plugin optional, not its classes. The non-null service
lookup above is one presence check; `getServer().getPluginManager().isPluginEnabled("HiddenOre")` is the other,
for an integration that wants events and no service at all.

---

## The lifecycle, in order

1. **HiddenOre enables.** It declares `load: STARTUP`, so it enables before worlds load. It reads
   `config.yml` and `language.yml` and publishes one immutable runtime record.
2. **The service is registered** with the ServicesManager at `ServicePriority.Normal`. A line naming the
   registration is written to the console.
3. **Your plugin enables.** With `softdepend`/`load: BEFORE`, this happens after step 2, so acquiring the
   service in your own `onEnable` is safe.
4. **During play**, the service answers questions and the two events fire on the region thread that owns the
   broken block.
5. **A configuration reload** (`/hiddenore reload`, or the config file watcher noticing an edit) validates and
   swaps the runtime record in one assignment. Your `HiddenOreService` reference stays valid and keeps working
   — but its *answers* change: `isSeeded()` can flip, a material can stop being managed, and the seeded vein
   layout is recomputed. If the new configuration is invalid the reload is rejected, the previous runtime stays
   live, and nothing you hold changes.
6. **Drain** — plugin disable, or a hot unload by a development tool — unregisters the service and the
   PlaceholderAPI expansion. A `HiddenOreService` reference you cached still works in the sense that it does
   not throw: it degrades to "no data" (see the failure table in [service.md](service.md)). Re-acquire on
   `PluginEnableEvent` rather than holding a reference across a reload of HiddenOre itself.

---

## Threading, in one line

Every method that names a `Block`, `Chunk` or `Location` must be called from the thread that owns that
position's region, and several of them throw `IllegalStateException` if it is not. `ownsRegion(World, int, int)`
is the probe you branch on. On Paper and Spigot that is the main thread; on Folia it is one specific region
thread out of many. [service.md](service.md) states this per method and shows the branch.

---

## What is not part of the contract

| Type                                                   | Why it is not documented here                                                |
|--------------------------------------------------------|------------------------------------------------------------------------------|
| `art.arcane.hiddenore.api.HiddenOreAPI`                | The implementation class. It implements `HiddenOreService` and behaves identically, but reaching it through the plugin main class drags `art.arcane.volmlib` onto your compile classpath. Use the service interface |
| `art.arcane.volmlib.integration.IntegrationServiceContract` | HiddenOre registers a provider for this VolmLib service so that React can read its telemetry. It is a VolmLib contract, versioned by VolmLib, and requires VolmLib on your classpath |
| Everything under `art.arcane.hiddenore.rules`, `.vein`, `.listeners`, `.service`, `.generation`, `.util` | Public Java classes for HiddenOre's own use. They reference shaded and VolmLib types, and they change without notice |

A regression test in the HiddenOre build asserts that no method, constructor or field of `HiddenOreService`,
`ChunkProvenance`, `BlockOrigin`, `HiddenVein`, `HiddenOreBreakEvent` or `HiddenOreDropsEvent` mentions a type
outside `java.*`, `org.bukkit.*` and `art.arcane.hiddenore.api.*`. Those six types are the contract.
