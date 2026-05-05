package cn.wdidada.galaxymall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.junit.jupiter.api.Test;

class SeataTccAnnotationTest {

    @Test
    void createOrderCarriesGlobalTransactionalAnnotation() throws Exception {
        Method method = SeataTccOrderService.class.getMethod("createOrder", cn.wdidada.galaxymall.common.api.CreateOrderRequest.class);
        GlobalTransactional annotation = method.getAnnotation(GlobalTransactional.class);

        assertNotNull(annotation);
        assertEquals("galaxy-order-tcc", annotation.name());
    }
}
