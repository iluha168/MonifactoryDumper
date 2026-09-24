This project runs real Monifactory, default mods, default configs.

Here we render and extract whatever we want, and generate the dump. All as part of a Gradle task.

Then other services can use that artifact and do whatever they want with it, without running the game alongside (the modpack takes a lot of RAM and CPU, mind you, how would you even deploy this otherwise).
And also without title screen music playing on a window you cant see :P

## Building the artifact

```sh
./gradlew :dumper:dump
```

That one task does everything, from an empty `~/.gradle` and an empty build directory: asks CurseForge which
Monifactory file is current, downloads the pack and every file it lists, gathers vanilla Minecraft, runs the Forge
installer, builds the renderer mod, boots the game headless (no display server, no window), renders every recipe and
exits. It writes one directory per pack version:

```
dumper/build/dumps/<pack name>-<pack version>/     e.g. Monifactory-0.13.8
  recipes.json     every recipe, one JSON record per line, each with its picture as layers of still ids ("image")
  stills.pak       every distinct still, lossless WebP, back to back, in still-id order
  stills.json      where each still is in stills.pak, and its size
  meta.json        artifact format (2), pack name, version and mode, Minecraft and Forge, the renderer jar's
                   SHA-256, scale, frame policy, still and recipe counts, and whether this is the whole corpus or a
                   sample
  categories.tsv   recipes per EMI category, and how many of EMI's own anvil and grindstone entries were dropped
  render.tsv       how each recipe was drawn: its layers, the animated ones, frames, and why it was drawn whole if it
                   was (diagnostics, not contract)
dumper/build/dumps/latest                          a symlink to the directory the last successful dump wrote
```

[FORMAT.md](FORMAT.md) specifies all of it for readers: every field, the stills, and how to draw a recipe at a given
tick. In short, a recipe's picture is its layers in draw order: EMI's recipe card first, then one layer per EMI
widget, GregTech's LDLib widgets split per node, so each slot, tank, arrow and text line is a layer of its own. Each
layer is a box on the canvas and a loop of stills. Every layer is checked against EMI's own render of the whole recipe
at frame 0 and during its animation; a recipe whose layers do not reproduce it is stored whole instead, as one layer.

Other projects get the artifact through the `dumpArtifact` configuration, which is `latest`. Gradle needs an artifact's
path while it resolves dependencies, before the download has said which pack version this is, so the link is what it
can name.

`verifyDump` runs right after and fails the build unless the stills tile `stills.pak` exactly and each decodes at the
size `stills.json` gives, every record's picture only uses stills that exist, at one size per layer, inside a canvas of
its recipe's size, and meta.json's counts agree. `./gradlew :dumper:compare:verifyArtifact
-Pmonifactory.artifact=<dir>` runs the same check on any directory.

What it costs: a GPU with an EGL driver (about 325 MiB of its memory), about 8 GB of RAM, and an hour or two of wall
clock on a laptop RTX 3050 with 12 hardware threads. The game boots in about 2 minutes; then the render thread spends
about 50 ms per recipe (a `sample=50` batch of 2,379 recipes took 2 minutes), which puts the whole corpus of 121,000 at
1.7 hours. Rendering is the long part, since about 40% of recipes have a layer that animates, and each such layer is
drawn frame by frame until its loop closes or 400 frames go by. The first run also downloads about 1 GB (the pack,
Minecraft, Forge's libraries and the game's assets). Run it on an otherwise idle machine: if an out-of-memory killer
takes the game, the build fails with exit 143 and starts over next time.

### Memory and CPU

`-Pmonifactory.heap` is the game's `-Xmx`, 5G unless given. At 5G a game is about 8 GB resident at its peak, during
the boot's datapack and EMI reloads, and about 7.5 GB while it renders (7.0 GB on average in the measured runs). The
rest beyond the heap is metaspace, the code cache and GC structures (about 0.9 GB), and what the JVM does not count:
LWJGL, the NVIDIA driver, the WebP encoders' malloc arenas and thread stacks (about 1.5 GB). While it renders a game
keeps about 4.6 cores busy: its render thread, the encoders and G1.

Less heap is not worth it. At 4G the boot fills the heap and needs three full collections, and the batch took 9% longer;
at 3G it runs out during EMI's reload, and the game exits 0 without an artifact. The build warns below 5G. A 4G run's
25 Thermal Stirling fuel pictures once came out without their animated layer, but that was not the heap: the JEI tick
timer in TooManyRecipeViewers starts its 20-second cycle on the real clock when the category is built, and a layer that
does not loop within 400 frames keeps its first 2 seconds, so each boot keeps a different stretch of the fuel bar, and
in that boot a stretch where it did not move. Two boots at any heap can differ there.

Three things keep a game at those numbers:

- Once EMI has loaded, the renderer lets go of what the server datapack reload left behind in mods' statics, which
  nothing reads again: the server's recipe manager, which KubeJS and Thermal both kept (712 MB), KubeJS's regex filter
  caches (195 MB), and pieces emi_loot, CodeChickenLib and GregTech kept. It collects once and logs
  `[dumper] released the server side: heap N MiB used after GC` (about 3,240 MiB). Each release is reflection on its
  own; a mod that is not there or renamed a field costs that step and a log line. Without it the heap stays 4.1 GB
  full, G1 marks through the whole batch, and the batch takes 15 to 19% longer.
- Every game gets `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`, so G1 gives back the heap the boot needed after
  that collection (5 GB committed becomes about 4.7), and `-XX:TrimNativeHeapInterval=10000`, which returns what
  glibc's arenas have freed every 10 s (a product flag from JDK 17.0.9). Together with the release they take a game
  from 7.9 to 7.0 GB resident while it renders. `MALLOC_ARENA_MAX=2` would save another 140 MiB and cost 16% of the
  batch time, so it is not set.
- `ALSOFT_DRIVERS=null` in the game's environment puts OpenAL on its "No Output" device: the same sound code runs, with
  no audio server and nothing on the speakers.

Two more things are about CPU. The headless GLFW's `glfwWaitEventsTimeout` sleeps for its timeout, so the frame limiter
no longer spins a core through the boot (about 58 CPU-seconds a boot). And once the batch starts, the renderer stops the
client's game ticks by making a tick last `Float.MAX_VALUE` milliseconds: the fake clock does not cover `partialTick`,
direct reads of the level's game time, GregTech's `CLIENT_TIME` or the shaders' `GameTime`, and now they stand still
too.

Every frame of an animation gets one atlas tick, and a full tick walks all 1,607 animated sprites through Embeddium's
per-sprite hook, about 116 us, which was 15% of the render thread. Embeddium only uploads sprites something marked
active since the last tick, so the renderer ticks just the sprites marked since the recipe started, through their own
tickers: 9 on average, and the ticks went from 20 to 28 s of a `sample=50` batch to 3 to 4 s. A sprite's counter now
stands still between the recipes that show it. That moves the phase a recipe's animation starts at, which was never
fixed: it followed every frame every recipe before had drawn, so two boots whose lists differed by one animated recipe
drew different phases from there on. Of the batch's 990 animated pictures, two boots now agree byte for byte on 779 to
915, where they agreed on 612 or 613. Without Embeddium's marks, or with its "animate only visible textures" off, every
tick is the full one again and the log says so.

On the very first build, ForgeGradle's Mavenizer decompiles Minecraft in a JVM of its own with `-Xms4G` and no
maximum, so it may take a quarter of the machine's RAM. On a machine with little free memory that JVM is the one an
out-of-memory killer picks (seen here as "Failed to run MCP Step (exit code 143)"). Capping every JVM the build starts
gets past it; the game's own `-Xmx` and Gradle's `org.gradle.jvmargs` come later on their command lines and still win:

```sh
JAVA_TOOL_OPTIONS=-Xmx5g ./gradlew :dumper:dump
```

meta.json's `pack.version` and `pack.mode` come from the running game, not from a config file. The mode is KubeJS's
`global.packmode`, which Monifactory's `kubejs/startup_scripts/_packmode.js` sets and every recipe script branches on
(Normal, Hard or Expert). The version is the text FancyMenu puts after "Version" on the title screen, resolved through
FancyMenu's own placeholder parser from its loaded title-screen layout. `pack.name` comes from the pack's
`manifest.json`, since the game has no name for itself. If the manifest's version and the title screen's disagree, the
run fails before it renders anything. The artifact directory is named after the manifest, so the two would otherwise
contradict each other.

Renderer settings go through `-Pmonifactory.dumper=key=value,...`. Two of them make a sample in about 1/N of the
time. `every=N` renders every Nth recipe in corpus order, a proportional sample of every category, which is the one to
estimate sizes and timings from. `sample=N` picks about one recipe in N by a hash of what the recipe is, which is the
one two builds can be compared on (see below). meta.json marks either artifact `"partial": true`. `count=N` renders only
the first N recipes of whatever those pick, for trying the build out.

## Several games at once

```sh
./gradlew :dumper:dump -Pmonifactory.processes=3
```

The render thread is where a game spends its time, and a game has one, so the build can run N games side by side, each
drawing its share of the recipes, and merge what they write. The artifact is the same kind of directory in the same
place, with `latest` moved and `verifyDump` run the same way. Leave it out (or say 1) and the build is one game as
above, with no merge.

- Which game draws a recipe is a hash of what the recipe is, not its place in EMI's list, since that list gains or loses
  a few dozen recipes from boot to boot. That is its category and ids, or with no ids its stacks. Where other recipes
  share those, it is also every stack's amount, chance and NBT and the result on its card, which tell apart
  FramedBlocks' framing saw recipes (about 25,000 of them name nothing) and the fuels and enchantments
  TooManyRecipeViewers lists under one id. The few recipes equal in all of that too (Thermal's disenchantment fuels,
  each listed three times) are numbered in list order. Every recipe gets a key of its own, and a recipe no other recipe
  resembles keeps its key when GregTech moves its amounts or NBT between boots. `sample=N` is a hash of the same kind,
  so it picks the same recipes in every game. `count=N` is the first N of the recipes picked, split between the games.
  `every=N` picks by place in the list, which is not the same in any two boots, so the build refuses it with more than
  one game; use `sample=N` instead.
- The games start one at a time: game k+1 starts once game k has logged `[dumper] released the server side`, about
  2 minutes into its boot. A boot's memory peak comes before that line, so only one game is ever at its peak. A game
  that fails or hangs before the line holds the next one back until it exits or goes 30 minutes without output, and
  a game started again after a failure waits its turn the same way.
- Each game runs in an instance directory of its own, since a boot writes into its instance:
  `logs/`, `journeymap/`, `local/`, `fancymenu_data/`, and nearly every file in `config/` (rewritten with the same text
  each boot, a few with a new timestamp). Game 0 runs in `dumper/build/instance`; game k in
  `dumper/build/instances/<k>`, a fresh copy of game 0's made before the games start (about 80 MB, a second or two),
  whose `mods/`, `resourcepacks/` and `shaderpacks/` are symlinks to game 0's, since no boot writes there. Each game
  has its own natives directory too, `dumper/build/instances/<k>-natives`.
- Each game uses fewer threads: encoders `(cores - 1 - N) / N` instead of `cores - 2`, and two matte threads instead of
  four. `-Pmonifactory.heap` is each game's heap, and each gets the flags and environment above.
- A game that fails is started again once, on its own, while the others go on: a non-zero exit (the server datapack
  reload timeout, or 143 when an out-of-memory killer took it), an exit without a finished artifact, or 30 minutes
  without a line of output (a boot hung in mod construction, which one game alone waits 12 hours on). A second failure
  stops every game and fails the build, naming the logs. Each game's output is in `dumper/build/shards/dump/<k>-attempt<a>.out`,
  the `logs/latest.log` of a failed attempt is kept next to it as `<k>-attempt<a>.latest.log`, and each instance keeps
  its own `logs/`.
- The games write `dumper/build/shards/dump/<k>`, each an artifact of its own recipes plus `shard.tsv`, the key of every
  recipe its boot picked, in its order. `MergeShards` (in `:dumper:compare`) makes one artifact of them. Records follow
  game 0's list; a recipe only another game's boot had goes right after the recipe before it in that game's list.
  Still ids are handed out again in record order, and a still two games both drew is stored once: equal pictures encode
  to equal WebP bytes, so the merge keeps one copy of equal bytes and copies payloads without re-encoding them.
  `categories.tsv` and `corpus` are game 0's. meta.json says `"processes": N` and `"drift"`, how many recipes some
  game's boot listed and another's did not, and the merge prints the same with how many of those were drawn.
- The shards merge in whatever order they finished; the result depends only on what they hold.

Memory is the limit. At the default 5G heap, N staggered games want about 7.5 GB for each game rendering and 8 GB for
the one booting, and the machine wants some room for everything else: (N - 1) x 7.5 + 8 + 2 GB of available memory.
That is about 18 GB for 2 games and 25 GB for 3, so with 26 GB available, run 3. The build warns when N games want more
than the machine has available. Believe the warning. With 10 GB free, two 4G games were killed by earlyoom within
minutes; with swap turned on instead, both slowed to a crawl, and the NVIDIA driver failed to map GPU memory and left
the GPU needing a reboot. CPU runs out next: each game keeps about 4.6 cores busy while it renders, so on 12 hardware
threads a third game shares cores with the other two.

The staggered start costs a boot per extra game, about 2 minutes, so more games only pay off when the batch is longer
than that. A `sample=50` build (2,381 recipes) took 4 minutes 15 seconds with one game and 5 minutes 40 seconds with
two: game 1 started 2 minutes into game 0's run, and game 0's batch was done a minute and a half later. The games'
render threads spent 51 ms a recipe alone and 58 ms side by side, so the whole corpus should take about an hour with two
games against an hour and three quarters with one. The shares were not even either: 1,300 and 1,081 of the 2,381, since
back then recipes that shared a key went to one game together, and three framing saw keys had 134 recipes each. With a
key per recipe the same hash splits a data boot's `sample=50` (2,409 recipes) into 1,245 and 1,164.

`rebuild` and `rebuildCheck` take `-Pmonifactory.processes` too (their games write
`dumper/build/shards/rebuild`); `dumpData` is always one game.

## Recipes without images

```sh
./gradlew :dumper:dumpData
```

The same boot, the same corpus and the same `recipes.json` records, but nothing is drawn: every record's `image` is
null. Once the pack and Forge are installed it takes about three minutes (boot, datapack
reload and EMI's reload; writing the 90 MB `recipes.json` is under two seconds) where the full build takes hours. It
writes a directory of its own that never replaces the full build's or moves `latest`:

```
dumper/build/dumps/<pack name>-<pack version>-data/     e.g. Monifactory-0.13.8-data
  recipes.json     every recipe, one JSON record per line, with null image fields
  meta.json        as for the full build, with "images": false and the image fields null
  categories.tsv   as for the full build
```

[FORMAT.md](FORMAT.md) covers this kind of artifact too.

`verifyDumpData` runs right after and fails the build unless every `recipes.json` line parses, no record claims an
image, there are no `stills.*`, and meta.json counts the same recipes. `every=N`, `sample=N` and `count` work as they
do for the full build, and mark the artifact `"partial": true` the same way.

## Updating to a new pack version

Everything is pinned to what the pack's `manifest.json` says. Only `gradle/libs.versions.toml` repeats two of those
numbers, `minecraft` and `forge`, because ForgeGradle needs them while the build configures, before anything is
downloaded. The download checks both against the manifest every time it runs and stops the build on a mismatch, so a
pin that has gone stale cannot compile the renderer against the wrong Minecraft.

1. Ask CurseForge again. The answer is cached for a day, so after a release:

   ```sh
   ./gradlew :dumper:deps:downloader:fetchLatest --rerun
   ```

2. Download the new pack and let it check the pins:

   ```sh
   ./gradlew :dumper:deps:downloader:downloadPack
   ```

   If it fails with "The pack runs on Minecraft X / Forge Y, but gradle/libs.versions.toml pins ...", set `minecraft`
   and `forge` in `gradle/libs.versions.toml` to what it says and run it again.
   - A Forge bump on the same Minecraft is usually just that. The installer, the launch arguments and the renderer
     all follow the pin.
   - A Minecraft bump is a port, not a bump. The renderer reaches Minecraft members by their SRG names, among them
     `f_96164_` and `f_104903_` in `Dumper.java`, `m_137550_` (Util.getMillis) and `m_7673_` (TextureManager.tick) in
     the clock agent, `f_90991_` and `f_92521_` (Minecraft.timer, Timer.msPerTick) in `ClientTicks.java`, and
     `f_118469_`, `f_118262_` and `f_243782_` (the texture manager's tickable textures, an atlas's animated sprites,
     a sprite ticker's sprite) in `SpriteTicks.java`. `lwjgl` in the catalog must match the new client's LWJGL, and
     the build says so if it does not. The clock agent prints `Util.getMillis NOT patched` or
     `TextureManager.tick NOT gated` at exit if a name stopped matching, the game log says `client ticks keep running`
     if the timer's did, and `every atlas tick ticks every animated sprite` if the atlases' did.

3. Re-check the loop detection on the new pack: a layer is stored as one period of its animation, and the early stop
   that finds the period must agree with drawing all 400 frames. One boot records raw frame hashes, then the frame
   policy is checked offline on them:

   ```sh
   ./gradlew :dumper:runGame -Pmonifactory.mode=seq
   ./gradlew :dumper:compare:checkDetection
   ```

4. Smoke test: a sampled artifact takes a fraction of the full build and exercises every category.

   ```sh
   ./gradlew :dumper:dump -Pmonifactory.dumper=every=50
   ```

   Look at `categories.tsv` for categories that appeared, disappeared or went empty, and at the end of the game log
   (`dumper/build/instance/logs/latest.log`, the `[dumper] batch` lines) for render failures. Put a few images next to
   the same recipes in the real game.

5. The full build, which gets its own directory. The previous version's directory stays where it is:

   ```sh
   ./gradlew :dumper:dump
   ```

6. Optionally, compare against the previous pack version. Between versions most differences are real changes, so read
   the report rather than the exit code:

   ```sh
   ./gradlew :dumper:compare:compareDumps -Pmonifactory.compare.a=<old dir> -Pmonifactory.compare.b=<new dir>
   ```

## Checking a rebuild

Two builds of the same pack version are not byte-identical, on purpose. GregTech does not boot the same way twice (a
recipe conflict flicker, and slots whose content it picks per boot), and this build runs mods as shipped. So a rebuild
is checked as a set:

```sh
./gradlew :dumper:rebuildCheck
```

It builds the artifact if it is not up to date, builds it again into `<pack>-<version>-rebuild`, verifies both, and
compares them. They must hold the same recipes except the documented GT flicker and the TooManyRecipeViewers/EMI info
drift. A picture may differ only inside one boot-varying slot (a tag, a list of options, an NBT variant, a GregTech
multiblock's representative part), or by the same stacks sitting in another order. Anything else fails the task, and
`dumper/build/rebuild-compare.tsv` lists every difference with its reason.

The check doubles the build time. `-Pmonifactory.dumper=sample=N` makes it a quicker check of the same rules: both
builds render about one recipe in N, picked by a hash of the recipe's ids (or, for a recipe with no id, its category and
stacks), so both pick the same recipes. `every=N` will not do here. It picks by position, and EMI's list drifts by a
few dozen recipes between boots, so two `every` samples hold different recipes and the comparison refuses them.

## When a boot goes wrong

- About one boot in eight has hung in mod construction: a GregTech worker spinning in `DynamicRenderManager.register`
  before the renderer exists to notice. The dump task gives up after 12 hours; kill it sooner if the log stops moving
  before the `[dumper]` lines start, and run it again. With `-Pmonifactory.processes` above 1, a game whose output stops
  for 30 minutes is stopped and started again.
- The server datapack reload has a 10 minute timeout of its own and fails the run if it hangs. With several games, the
  game that hit it is started again once.
- If the game exits before meta.json is written, the dump fails with "The game exited without finishing the
  artifact", even when the JVM's status was 0. Running out of heap ends that way: Minecraft stops itself on an
  `OutOfMemoryError` and exits cleanly. Give it more `-Pmonifactory.heap`.
- The renderer ends the JVM with status 1 on any failure (never through Minecraft's crash screen, which a mod in the
  pack would turn into a return to the title screen). The reason is in `dumper/build/instance/logs/latest.log`, or,
  with several games, in the log the build names.
