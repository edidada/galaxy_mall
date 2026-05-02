package cn.wdidada.galaxymall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import org.junit.jupiter.api.Test;

class LocalBinlogRepositoryTest {

    @Test
    void claimReleaseAndAckKeepCompensationEventConsumable() {
        LocalBinlogRepository repository = new LocalBinlogRepository();
        repository.append("ORDER_CREATED|order-1");
        repository.append("ORDER_STOCK_RELEASE_RETRY|order-2|timeout");

        LocalBinlogRepository.ClaimedEvent claimed = repository.claimFirst("ORDER_STOCK_RELEASE_RETRY|").orElseThrow();
        assertEquals("ORDER_STOCK_RELEASE_RETRY|order-2|timeout", claimed.payload());

        repository.release(claimed.id());
        LocalBinlogRepository.ClaimedEvent reclaimed = repository.claimFirst("ORDER_STOCK_RELEASE_RETRY|").orElseThrow();
        repository.ack(reclaimed.id());

        assertTrue(repository.claimFirst("ORDER_STOCK_RELEASE_RETRY|").isEmpty());
        assertEquals(1, repository.snapshot().size());
    }
}
