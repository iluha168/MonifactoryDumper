Because we render an insane amount of animated images, we need comparably insane compression.

I bet we can profit on the fact that all images are minecraft-y (pixel art), and change only in small sections between frames.

## What is here

- `WebpEncoder` turns frames into lossless WebP through libwebp, which arrives inside
  `com.github.usefulness:webp-imageio` as an ordinary dependency. Nothing is built or downloaded by hand.
  A still comes out as a bare VP8L file at `cwebp -z 9` settings (lossless, quality 100, method 6), plus `exact`,
  so even the colour under alpha 0 decodes back unchanged.
- `encode(List<Frame>, frameMillis)` is the one entry point the renderer needs. Frames that never change give the
  still. Frames that do change give a lossless animated WebP that loops forever: `AnimatedWebp`, the Java RIFF muxer
  ported unchanged from `ignored/enc/src/AnimWebp.java` (changed-pixel crops, two candidates per frame, identical
  frames merged into longer durations, the 8 Mpx effort gate of PLAN section 4).
- `FramePolicy` decides which frames of an animated recipe are stored (PLAN section 4's Policy B): the strict period
  if it closes within 400 frames, otherwise the first 40.
- `PakWriter` and `PakReader` handle `images.pak`: payloads back to back, no header. Each recipe record carries
  its own `offset` and `bytes` (PLAN section 6).
- `layered` is artifact format 2 (`ignored/layers/DESIGN.md`): `LayeredImage`, `Layer` and `Timeline` are a
  record's `"image"` object and write it; `StillWriter` dedupes stills by `StillHash`, encodes them on the caller's
  executor and writes `stills.pak` in id order plus `stills.json` (`StillTable`); `Compositor` draws an image at a
  tick. Reading the JSON back is `dumper/compare`'s job, with the Gson it already has.

`./gradlew :dumper:deps:imgencoder:test` decodes every test picture back through the fork's reader and, where it is
installed, through `dwebp`, and fails on any pixel that differs.

With `-Pmonifactory.enc.corpus=<dir holding work/ and work2/>` (the prototype's `ignored/enc`), the tests also encode
the prototype's 41 test sets, require its exact bytes, and leave the port's files in `build/enc-corpus` for
`ignored/enc/verify.py`.
