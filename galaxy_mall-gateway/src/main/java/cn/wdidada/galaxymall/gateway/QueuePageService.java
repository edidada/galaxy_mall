package cn.wdidada.galaxymall.gateway;

public final class QueuePageService {

    public String build(String reason) {
        return "<html><body><h1>Galaxy Mall Queue</h1><p>" + reason + "</p><p>retry after 30s</p></body></html>";
    }
}
