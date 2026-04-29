package cn.wdidada.galaxymall.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LocalBinlogRepository {

    private final List<String> events = new CopyOnWriteArrayList<>();

    public void append(String event) {
        events.add(event);
    }

    public List<String> snapshot() {
        return new ArrayList<>(events);
    }
}
