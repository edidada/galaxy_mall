package cn.wdidada.galaxymall.gateway;

public final class QueuePageService {

    public String build(String reason, String queueToken, int ahead, long readyAtMillis) {
        return "<html><body><h1>Galaxy Mall Queue</h1><p>" + reason + "</p><p>queueToken=" + queueToken
                + "</p><p>ahead=" + ahead + "</p><p>readyAtMillis=" + readyAtMillis + "</p></body></html>";
    }
}
