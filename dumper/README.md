This project runs real Monifactory, default mods, default configs.

Here we render and extract whatever we want, and generate the dump. All as part of a Gradle task.

Then other services can use that artifact and do whatever they want with it, without running the game alongside (the modpack takes a lot of RAM and CPU, mind you, how would you even deploy this otherwise).
And also without title screen music playing on a window you cant see :P

## Building the artifact

```sh
./gradlew :dumper:dump -Pmonifactory.heap=4G
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
  categories.tsv   recipes per EMI category, and how many the section 5 exclusions dropped
  render.tsv       how each recipe was drawn: its layers, the animated ones, frames, and why it was drawn whole if it
                   was (diagnostics, not contract)
dumper/build/dumps/latest                          a symlink to the directory the last successful dump wrote
```

A recipe's picture is its layers in draw order: EMI's recipe card first, then one layer per EMI widget, GregTech's
LDLib widgets split per node, so each slot, tank, arrow and text line is a layer of its own. Each layer is a box on the
canvas and a loop of still ids with how many 50 ms ticks each shows; a static layer is one still. To draw a recipe at
tick `t`, copy layer 0's current still, put every later layer's over it ("over" per colour channel, rounded after each
layer), and take the alpha from layer 0. Every layer is checked against EMI's own render of the whole recipe at frame
0 and during its animation; a recipe whose layers do not reproduce it is stored whole instead, as one layer.

Other projects get the artifact through the `dumpArtifact` configuration, which is `latest`. Gradle needs an artifact's
path while it resolves dependencies, before the download has said which pack version this is, so the link is what it
can name.

`verifyDump` runs right after and fails the build unless the stills tile `stills.pak` exactly and each decodes at the
size `stills.json` gives, every record's picture only uses stills that exist, at one size per layer, inside a canvas of
its recipe's size, and meta.json's counts agree. `./gradlew :dumper:compare:verifyArtifact
-Pmonifactory.artifact=<dir>` runs the same check on any directory.

What it costs: a GPU with an EGL driver, about 6 to 6.5 GB of RAM at `-Pmonifactory.heap=4G` and 7 to 8 GB at 5G (the
default 8G heap wants more; 3G is too small and runs out during EMI's reload), and a few hours of wall clock on a laptop
RTX 3050 with 12 hardware threads: about 3 minutes to boot, then about 71 ms of the game's render thread per recipe,
two and a half hours for the whole corpus (an `every=50` sample's batch took 3 minutes). Rendering is the long part,
since about 40% of recipes have a layer that animates, and each such layer is drawn frame by frame until its loop closes
or 400 frames go by. The first run also downloads about 1 GB (the pack, Minecraft, Forge's libraries and the game's
assets). Run it on an otherwise idle machine: if an out-of-memory killer takes the game, the build fails with exit 143
and starts over next time.

On the very first build, ForgeGradle's Mavenizer decompiles Minecraft in a JVM of its own with `-Xms4G` and no
maximum, so it may take a quarter of the machine's RAM. On a machine with little free memory that JVM is the one an
out-of-memory killer picks (seen here as "Failed to run MCP Step (exit code 143)"). Capping every JVM the build starts
gets past it; the game's own `-Xmx` and Gradle's `org.gradle.jvmargs` come later on their command lines and still win:

```sh
JAVA_TOOL_OPTIONS=-Xmx5g ./gradlew :dumper:dump -Pmonifactory.heap=4G
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
./gradlew :dumper:dump -Pmonifactory.heap=4G -Pmonifactory.processes=3
```

The render thread is where a game spends its time, and a game has one, so the build can run N games side by side, each
drawing its share of the recipes, and merge what they write. The artifact is the same kind of directory in the same
place, with `latest` moved and `verifyDump` run the same way. Leave it out (or say 1) and the build is one game as
above, with no merge.

- Which game draws a recipe is a hash of what the recipe is (its category and ids, or its stacks), not its place in
  EMI's list, since that list gains or loses a few dozen recipes from boot to boot. `sample=N` is a hash of the same
  kind, so it picks the same recipes in every game. `count=N` is the first N of the recipes picked, split between the
  games. `every=N` picks by place in the list, which is not the same in any two boots, so the build refuses it with
  more than one game; use `sample=N` instead.
- The games start together. Each runs in an instance directory of its own, since a boot writes into its instance:
  `logs/`, `journeymap/`, `local/`, `fancymenu_data/`, and nearly every file in `config/` (rewritten with the same text
  each boot, a few with a new timestamp). Game 0 runs in `dumper/build/instance`; game k in
  `dumper/build/instances/<k>`, a fresh copy of game 0's made before the games start (about 80 MB, a second or two),
  whose `mods/`, `resourcepacks/` and `shaderpacks/` are symlinks to game 0's, since no boot writes there. Each game
  has its own natives directory too, `dumper/build/instances/<k>-natives`.
- Each game uses fewer threads: encoders `(cores - 1 - N) / N` instead of `cores - 2`, and two matte threads instead of
  four. `-Pmonifactory.heap` is each game's heap.
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

Memory is the limit. Each game needs its heap plus 2 to 3 GB, and the build warns when N of them want more than the
machine has available. Believe the warning. With 10 GB free, two 4G games were killed by earlyoom within minutes; with
swap turned on instead, both slowed to a crawl, and the NVIDIA driver failed to map GPU memory and left the GPU needing
a reboot. `rebuild` and `rebuildCheck` take `-Pmonifactory.processes` too (their games write
`dumper/build/shards/rebuild`); `dumpData` is always one game.

## Recipes without images

```sh
./gradlew :dumper:dumpData -Pmonifactory.heap=4G
```

The same boot, the same corpus and the same `recipes.json` records, but nothing is drawn: every record's `image` is
null. Once the pack and Forge are installed it takes about three minutes (boot, datapack
reload and EMI's reload; writing the 90 MB `recipes.json` is under two seconds) where the full build takes hours. It
writes a directory of its own that never replaces the full build's or moves `latest`:

```
dumper/build/dumps/<pack name>-<pack version>-data/     e.g. Monifactory-0.13.8-data
  recipes.json     every recipe, one JSON record per line, with null image fields
  meta.json        as for the full build, with "images": false; scale, frameMillis, framePolicy, stills,
                   stillsBytes, layered and fallback are null, since there are no images for them to describe
  categories.tsv   as for the full build
```

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
   - A Minecraft bump is a port, not a bump. The renderer reaches four Minecraft members by their SRG names:
     `f_96164_` and `f_104903_` in `Dumper.java`, `m_137550_` (Util.getMillis) and `m_7673_` (TextureManager.tick) in
     the clock agent. `lwjgl` in the catalog must match the new client's LWJGL, and the build says so if it does not.
     The clock agent prints `Util.getMillis NOT patched` or `TextureManager.tick NOT gated` at exit if a name
     stopped matching.

3. Re-check the loop detection on the new pack (PLAN M4). One boot records raw frame hashes, then the frame policy
   is checked offline on them:

   ```sh
   ./gradlew :dumper:runGame -Pmonifactory.mode=seq -Pmonifactory.heap=4G
   ./gradlew :dumper:compare:checkDetection
   ```

4. Smoke test: a sampled artifact takes a fraction of the full build and exercises every category.

   ```sh
   ./gradlew :dumper:dump -Pmonifactory.dumper=every=50 -Pmonifactory.heap=4G
   ```

   Look at `categories.tsv` for categories that appeared, disappeared or went empty, and at the end of the game log
   (`dumper/build/instance/logs/latest.log`, the `[dumper] batch` lines) for render failures. Put a few images next to
   the same recipes in the real game.

5. The full build, which gets its own directory. The previous version's directory stays where it is:

   ```sh
   ./gradlew :dumper:dump -Pmonifactory.heap=4G
   ```

6. Optionally, compare against the previous pack version. Between versions most differences are real changes, so read
   the report rather than the exit code:

   ```sh
   ./gradlew :dumper:compare:compareDumps -Pmonifactory.compare.a=<old dir> -Pmonifactory.compare.b=<new dir>
   ```

## Checking a rebuild

Two builds of the same pack version are not byte-identical, on purpose. GregTech does not boot the same way twice (a
recipe conflict flicker, and slots whose content it picks per boot), and this build runs mods as shipped. So a rebuild
is checked as a set (PLAN section 7):

```sh
./gradlew :dumper:rebuildCheck -Pmonifactory.heap=4G
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
