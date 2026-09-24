Because we render an insane amount of animated images, we need comparably insane compression.

I bet we can profit on the fact that all images are minecraft-y (pixel art), and change only in small sections between frames.

## What is here

- `WebpEncoder` turns frames into lossless WebP through libwebp, which arrives inside
  `com.github.usefulness:webp-imageio` as an ordinary dependency. Nothing is built or downloaded by hand.
  A still comes out as a bare VP8L file at `cwebp -z 9` settings (lossless, quality 100, method 6), plus `exact`,
  so even the colour under alpha 0 decodes back unchanged.
- `encode(List<Frame>, frameMillis)` turns a frame sequence into one file. Frames that never change give the
  still. Frames that do change give a lossless animated WebP that loops forever: `AnimatedWebp`, a Java RIFF muxer
  (changed-pixel crops, two candidates per frame, identical frames merged into longer durations, and above 8 Mpx of
  frames only the alpha-punched one). The layered renderer stores stills only, so it calls `encode(Frame)`.
- `FramePolicy` decides which frames of an animated layer are stored: the strict period if it closes within 400
  frames, otherwise the first 40.
- `PakWriter` and `PakReader` handle a pack file such as `stills.pak`: payloads back to back, no header. Where each
  payload is (a `PakEntry`) is kept outside the pack, in `stills.json`.
- `layered` holds the types of the artifact's pictures: `LayeredImage`, `Layer` and `Timeline` are a record's
  `"image"` object and write it; `StillWriter` dedupes stills by `StillHash`, encodes them on the caller's executor
  and writes `stills.pak` in id order plus `stills.json` (`StillTable`); `Compositor` draws an image at a tick. Reading
  the JSON back is `dumper/compare`'s job, with the Gson it already has. [dumper/FORMAT.md](../../FORMAT.md) is the
  format itself, for readers in any language.

`./gradlew :dumper:deps:imgencoder:test` decodes every test picture back through the fork's reader and, where it is
installed, through `dwebp`, and fails on any pixel that differs.

`-Pmonifactory.enc.corpus=<dir>` adds a regression test of the muxer against recorded sets of recipe animation frames,
which are not in the repository. The directory holds `work/` and `work2/`, each with one directory per set: `src/`,
the frames as PNGs in name order, 50 ms apart (31 ms for a set named `anim_item`, 100 ms for `item_stack_probe`), and
`javamux_uf011.webp`, the bytes the muxer must write for them with both candidates on every frame. The test also
writes each set through the public entry point, and leaves every file it wrote in `build/enc-corpus` with an
`index.tsv` of sizes, for decoding with another tool. Without the property the test is skipped.
