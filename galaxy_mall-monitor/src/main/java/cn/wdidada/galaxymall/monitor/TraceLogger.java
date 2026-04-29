package cn.wdidada.galaxymall.monitor;

import cn.wdidada.galaxymall.common.TraceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class TraceLogger {

    private final List<String> logs = new CopyOnWriteArrayList<>();

    public void log(String service, String action, String detail) {
        logs.add(Instant.now() + " [" + TraceContext.getOrCreate() + "] " + service + " " + action + " " + detail);
    }

    public List<String> snapshot() {
        return new ArrayList<>(logs);
    }
}
