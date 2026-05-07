package cn.wdidada.galaxymall.common.client;

public final class GalaxyServiceNames {

    public static final String GATEWAY = "galaxy-mall-gateway";
    public static final String USER = "galaxy-mall-user";
    public static final String PRODUCT = "galaxy-mall-product";
    public static final String STOCK = "galaxy-mall-stock";
    public static final String ORDER = "galaxy-mall-order";
    public static final String PAYMENT = "galaxy-mall-payment";
    public static final String SECKILL = "galaxy-mall-seckill";

    public static final String USER_LB_URI = "lb://" + USER;
    public static final String PRODUCT_LB_URI = "lb://" + PRODUCT;
    public static final String STOCK_LB_URI = "lb://" + STOCK;
    public static final String ORDER_LB_URI = "lb://" + ORDER;
    public static final String PAYMENT_LB_URI = "lb://" + PAYMENT;
    public static final String SECKILL_LB_URI = "lb://" + SECKILL;

    private GalaxyServiceNames() {
    }
}
