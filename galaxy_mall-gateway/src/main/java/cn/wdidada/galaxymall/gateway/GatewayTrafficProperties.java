package cn.wdidada.galaxymall.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "galaxy.gateway")
public class GatewayTrafficProperties {

    private final Query query = new Query();
    private final Order order = new Order();
    private final WaitingRoom waitingRoom = new WaitingRoom();
    private final Http http = new Http();

    public Query getQuery() {
        return query;
    }

    public Order getOrder() {
        return order;
    }

    public WaitingRoom getWaitingRoom() {
        return waitingRoom;
    }

    public Http getHttp() {
        return http;
    }

    public static final class Query {

        private long tokenCapacity = 10_000L;
        private long refillPerSecond = 5_000L;

        public long getTokenCapacity() {
            return tokenCapacity;
        }

        public void setTokenCapacity(long tokenCapacity) {
            this.tokenCapacity = tokenCapacity;
        }

        public long getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(long refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }
    }

    public static final class Order {

        private long tokenCapacity = 5_000L;
        private long refillPerSecond = 2_000L;
        private double leakyBucketCapacity = 1_500D;
        private double leakyBucketPerSecond = 1_000D;
        private int userWindowLimit = 3;

        public long getTokenCapacity() {
            return tokenCapacity;
        }

        public void setTokenCapacity(long tokenCapacity) {
            this.tokenCapacity = tokenCapacity;
        }

        public long getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(long refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }

        public double getLeakyBucketCapacity() {
            return leakyBucketCapacity;
        }

        public void setLeakyBucketCapacity(double leakyBucketCapacity) {
            this.leakyBucketCapacity = leakyBucketCapacity;
        }

        public double getLeakyBucketPerSecond() {
            return leakyBucketPerSecond;
        }

        public void setLeakyBucketPerSecond(double leakyBucketPerSecond) {
            this.leakyBucketPerSecond = leakyBucketPerSecond;
        }

        public int getUserWindowLimit() {
            return userWindowLimit;
        }

        public void setUserWindowLimit(int userWindowLimit) {
            this.userWindowLimit = userWindowLimit;
        }
    }

    public static final class WaitingRoom {

        private int capacity = 1_000_000;
        private int releasePerSecond = 100_000;

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getReleasePerSecond() {
            return releasePerSecond;
        }

        public void setReleasePerSecond(int releasePerSecond) {
            this.releasePerSecond = releasePerSecond;
        }
    }

    public static final class Http {

        private int connectTimeoutMs = 1_000;
        private int readTimeoutMs = 3_000;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
