package cn.wdidada.galaxymall.common.api;

import java.io.Serial;
import java.io.Serializable;

public final class ProductView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String productId;
    private String name;
    private int priceCent;
    private boolean seckillProduct;
    private String staticPage;

    public ProductView() {
    }

    public ProductView(String productId, String name, int priceCent, boolean seckillProduct, String staticPage) {
        this.productId = productId;
        this.name = name;
        this.priceCent = priceCent;
        this.seckillProduct = seckillProduct;
        this.staticPage = staticPage;
    }

    public String productId() {
        return productId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String name() {
        return name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int priceCent() {
        return priceCent;
    }

    public int getPriceCent() {
        return priceCent;
    }

    public void setPriceCent(int priceCent) {
        this.priceCent = priceCent;
    }

    public boolean seckillProduct() {
        return seckillProduct;
    }

    public boolean isSeckillProduct() {
        return seckillProduct;
    }

    public void setSeckillProduct(boolean seckillProduct) {
        this.seckillProduct = seckillProduct;
    }

    public String staticPage() {
        return staticPage;
    }

    public String getStaticPage() {
        return staticPage;
    }

    public void setStaticPage(String staticPage) {
        this.staticPage = staticPage;
    }
}
