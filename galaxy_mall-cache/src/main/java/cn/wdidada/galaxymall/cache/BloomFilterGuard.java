package cn.wdidada.galaxymall.cache;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLongArray;

public final class BloomFilterGuard {

    private static final int HASH_FUNCTIONS = 4;

    private final AtomicLongArray words;
    private final int size;

    public BloomFilterGuard(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        this.size = size;
        this.words = new AtomicLongArray((size + Long.SIZE - 1) / Long.SIZE);
    }

    public void addAll(Collection<String> values) {
        values.forEach(this::add);
    }

    public void add(String value) {
        long hash1 = hash64(value, 0xcbf29ce484222325L);
        long hash2 = hash64(value, 0x9e3779b97f4a7c15L);
        for (int i = 0; i < HASH_FUNCTIONS; i++) {
            setBit(index(hash1, hash2, i));
        }
    }

    public boolean mightContain(String value) {
        long hash1 = hash64(value, 0xcbf29ce484222325L);
        long hash2 = hash64(value, 0x9e3779b97f4a7c15L);
        for (int i = 0; i < HASH_FUNCTIONS; i++) {
            if (!isBitSet(index(hash1, hash2, i))) {
                return false;
            }
        }
        return true;
    }

    private void setBit(int index) {
        int wordIndex = index / Long.SIZE;
        long mask = 1L << (index % Long.SIZE);
        while (true) {
            long current = words.get(wordIndex);
            long updated = current | mask;
            if (current == updated || words.compareAndSet(wordIndex, current, updated)) {
                return;
            }
        }
    }

    private boolean isBitSet(int index) {
        int wordIndex = index / Long.SIZE;
        long mask = 1L << (index % Long.SIZE);
        return (words.get(wordIndex) & mask) != 0L;
    }

    private int index(long hash1, long hash2, int round) {
        long combined = hash1 + round * hash2;
        return Math.floorMod(combined, size);
    }

    private long hash64(String value, long seed) {
        long hash = seed;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash;
    }
}
