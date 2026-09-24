# Artifact format 2

This is what `./gradlew :dumper:dump` and `./gradlew :dumper:dumpData` write, described for someone who reads it
without the Java: every file, every field, and how to draw a recipe's picture from it. Numbers quoted "in 0.13.8" come
from the full build of Monifactory 0.13.8 (121,304 recipes) and its data-only build (121,281); they show what real
data looks like and are not part of the contract.

Two reference implementations live in this repository:

- `dumper/deps/imgencoder/src/main/java/com/iluha168/monifactory/imgencoder/layered/` holds the types the renderer
  writes a picture with (`LayeredImage`, `Layer`, `Timeline`, `StillTable`), whose constructors enforce every rule of
  the `image` object, and `Compositor`, which draws a picture at a tick.
- `dumper/compare/src/main/java/com/iluha168/monifactory/compare/Artifact.java` reads an artifact directory with
  Gson: `meta.json`, the still table, `recipes.json` streamed record by record, and each `image` object turned back
  into those types. `VerifyArtifact.java` next to it checks a whole artifact against this document.

## Directory layout

A full build writes one directory per pack version, `dumper/build/dumps/<pack name>-<pack version>/`, and a data-only
build writes `dumper/build/dumps/<pack name>-<pack version>-data/`:

```
recipes.json     every recipe, one JSON record per line                         contract
stills.json      where each still is in stills.pak, and its size                contract, full build only
stills.pak       every distinct still, lossless WebP, back to back               contract, full build only
meta.json        what the artifact is: format, pack, sizes, counts              contract
categories.tsv   every EMI category with its recipe count                       contract
render.tsv       how each recipe was drawn                                      diagnostics, full build only
```

A data-only artifact has the same `recipes.json` records with every `image` null, `meta.json` saying
`"images": false`, and `categories.tsv`. It has no `stills.*` and no `render.tsv`.

`meta.json` is written last. A directory without it is unfinished, whatever else it holds.

A game of a sharded build (`-Pmonifactory.processes`) writes an artifact of its own share plus `shard.tsv`, and the
build merges those into one ordinary artifact. The shard directories are an intermediate step under
`dumper/build/shards/`; nothing in this document needs them, and `shard.tsv` never appears in a published artifact.

All text files are UTF-8 with `\n` line ends. JSON strings escape `"`, `\`, and the control characters; everything
else, non-ASCII included, is written as it is.

## recipes.json

A JSON array of record objects. The writer puts `[` on the first line, one record per line, each followed by `,`
except the last, and `]` on the last line. Strings never hold a raw newline, so a record is always exactly one line.
The file is large (176 MB in 0.13.8, 90 MB for data only), so a reader will usually stream it:

```python
import json

def records(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line in ("[", "]"):
                continue
            yield json.loads(line.removesuffix(","))
```

Parsing the whole file as one JSON document works too.

### Order and identity

Records come in EMI's category order, the order of `categories.tsv`, and within a category in the order EMI lists its
recipes. Each category's recipes are contiguous. A record's position (0-based, not counting the `[` line) is its
index, which `render.tsv` refers to; nothing else refers to it, and it is not stable between builds.

Records are not keyed by anything. An id is data on the record, not a key:

- `emiRecipeId` is null for 48,584 of the 121,304 records in 0.13.8, 29,584 of them FramedBlocks framing saw
  recipes.
- Ids are not unique either: in 0.13.8, 43 `emiRecipeId` values are each shared by several records, 359 records in
  all. TooManyRecipeViewers lists Thermal's fuels and EnderIO's enchanter recipes several times under one id; one
  Thermal id covers 227 records.
- Two records may have the same content. Two recipes with equal category, inputs and outputs are still two recipes,
  and a player sees both (GregTech's Forge Hammer lists `nether_star_block` twice under two ids).

Each recipe EMI lists is in the artifact once, except those of the two excluded categories (see `categories.tsv`)
and, in a build that ran several games, the few that drift between boots (see `meta.json`).

### Record fields

Keys come in this order: `emiRecipeId`, `underlyingRecipeId`, `cat`, `cls`, `w`, `h`, `in`, `cats`, `out`, `outFrom`
when present, `image`, and `err` when present. A reader should look fields up by name.

| Field | Type | Meaning |
|---|---|---|
| `emiRecipeId` | string or null | EMI's id for the recipe (`EmiRecipe.getId()`), null where it has none. |
| `underlyingRecipeId` | string or null | The id of the datapack recipe behind it (`getBackingRecipe()`), null where there is none. In 0.13.8, every record without an `emiRecipeId` has no `underlyingRecipeId` either, and 5,708 with one have none. |
| `cat` | string | The EMI category id, one of `categories.tsv`'s. |
| `cls` | string | The Java class of EMI's recipe display, such as `dev.emi.emi.recipe.EmiShapedRecipe`. |
| `w`, `h` | integer | The display size in GUI pixels, as EMI lays the recipe out. The picture is `(w + 8) * scale` by `(h + 8) * scale` pixels, `scale` being `meta.json`'s. |
| `in` | array | Inputs, in EMI's order: ingredient objects (below). |
| `cats` | array | Catalysts (machines, workstations, non-consumed tools), in EMI's order. |
| `out` | array | Outputs, in EMI's order. May be empty: a fuel or an information page has no result. |
| `outFrom` | `"slots"`, only when present | EMI's `getOutputs()` was empty but the recipe's card shows a result, so `out` was read off the card: the slots the recipe marks as its own result slots, less anything already listed as an input or catalyst. Absent on every other record. 29,584 records in 0.13.8, all of them framing saw recipes. |
| `image` | object or null | The recipe's picture (see "The image object"). Null in a data-only artifact, and in a full one for a recipe that failed to render (`meta.json`'s `failed` counts those). |
| `err` | string, only when present | What threw while the recipe was read from EMI. The writer then fails the run before it writes `meta.json`, so a finished artifact has no `err`. Only such a record may have `w` or `h` of -1, or `in`, `cats` or `out` null. |

### Ingredient objects

An element of `in`, `cats` or `out` is one of three kinds, told apart by `k`. An element may in principle be `null`,
where EMI handed out a null ingredient; 0.13.8 has none.

**`"k": "s"`, a concrete stack.** Keys in order: `k`, `t`, `id`, `n`, `c` if present, `nbt`, then `nbtk`, `nbth` and
`ench` if present, then `rem` if present.

| Field | Type | Meaning |
|---|---|---|
| `t` | string | `"item"` or `"fluid"`. `"empty"` for a stack EMI calls empty whose key is neither (NuclearCraft's particles, in 0.13.8). Otherwise the Java class name of the stack's key (`igentuman.nc.content.particles.Particle`), or `"null"` if it has none. |
| `id` | string | The stack's id, such as `"minecraft:oak_log"`. EMI's empty stack, which fills the unused cells of a crafting grid, is `{"k":"s","t":"item","id":"emi:empty","n":1,"nbt":0}` (83,163 of them in 0.13.8). |
| `n` | integer | The amount as EMI gives it: an item count, or millibuckets for a fluid. A 64-bit value in EMI; may be 0. |
| `c` | number, only when present | The chance, when it is not 1: `0.14` is 14%. Written as Java prints a `float`, so exponent notation (`1.0E-4`) is possible. `0.0` occurs: GregTech lists the items a recipe needs but does not use up (programmed circuits, lenses) among the catalysts with chance 0. |
| `nbt` | 0 or 1 | Whether the stack carries a non-empty NBT tag. |
| `nbtk` | array of strings, only when `nbt` is 1 | The tag's top-level keys, in no particular order. |
| `nbth` | integer, only when `nbt` is 1 | Java's `String.hashCode()` (32-bit, signed) of the tag's SNBT text. It tells NBT variants of one item apart; the tag itself is not in the artifact. |
| `ench` | object, only when present | With `nbt` 1 and a non-empty `Enchantments` or `StoredEnchantments` list in the tag: an object with those of the two keys that apply, `Enchantments` first, each an array of `[enchantment id, level]` pairs. |
| `rem` | string, only when present | The id of what stays behind when the stack is used, such as a bucket. |

**`"k": "t"`, a tag.** Keys in order: `k`, `tag`, `reg`, `n`, `c` if present, `matches`.

| Field | Type | Meaning |
|---|---|---|
| `tag` | string | The tag id, such as `"minecraft:oak_logs"`. |
| `reg` | string | The registry it is a tag of, such as `"minecraft:item"`. |
| `n`, `c` | | As for a stack. |
| `matches` | integer | How many stacks the tag stood for in the boot that wrote the artifact. May be 0. |

**`"k": "m"`, anything else with several options**, most often EMI's `ListEmiIngredient` ("any of these"). Keys in
order: `k`, `cls`, `n`, `c` if present, `count`, `ids`, `parts` if present.

| Field | Type | Meaning |
|---|---|---|
| `cls` | string | The Java class of the ingredient, such as `dev.emi.emi.api.stack.ListEmiIngredient`. A mod's own ingredient type lands here too. |
| `n`, `c` | | As for a stack. |
| `count` | integer | How many options there are: the length of `ids`. |
| `ids` | array of strings | Every option's id, in EMI's order. Amounts and NBT of the options are not recorded. |
| `parts` | integer, only for `ListEmiIngredient` | How many ingredients the list was built from. A part may be a tag, so `parts` can be less than `count`. |

## The image object

```json
{"w":344,"h":140,"layers":[{"x":0,"y":0,"f":[328214],"d":[1]},{"x":4,"y":4,"f":[336287,336288,336289],"d":[2,24,14]}]}
```

- `w`, `h`: the canvas in pixels, both at least 1. Always `(record.w + 8) * scale` by `(record.h + 8) * scale`.
- `layers`: a non-empty array, in draw order. Layer 0 is the one whose alpha becomes the picture's alpha.
- `x`, `y`: where the layer's stills go on the canvas, top-left corner, y down, both at least 0.
- `f`: the layer's loop, as still ids (indices into `stills.json`). `d`: how many ticks each entry of `f` shows. One
  tick is `meta.json`'s `frameMillis`, 50 ms, which is also one Minecraft tick.

A layer has no size field. Its size is its stills' size, from `stills.json`. The renderer guarantees, and
`VerifyArtifact` checks, that:

- `f` and `d` have the same length, at least 1.
- Every id in `f` is a row of `stills.json`, and every still of one layer has the same width and height.
- Every `d` is at least 1.
- Two consecutive entries of `f` are never the same still. The first and the last may be (1,938 animated layers in
  0.13.8): merging them would rotate the loop and shift the layer's phase against the others.
- A layer with one entry is static, and then `d` is `[1]`.
- Each layer lies inside the canvas: `x + width <= w` and `y + height <= h`.

Every layer loops on its own, all starting on their first entry at tick 0. There is no common period to know, and
computing one is rarely useful: the least common multiple of a picture's loop lengths reaches 1,185,600 ticks in
0.13.8.

What the layers are is not part of the contract, but it helps to know. For a recipe drawn as layers, layer 0 is EMI's
card, at (0, 0), the size of the canvas and static; every EMI widget follows as a layer of its own, and GregTech's
LDLib widgets are split into one layer per node, so each slot, tank, arrow and text line is a layer. Where the layers
did not reproduce EMI's own render of the whole recipe, the recipe is drawn whole instead: one layer at (0, 0), the
size of the canvas, animated if the recipe is. `render.tsv` says which way each recipe went; the picture does not need
to, since the drawing rule covers both.

### Loop lengths

The renderer draws an animated layer frame by frame until its pictures repeat with a strict period (the smallest `p`
with frame `k` equal to frame `k + p` for every frame drawn), and stores one period. If none closes within
`framePolicy.cap` frames (400), it stores the first `framePolicy.trim` frames (40, two seconds) and the loop jumps
where it wraps. So `sum(d)` is at most `framePolicy.maxStored` (200). A loop of exactly 40 ticks is either one of
those or a real 2-second loop, which is common: GregTech's progress arrows have that period.

## stills.json and stills.pak

`stills.json` is a JSON array with one row per line, like `recipes.json`. Row `i` describes still `i`:

```
[offset, length, width, height]
```

`offset` and `length` are bytes of `stills.pak`; `width` and `height` are the still's size in pixels, 1 to 16383
each. The stills tile the file in id order: still 0 starts at 0, each starts where the one before ended, and the last
ends at the end of the file, whose size is `meta.json`'s `stillsBytes`. `stills.pak` has no header, index or padding.

Each still is a complete lossless WebP file (`RIFF....WEBPVP8L`, a single image, no animation), written by libwebp
at `cwebp -z 9` settings. Decoded, it is straight (non-premultiplied) RGBA, top row first, exactly as drawn. A still
with no transparent pixel is stored without an alpha channel and decodes with alpha 255 everywhere. The colour of
pixels with alpha 0 survives the round trip, but means nothing.

Each distinct picture is stored once for the whole artifact, and records share stills freely: every card of a
category is one still, and one layer's still may be another recipe's too. In 0.13.8, 371,972 stills take 174 MB. Every
still is used by some record. In artifacts the renderer or the shard merge wrote, ids are numbered in the order records
first use them (record by record, layer by layer, entry by entry), so reading records in order reads `stills.pak`
roughly front to back; a reader need not rely on that.

## Drawing a recipe at a tick

To draw a record's `image` at tick `t` (any integer; tick 0 shows every layer's first entry):

1. For each layer, find the still it shows: take `t mod sum(d)`, rounded towards negative infinity so it is never
   negative, and walk the entries, subtracting each `d` until the remainder goes below 0. That entry's `f` is the
   still.
2. Start a `w` by `h` canvas of transparent pixels, `(0, 0, 0, 0)` in RGBA, 8 bits a channel.
3. Layer 0: copy its still onto the canvas at (`x`, `y`), all four channels, as it is. That is what straight-alpha
   "over" does onto a transparent canvas.
4. Each later layer, in order: every pixel of its still, with alpha `a`, goes onto the canvas pixel under it:
   - `a` is 0: nothing changes.
   - `a` is 255: the canvas pixel's red, green and blue become the still's.
   - otherwise each colour channel becomes `(s * a + S * (255 - a) + 127) / 255` in integer division, where `s` is the
     still's channel and `S` the canvas's.

   The canvas alpha never changes after layer 0.
5. Every pixel whose alpha is 0 becomes `(0, 0, 0, 0)`.

The result is the picture: its alpha is layer 0's alpha where layer 0 lies, and 0 elsewhere. Step 4 is GL's
`SRC_ALPHA, ONE_MINUS_SRC_ALPHA` colour blend, Minecraft's default, rounded to the nearest 8-bit level after every
layer as a GL render target of 8 bits a channel does; 255 is odd, so there are no ties. Step 5 makes two draws of the
same picture equal pixel for pixel. This is exactly what `Compositor.composite` computes, and the renderer uses that
same code to check each recipe's layers against EMI's own render of the recipe: every pixel the real render covers
must be within 1 level per channel, or the recipe is drawn whole.

A picture is animated when any of its layers has more than one entry in `f`: 50,212 of 121,304 in 0.13.8. To play it,
show tick `floor(milliseconds / frameMillis)`.

## meta.json

A pretty-printed JSON object that says what the artifact is. Every key below is present in both kinds of artifact;
the ones that describe pictures are null in a data-only one.

| Field | Type | Meaning |
|---|---|---|
| `format` | integer | The artifact format: `2`. See "Versions". |
| `pack` | object | `name` (from the pack's `manifest.json`, such as `"Monifactory"`), `version` (the version the running game's title screen shows, such as `"0.13.8"`) and `mode` (KubeJS's `global.packmode`: `"Normal"`, `"Hard"` or `"Expert"`), all strings. |
| `minecraft` | string | The Minecraft version, such as `"1.20.1"`. |
| `forge` | string | The Forge version, such as `"47.4.13"`. |
| `renderer` | string or null | The SHA-256, in lowercase hex, of the renderer mod's jar that wrote the artifact. Null if the renderer did not run from a jar. |
| `recipes` | integer | How many records `recipes.json` holds. |
| `corpus` | integer | How many recipes the boot's corpus holds: every recipe EMI listed, less the excluded ones. The sum over `categories.tsv` of `recipes - dropped`. |
| `partial` | boolean | True when the artifact is a sample or was cut short (`every` or `sample` above 1, or a `limit`), so it is not the whole corpus. |
| `every` | integer | 1, or N for "every Nth recipe in corpus order" (`-Pmonifactory.dumper=every=N`). |
| `sample` | integer | 1, or N for "about one recipe in N, picked by a hash of what the recipe is" (`sample=N`). |
| `limit` | integer or null | The `count=N` the run was capped at, when that is below `corpus`; otherwise null. |
| `failed` | integer | Records whose `image` is null because the recipe failed to render. Always 0 in a data-only artifact. A full build with failed recipes still writes the whole artifact, then reports failure. |
| `images` | boolean | Whether the artifact has pictures: `stills.*` and non-null `image` fields. |
| `scale` | integer or null | Pixels per GUI pixel, EMI's recipe screenshot scale: 2 for a headless build. |
| `frameMillis` | integer or null | Milliseconds per tick of a layer's loop: 50. |
| `framePolicy` | object or null | `cap` (400), `trim` (40) and `maxStored` (200), all integers, as in "Loop lengths". |
| `stills` | integer or null | How many rows `stills.json` has. |
| `stillsBytes` | integer or null | The size of `stills.pak` in bytes. |
| `layered` | integer or null | Recipes drawn as layers. |
| `fallback` | integer or null | Recipes drawn whole, as one layer. `layered + fallback + failed == recipes`. |

A build that ran several games (`-Pmonifactory.processes=N`) merges their artifacts into one, and its `meta.json`
has two keys more:

| Field | Type | Meaning |
|---|---|---|
| `processes` | integer | How many games drew the artifact. |
| `drift` | integer | How many recipes some game's boot listed and another's did not. EMI's list gains or loses a few dozen recipes from boot to boot. |

In a merged artifact, `recipes`, `partial`, `failed`, `stills`, `stillsBytes`, `layered` and `fallback` describe the
merged whole, while `corpus` and `categories.tsv` are game 0's boot. A recipe only another game's boot listed goes in
after its neighbour, and one that game 0 listed but the game that owned it did not is missing, so `recipes` may differ
from `corpus` with `partial` false: 121,304 against 121,314 in 0.13.8, with a drift of 72. A single game's artifact
has `recipes == corpus` whenever `partial` is false.

A game's own shard artifact also has `"shard": {"index": i, "count": n}`. The merge removes it, so a published
artifact never has it.

## categories.tsv

Tab-separated, with a header line:

```
category	recipes	dropped
minecraft:crafting	26307	0
```

One row per category id EMI lists, in EMI's order, empty categories included (231 rows in 0.13.8). `recipes` is how
many recipes EMI lists in the category; `dropped` is how many of those the build left out. Where EMI lists one
category id twice (TooManyRecipeViewers does), the row counts the first listing.

The rows describe the whole corpus of the boot, not the records: a sampled or capped artifact has the same
`categories.tsv` as the full one.

Two categories are excluded, and only in them can `dropped` be above 0. `emi:anvil_repairing` and `emi:grinding` are
filled by EMI itself from item properties, with no datapack recipe behind any of it: the anvil one is every
enchantable tool crossed with every enchantment, and the grinding one the same tools disenchanted and combined. The
build drops the recipes of those two categories whose display class is EMI's own (a class name starting with
`dev.emi.emi.`). Recipes a mod registered there stay: in 0.13.8, `emi:anvil_repairing` lists 12,230 and drops 12,226,
keeping Quark's four real repair recipes, and `emi:grinding` drops all 2,929.

## render.tsv (diagnostics)

Not part of the contract: its columns may change without a format bump. Tab-separated, one row per record, with a
header line:

| Column | Meaning |
|---|---|
| `index` | The record's index in `recipes.json`. |
| `emiRecipeId` | As in the record, empty for null. |
| `category`, `class` | The record's `cat` and `cls`. |
| `layers` | Layers in the picture; 0 for a failed recipe. |
| `animatedLayers` | Layers the renderer drew frame by frame. It may count a layer that came out with one still. For a recipe drawn whole, 0 or 1. |
| `framesDrawn` | Frames drawn after frame 0. |
| `mode` | `layered`, `fallback` (drawn whole) or `failed`. |
| `reason` | Why the recipe was drawn whole, or why it failed; empty otherwise. |
| `millis` | Render-thread milliseconds the recipe took. |

## Versions

`meta.json`'s `format` is `2` for everything this document describes. A reader should refuse any other value rather
than guess: a different number means the layout changed in a way this document does not cover. Format 1 artifacts,
from earlier renderers, have no `format` key and an `images.pak` instead of `stills.*`; the tools in `dumper/compare`
recognise and refuse them. Within format 2, a reader should ignore keys it does not know: to a reader written for
single-game builds, the `processes` and `drift` of a merged one are such keys.

## A worked example

Record 116,931 of the 0.13.8 full build, a Thermal Stirling dynamo fuel shown through TooManyRecipeViewers:

```json
{"emiRecipeId":"toomanyrecipeviewers:/thermal/stirling_tar","underlyingRecipeId":null,"cat":"thermal:stirling_fuel",
 "cls":"dev.nolij.toomanyrecipeviewers.impl.recipe.TMRVRecipe","w":164,"h":62,
 "in":[{"k":"s","t":"item","id":"thermal:tar","n":1,"nbt":0}],"cats":[],"out":[],
 "image":{"w":344,"h":140,"layers":[{"x":0,"y":0,"f":[328214],"d":[1]},
                                    {"x":4,"y":4,"f":[336287,336288,336289],"d":[2,24,14]},
                                    {"x":70,"y":50,"f":[336524],"d":[1]}]}}
```

(It is one line in the file.) The canvas is `(164 + 8) * 2` by `(62 + 8) * 2`, 344 by 140. `stills.json` gives the
sizes: still 328214, the card, is 344x140; stills 336287 to 336289, the category's background with its slot, flame and
energy bar, are 336x132 each; still 336524, the tar, is 44x44. Layer 1 loops over 2 + 24 + 14 = 40 ticks: at tick 30,
`30 mod 40 = 30`, and 30 - 2 = 28, 28 - 24 = 4, 4 - 14 < 0, so it shows still 336289. The picture is animated, since
layer 1 has three entries, and repeats every 40 ticks, two seconds.

This Python draws any record's picture at any tick. It uses Pillow, built with WebP support, and does the arithmetic
of "Drawing a recipe at a tick" one pixel at a time, for clarity rather than speed. On the records it was tried on it
gives the same pixels as `Compositor`.

```python
import io, json
from PIL import Image

def open_stills(artifact):
    with open(f"{artifact}/stills.json", encoding="utf-8") as f:
        table = json.load(f)                            # [[offset, length, width, height], ...]
    pak = open(f"{artifact}/stills.pak", "rb")
    def still(i):
        offset, length, width, height = table[i]
        pak.seek(offset)
        picture = Image.open(io.BytesIO(pak.read(length))).convert("RGBA")
        assert picture.size == (width, height)
        return picture
    return still

def still_at(layer, tick):
    t = tick % sum(layer["d"])                          # Python's % is never negative here
    for f, d in zip(layer["f"], layer["d"]):
        t -= d
        if t < 0:
            return f

def draw(image, tick, still):
    w, h = image["w"], image["h"]
    canvas = [[0, 0, 0, 0] for _ in range(w * h)]       # straight RGBA
    for i, layer in enumerate(image["layers"]):
        picture = still(still_at(layer, tick))
        pixels = picture.load()
        for y in range(picture.height):
            for x in range(picture.width):
                r, g, b, a = pixels[x, y]
                below = canvas[(layer["y"] + y) * w + layer["x"] + x]
                if i == 0:
                    below[:] = [r, g, b, a]             # layer 0 is copied, alpha included
                elif a == 255:
                    below[0:3] = [r, g, b]              # the canvas keeps its alpha
                elif a > 0:
                    keep = 255 - a
                    below[0] = (r * a + below[0] * keep + 127) // 255
                    below[1] = (g * a + below[1] * keep + 127) // 255
                    below[2] = (b * a + below[2] * keep + 127) // 255
    for p in canvas:
        if p[3] == 0:
            p[:] = [0, 0, 0, 0]
    out = Image.new("RGBA", (w, h))
    out.putdata([tuple(p) for p in canvas])
    return out

# still = open_stills("dumper/build/dumps/latest")
# draw(record["image"], 30, still).save("stirling_tar.png")
```
