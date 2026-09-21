A java agent to control the clock that animations read. The renderer freezes it at a chosen time around each draw, so every animated frame is a function of the frame index.

`FakeTime` is the API: `freeze(millis)` / `release()` around a draw, `holdAtlas(true)` once, then `tickAtlas(textureManager::tick)` once per frame. Only the freezing thread sees the fake time. Everything else in the game keeps the real clock.

`ClockAgent` is the `-javaagent`. It rewrites `System.currentTimeMillis()` call sites in every class whose module can read this one, the return value of `Util.getMillis()`, and puts a gate on `TextureManager.tick()`. There is no list of mods or classes. It prints what it patched when the game exits.
