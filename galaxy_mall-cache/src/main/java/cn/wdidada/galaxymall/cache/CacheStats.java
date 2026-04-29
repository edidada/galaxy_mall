package cn.wdidada.galaxymall.cache;

public record CacheStats(long l1Hits, long l2Hits, long l3Hits, long misses) {
}
