package net.theresa.neogenesis.modules.culling.entityculling;

import java.util.Arrays;

public class ArrayOcclusionCache implements OcclusionCache {

    private final int reachX2;
    private final byte[] cache;
    private int positionKey;
    private int entry;
    private int offset;

    public ArrayOcclusionCache(int reach) {
        this.reachX2 = reach * 2;
        this.cache = new byte[this.reachX2 * this.reachX2 * this.reachX2 / 4];
    }

    public void resetCache() {
        Arrays.fill(this.cache, (byte) 0);
    }

    public void setVisible(int x, int y, int z) {
        this.positionKey = x + y * this.reachX2 + z * this.reachX2 * this.reachX2;
        this.entry = this.positionKey / 4;
        this.offset = this.positionKey % 4 * 2;
        byte[] var10000 = this.cache;
        int var10001 = this.entry;
        var10000[var10001] = (byte) (var10000[var10001] | 1 << this.offset);
    }

    public void setHidden(int x, int y, int z) {
        this.positionKey = x + y * this.reachX2 + z * this.reachX2 * this.reachX2;
        this.entry = this.positionKey / 4;
        this.offset = this.positionKey % 4 * 2;
        byte[] var10000 = this.cache;
        int var10001 = this.entry;
        var10000[var10001] = (byte) (var10000[var10001] | 1 << this.offset + 1);
    }

    public int getState(int x, int y, int z) {
        this.positionKey = x + y * this.reachX2 + z * this.reachX2 * this.reachX2;
        this.entry = this.positionKey / 4;
        this.offset = this.positionKey % 4 * 2;
        return this.cache[this.entry] >> this.offset & 3;
    }

    public void setLastVisible() {
        byte[] var10000 = this.cache;
        int var10001 = this.entry;
        var10000[var10001] = (byte) (var10000[var10001] | 1 << this.offset);
    }

    public void setLastHidden() {
        byte[] var10000 = this.cache;
        int var10001 = this.entry;
        var10000[var10001] = (byte) (var10000[var10001] | 1 << this.offset + 1);
    }

}
