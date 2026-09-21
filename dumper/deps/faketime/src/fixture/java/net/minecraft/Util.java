package net.minecraft;

/** Minecraft's Util, as far as the agent is concerned: getMillis under its SRG name, built on nanoTime. */
public class Util {
    public static long m_137550_() {
        return m_137569_() / 1_000_000L;
    }

    public static long m_137569_() {
        return System.nanoTime();
    }
}
