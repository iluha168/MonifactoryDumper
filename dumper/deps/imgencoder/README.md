Because we render an insane amount of animated images, we need comparably insane compression.

I bet we can profit on the fact that all images are minecraft-y (pixel art), and change only in small sections between frames.

## What is here

- `WebpEncoder` turns frames into lossless WebP through libwebp, which arrives inside
  `com.github.usefulness:webp-imageio` as an ordinary dependency. Nothing is built or downloaded by hand.
  A still comes out as a bare VP8L file at `cwebp -z 9` settings (lossless, quality 100, method 6), plus `exact`,
  so even the colour under alpha 0 decodes back unchanged.
- `encode(List<Frame>, frameMillis)` is the one entry point the renderer needs. Frames that never change give the
  still. Frames that do change will give an animated WebP once the muxer lands (PLAN section 4). Until then they throw.
- `PakWriter` and `PakReader` handle `images.pak`: payloads back to back, no header. Each recipe record carries
  its own `offset` and `bytes` (PLAN section 6).

`./gradlew :dumper:deps:imgencoder:test` decodes every test picture back through the fork's reader and, where it is
installed, through `dwebp`, and fails on any pixel that differs.
