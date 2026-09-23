package faketime.fixture;

import net.minecraft.Util;

public class Clocks {
    public static long wall() {
        return System.currentTimeMillis();
    }

    public static long util() {
        return Util.m_137550_();
    }

    public static long nano() {
        return System.nanoTime();
    }
}
