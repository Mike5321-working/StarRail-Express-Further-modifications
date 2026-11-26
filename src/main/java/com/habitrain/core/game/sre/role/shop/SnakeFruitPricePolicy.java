package com.habitrain.core.game.sre.role.shop;

import java.util.function.IntUnaryOperator;

/** Pure rules for red-apple prices; each entry gets its own pair of random draws. */
public final class SnakeFruitPricePolicy {
    public static final int APPLE_PRICE = 150;
    public static final int DURATION_TICKS = 200;
    public static final int MAX_PRICE = 1000;
    public static final int SCRAMBLE_INTERVAL_TICKS = 2;
    public static final int QUOTE_GRACE_TICKS = 6;

    /** Stable on both sides, independent of the random draws used for the final price. */
    public static int scramblePrice(long seed, int index, int frame) {
        long value = seed + 0x9E3779B97F4A7C15L * (index + 1L) + 0xD1B54A32D192ED03L * frame;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return (int) Long.remainderUnsigned(value ^ (value >>> 31), MAX_PRICE + 1L);
    }

    private static final ThreadLocal<java.security.MessageDigest> SHA256_TL = ThreadLocal.withInitial(() -> {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    });
    private static final ThreadLocal<byte[]> FRAME_INPUT_TL = ThreadLocal.withInitial(() -> new byte[20]);

    /** Derives a one-time per-frame token from the server's private salt via SHA-256 so future frames cannot be predicted. */
    public static long frameSeed(long masterSeed, long secretSalt, int frame) {
        if (secretSalt == 0L) return masterSeed;
        java.security.MessageDigest sha256 = SHA256_TL.get();
        sha256.reset();
        byte[] input = FRAME_INPUT_TL.get();
        putLongBE(input, 0, masterSeed);
        putLongBE(input, 8, secretSalt);
        putIntBE(input, 16, frame);
        byte[] digest = sha256.digest(input);
        return getLongBE(digest, 0);
    }

    private static void putLongBE(byte[] buf, int off, long v) {
        buf[off] = (byte) (v >>> 56);
        buf[off + 1] = (byte) (v >>> 48);
        buf[off + 2] = (byte) (v >>> 40);
        buf[off + 3] = (byte) (v >>> 32);
        buf[off + 4] = (byte) (v >>> 24);
        buf[off + 5] = (byte) (v >>> 16);
        buf[off + 6] = (byte) (v >>> 8);
        buf[off + 7] = (byte) v;
    }

    private static void putIntBE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    private static long getLongBE(byte[] buf, int off) {
        return ((long) buf[off] << 56)
                | ((buf[off + 1] & 0xFFL) << 48)
                | ((buf[off + 2] & 0xFFL) << 40)
                | ((buf[off + 3] & 0xFFL) << 32)
                | ((buf[off + 4] & 0xFFL) << 24)
                | ((buf[off + 5] & 0xFFL) << 16)
                | ((buf[off + 6] & 0xFFL) << 8)
                | (buf[off + 7] & 0xFFL);
    }

    public enum Faction { GOOD, KILLER, NEUTRAL }

    private SnakeFruitPricePolicy() {}

    public static double increaseChance(Faction target, int goodAlive, int goodInitial,
                                        int killerAlive, int killerInitial) {
        if (target == Faction.NEUTRAL || goodInitial <= 0 || killerInitial <= 0) return 0.5;
        double good = Math.clamp((double) goodAlive / goodInitial, 0.0, 1.0);
        double killer = Math.clamp((double) killerAlive / killerInitial, 0.0, 1.0);
        double difference = target == Faction.KILLER ? killer - good : good - killer;
        return Math.clamp(0.5 + difference * 0.5, 0.1, 0.9);
    }

    public static int rollPrice(int normalPrice, double chance, double directionRoll,
                                IntUnaryOperator nextInt) {
        if (normalPrice < 0) return normalPrice;
        boolean increase = directionRoll < chance;
        if (normalPrice == 0) increase = true;
        if (normalPrice >= MAX_PRICE) increase = false;
        if (increase) {
            int minimum = normalPrice + 1;
            return minimum + nextInt.applyAsInt(MAX_PRICE - minimum + 1);
        }
        return nextInt.applyAsInt(Math.min(normalPrice, MAX_PRICE + 1));
    }

    public static String changeLabel(int normalPrice, int price) {
        if (normalPrice == price) return "";
        if (normalPrice <= 0) return "+∞%";
        long percent = Math.max(1, Math.round(Math.abs((double) price - normalPrice) * 100 / normalPrice));
        return (price < normalPrice ? "-" : "+") + percent + "%";
    }
}
