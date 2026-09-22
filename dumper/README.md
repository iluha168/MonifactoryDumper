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
  recipes.json     every recipe, one JSON record per line, each with the offset and length of its image
  images.pak       every image, lossless WebP (stills and looping animations), back to back
  meta.json        pack name and version, Minecraft and Forge, the renderer jar's SHA-256, scale, frame policy,
                   and whether this is the whole corpus or a sample
  categories.tsv   recipes per EMI category, and how many the section 5 exclusions dropped
  animation.tsv    what the frame policy decided for each animated recipe
```

`verifyDump` runs right after and fails the build unless every `recipes.json` entry resolves to an image in
`images.pak` that decodes in full, at its recipe's size, and for an animation loops forever and runs for its frame
count. `./gradlew :dumper:compare:verifyArtifact -Pmonifactory.artifact=<dir>` runs the same check on any directory.

What it costs: a GPU with an EGL driver, about 7 GB of RAM at `-Pmonifactory.heap=4G` (the default 8G heap wants
more), and hours of wall clock. Rendering is the long part, since about 43% of recipes animate and each animated one is
drawn frame by frame until its loop closes or 400 frames go by. The first run also downloads about 1 GB (the pack,
Minecraft, Forge's libraries and the game's assets). Run it on an otherwise idle machine: if an out-of-memory killer
takes the game, the build fails with exit 143 and starts over next time.

Renderer settings go through `-Pmonifactory.dumper=key=value,...`. Two of them make a sample in about 1/N of the
time. `every=N` renders every Nth recipe in corpus order, a proportional sample of every category, which is the one to
estimate sizes and timings from. `sample=N` picks about one recipe in N by a hash of what the recipe is, which is the
one two builds can be compared on (see below). meta.json marks either artifact `"partial": true`.

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

3. Re-check the animation detection on the new pack (PLAN M4). One boot records raw frame hashes, then the ladder and
   the frame policy are checked offline on them:

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
  before the `[dumper]` lines start, and run it again.
- The server datapack reload has a 10 minute timeout of its own and fails the run if it hangs.
- The renderer ends the JVM with status 1 on any failure (never through Minecraft's crash screen, which a mod in the
  pack would turn into a return to the title screen). The reason is in `dumper/build/instance/logs/latest.log`.
