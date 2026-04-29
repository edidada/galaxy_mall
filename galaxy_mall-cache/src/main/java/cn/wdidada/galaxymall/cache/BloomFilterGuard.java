package cn.wdidada.galaxymall.cache;

import java.util.BitSet;
import java.util.Collection;

public final class BloomFilterGuard {

    private final BitSet bits;
    private final int size;

    public BloomFilterGuard(int size) {
        this.size = size;
        this.bits = new BitSet(size);
    }

    public void addAll(Collection<String> values) {
        values.forEach(this::add);
    }

    public void add(String value) {
        for (int seed : new int[]{17, 31, 53}) {
            bits.set(index(value, seed));
        }
    }

    public boolean mightContain(String value) {
        for (int seed : new int[]{17, 31, 53}) {
            if (!bits.get(index(value, seed))) {
                return false;
            }
        }
        return true;
    }

    private int index(String value, int seed) {
        return Math.floorMod(value.hashCode() * seed, size);
    }
}
