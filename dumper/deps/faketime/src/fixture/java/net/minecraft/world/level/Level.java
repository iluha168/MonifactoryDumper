package net.minecraft.world.level;

/** Game and day time under their SRG names, each returning a field like the real one returns its level data's. */
public class Level {
    public long gameTime = 123;
    public long dayTime = 456;

    public long m_46467_() {
        return gameTime;
    }

    public long m_46468_() {
        return dayTime;
    }
}
