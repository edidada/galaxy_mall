package cn.wdidada.galaxymall.product;

import java.util.List;

/**
 * 图文商品播种数据。
 *
 * <p>图片全部指向前端静态目录 {@code front/public/images/}，商品服务本身不产图；
 * 若换到集群部署，图片随前端镜像一起发布，后端只给路径。
 */
final class ProductShowcaseSeeds {

    private ProductShowcaseSeeds() {
    }

    static void registerInto(ProductShowcaseCatalog catalog) {
        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-iphone",
                "iPhone Galaxy Edition 17 Pro",
                "原色钛金属 · 三摄 4800 万 · A19 Pro 芯片 · 支持以旧换新",
                "手机通讯",
                899900, 999900,
                "/images/p-iphone.png",
                List.of("/images/p-iphone.png", "/images/p-iphone-alt.png", "/images/g-phone.png"),
                List.of("自营", "12期免息", "以旧换新", "顺丰当日达"),
                List.of(new ProductShowcaseCatalog.SpecGroup("颜色", List.of(
                        new ProductShowcaseCatalog.SpecOption("原色钛金属", 0),
                        new ProductShowcaseCatalog.SpecOption("黑色钛金属", 0),
                        new ProductShowcaseCatalog.SpecOption("白色钛金属", 0))),
                        new ProductShowcaseCatalog.SpecGroup("内存容量", List.of(
                                new ProductShowcaseCatalog.SpecOption("256GB", 0),
                                new ProductShowcaseCatalog.SpecOption("512GB", 150000),
                                new ProductShowcaseCatalog.SpecOption("1TB", 320000))),
                        new ProductShowcaseCatalog.SpecGroup("版本", List.of(
                                ProductShowcaseCatalog.SpecOption.of("全网通 国行"),
                                new ProductShowcaseCatalog.SpecOption("港版", -40000)))),
                4.8, 126800,
                List.of("A19 Pro 六核，GPU 光追", "4800 万主摄 + 5 倍长焦", "钛金属中框，IP68"),
                detail("iPhone Galaxy Edition 17 Pro",
                        "全新钛金属一体成型中框，重量比上代轻 14g，握持更贴手。A19 Pro 芯片配合 12GB 运存，"
                                + "视频剪辑与端侧推理都不掉帧。",
                        "影像部分为 4800 万主摄 + 4800 万超广角 + 4800 万 5 倍长焦三摄，"
                                + "支持 4K 120fps 杜比视界与空间视频录制。",
                        "/images/p-iphone.png", "/images/g-phone.png"),
                "Galaxy Mall 手机自营旗舰店",
                "正品保障 · 全国联修 1 年 · 7 天无理由 · 运费险",
                "今日下单，预计明日 18:00 前送达",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-mouse",
                "Nebula 无线蓝牙鼠标",
                "三模连接 · 8K 回报率 · 人体工学 · Type-C 快充",
                "电脑办公",
                9900, 15900,
                "/images/p-mouse.png",
                List.of("/images/p-mouse.png", "/images/g-office.png"),
                List.of("自营", "百亿补贴", "30 天只换不修"),
                List.of(new ProductShowcaseCatalog.SpecGroup("颜色", List.of(
                        ProductShowcaseCatalog.SpecOption.of("曜石黑"),
                        ProductShowcaseCatalog.SpecOption.of("云雾白"),
                        new ProductShowcaseCatalog.SpecOption("星银", 600))),
                        new ProductShowcaseCatalog.SpecGroup("连接方式", List.of(
                                ProductShowcaseCatalog.SpecOption.of("2.4G 无线"),
                                new ProductShowcaseCatalog.SpecOption("蓝牙 5.4", 1000),
                                new ProductShowcaseCatalog.SpecOption("三模", 2000)))),
                4.7, 38400,
                List.of("PAW3950 传感器，26000 DPI", "8000Hz 无线回报率", "单电 70 小时续航"),
                detail("Nebula 无线蓝牙鼠标",
                        "轻量化 58g 机身，磨砂上盖不沾指纹；侧裙为硅胶防滑，长时间握持不累。",
                        "支持 2.4G、蓝牙、Type-C 有线三种连接，可在两台设备之间一键切换。",
                        "/images/p-mouse.png", "/images/g-office.png"),
                "Galaxy Mall 数码自营专营店",
                "正品保障 · 1 年质保 · 7 天无理由",
                "现货，24 小时内发出",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-seckill",
                "Meteor 秒杀 · 便携磁吸充电宝",
                "10000mAh · 磁吸无线充 · 秒杀专场商品",
                "秒杀专场",
                19900, 39900,
                "/images/p-seckill.png",
                List.of("/images/p-seckill.png", "/images/g-deal.png"),
                List.of("秒杀", "限量 500 件", "拍完恢复原价"),
                List.of(new ProductShowcaseCatalog.SpecGroup("颜色", List.of(
                        ProductShowcaseCatalog.SpecOption.of("深空灰"),
                        ProductShowcaseCatalog.SpecOption.of("极地白")))),
                4.5, 9200,
                List.of("15W 磁吸无线充", "20W 有线双向快充", "通过 3C 认证可登机"),
                detail("Meteor 便携磁吸充电宝",
                        "吸附即充，内置 10000mAh 电芯，可为 iPhone 补电约 2 次。",
                        "秒杀专场商品，库存与价格随时变动，下单前请确认页面价格。",
                        "/images/p-seckill.png", "/images/g-deal.png"),
                "Galaxy Mall 秒杀频道",
                "秒杀商品不支持 7 天无理由，质量问题 15 天包退",
                "秒杀订单 48 小时内发货",
                true
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-laptop",
                "Aurora Book 14 轻薄本",
                "Ultra 7 处理器 · 2.8K 120Hz 屏 · 32G+1T",
                "电脑办公",
                649900, 729900,
                "/images/p-laptop.png",
                List.of("/images/p-laptop.png", "/images/g-office.png"),
                List.of("自营", "24 期免息", "学生优惠"),
                List.of(new ProductShowcaseCatalog.SpecGroup("内存硬盘", List.of(
                        ProductShowcaseCatalog.SpecOption.of("32G+1TB"),
                        new ProductShowcaseCatalog.SpecOption("32G+2TB", 120000),
                        new ProductShowcaseCatalog.SpecOption("64G+2TB", 320000))),
                        new ProductShowcaseCatalog.SpecGroup("颜色", List.of(
                                ProductShowcaseCatalog.SpecOption.of("皓月银"),
                                new ProductShowcaseCatalog.SpecOption("曜石黑", 1000)))),
                4.6, 21500,
                List.of("2.8K 120Hz 触控屏", "1.29kg 全金属机身", "720MHz NPU 算力"),
                detail("Aurora Book 14 轻薄本",
                        "14 英寸 2.8K 屏，100% DCI-P3 色域，支持 10 点触控与护眼调光。",
                        "双风扇双热管，性能释放 65W；1.29kg 机身配 75Wh 电池，办公续航约 14 小时。",
                        "/images/p-laptop.png", "/images/g-office.png"),
                "Galaxy Mall 电脑自营旗舰店",
                "正品保障 · 2 年整机质保 · 7 天无理由",
                "现货，今日 16:00 前下单当日发出",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-monitor",
                "Halo 27 4K 专业显示器",
                "27 英寸 4K 144Hz · Mini-LED · 出厂校色",
                "电脑办公",
                219900, 279900,
                "/images/p-monitor.png",
                List.of("/images/p-monitor.png", "/images/g-office.png"),
                List.of("自营", "3 年质保", "漏光包换"),
                List.of(new ProductShowcaseCatalog.SpecGroup("面板", List.of(
                        ProductShowcaseCatalog.SpecOption.of("Mini-LED 1152 分区"),
                        new ProductShowcaseCatalog.SpecOption("Mini-LED 2304 分区", 50000)))),
                4.7, 8600,
                List.of("ΔE<1 出厂校色", "HDR1000 峰值亮度", "Type-C 90W 反向供电"),
                detail("Halo 27 4K 专业显示器",
                        "3840×2160 分辨率，144Hz 刷新率，设计剪辑与游戏兼顾。",
                        "支架支持升降、旋转、俯仰，VESA 100×100 可挂壁。",
                        "/images/p-monitor.png", "/images/g-office.png"),
                "Galaxy Mall 电脑自营旗舰店",
                "正品保障 · 3 年质保 · 漏光包换",
                "现货，24 小时内发出",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-earphone",
                "Pulse 降噪耳机 Pro",
                "-48dB 主动降噪 · 空间音频 · 40 小时续航",
                "数码配件",
                129900, 159900,
                "/images/p-earphone.png",
                List.of("/images/p-earphone.png", "/images/g-phone.png"),
                List.of("自营", "百亿补贴", "以旧换新"),
                List.of(new ProductShowcaseCatalog.SpecGroup("颜色", List.of(
                        ProductShowcaseCatalog.SpecOption.of("静谧黑"),
                        ProductShowcaseCatalog.SpecOption.of("云海白"),
                        new ProductShowcaseCatalog.SpecOption("暮光紫", 2000)))),
                4.8, 56200,
                List.of("双馈降噪 -48dB", "LDAC + 空间音频", "单耳 4.9g"),
                detail("Pulse 降噪耳机 Pro",
                        "六麦克风阵列，地铁与飞机客舱实测降噪深度 -48dB。",
                        "开盖即连，支持多点连接；充电 10 分钟可听 4 小时。",
                        "/images/p-earphone.png", "/images/g-phone.png"),
                "Galaxy Mall 影音自营专营店",
                "正品保障 · 1 年质保 · 7 天无理由",
                "现货，今日发出",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-watch",
                "Orbit 智能手表 3",
                "蓝宝石镜面 · 心电图 · 14 天续航",
                "智能穿戴",
                159900, 189900,
                "/images/p-watch.png",
                List.of("/images/p-watch.png", "/images/g-phone.png"),
                List.of("自营", "12 期免息", "运动模式 120+"),
                List.of(new ProductShowcaseCatalog.SpecGroup("表壳", List.of(
                        ProductShowcaseCatalog.SpecOption.of("42mm 铝合金"),
                        new ProductShowcaseCatalog.SpecOption("46mm 铝合金", 8000),
                        new ProductShowcaseCatalog.SpecOption("46mm 钛金属", 30000))),
                        new ProductShowcaseCatalog.SpecGroup("表带", List.of(
                                ProductShowcaseCatalog.SpecOption.of("硅胶运动款"),
                                new ProductShowcaseCatalog.SpecOption("尼龙回环款", 1500),
                                new ProductShowcaseCatalog.SpecOption("钢带 Milanese", 6000)))),
                4.6, 33100,
                List.of("血氧/心率/心电图", "5ATM 游泳级防水", "独立 GPS 双频"),
                detail("Orbit 智能手表 3",
                        "1.9 英寸 LTPO 常亮屏，峰值亮度 3000nit，阳光下可读。",
                        "支持睡眠呼吸暂停提醒与房颤提示，数据本地存储不上传。",
                        "/images/p-watch.png", "/images/g-phone.png"),
                "Galaxy Mall 穿戴自营专营店",
                "正品保障 · 1 年质保 · 7 天无理由",
                "现货，24 小时内发出",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-fridge",
                "Polar 十字对开门冰箱",
                "501L 风冷无霜 · 一级能效 · 母婴独立空间",
                "家用电器",
                379900, 459900,
                "/images/p-fridge.png",
                List.of("/images/p-fridge.png", "/images/g-home.png"),
                List.of("自营", "送装一体", "以旧换新补贴 800"),
                List.of(new ProductShowcaseCatalog.SpecGroup("容积", List.of(
                        ProductShowcaseCatalog.SpecOption.of("451L"),
                        new ProductShowcaseCatalog.SpecOption("501L", 30000),
                        new ProductShowcaseCatalog.SpecOption("561L", 80000))),
                        new ProductShowcaseCatalog.SpecGroup("门体颜色", List.of(
                                ProductShowcaseCatalog.SpecOption.of("星岩灰"),
                                new ProductShowcaseCatalog.SpecOption("釉白色", 4000)))),
                4.7, 12400,
                List.of("干湿分储，母婴独立变温", "-32℃ 深冷速冻", "一级能效 0.89 度/天"),
                detail("Polar 十字对开门冰箱",
                        "四门十字对开，上层冷藏、下层冷冻，独立双循环不串味。",
                        "风冷无霜，压缩机 10 年包修；机身厚度 650mm 可嵌入橱柜。",
                        "/images/p-fridge.png", "/images/g-home.png"),
                "Galaxy Mall 家电自营大店",
                "送装一体 · 3 年整机质保 · 压缩机 10 年包修",
                "预约配送，最快 48 小时上门",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-jacket",
                "Vector 三合一冲锋衣",
                "20000mm 防水 · 抓绒内胆可拆 · 男女同款",
                "服饰运动",
                69900, 109900,
                "/images/p-jacket.png",
                List.of("/images/p-jacket.png", "/images/g-home.png"),
                List.of("自营", "满 199 减 40", "7 天无理由"),
                List.of(new ProductShowcaseCatalog.SpecGroup("颜色", List.of(
                        ProductShowcaseCatalog.SpecOption.of("曜石黑"),
                        ProductShowcaseCatalog.SpecOption.of("松林绿"),
                        new ProductShowcaseCatalog.SpecOption("落日橙", 2000))),
                        new ProductShowcaseCatalog.SpecGroup("尺码", List.of(
                                ProductShowcaseCatalog.SpecOption.of("M"), ProductShowcaseCatalog.SpecOption.of("L"),
                                ProductShowcaseCatalog.SpecOption.of("XL"), ProductShowcaseCatalog.SpecOption.of("2XL"),
                                new ProductShowcaseCatalog.SpecOption("3XL", 3000)))),
                4.5, 7300,
                List.of("外层 20000mm 静水压", "内胆 300g 抓绒可单穿", "腋下透气拉链"),
                detail("Vector 三合一冲锋衣",
                        "外壳 + 抓绒内胆可自由组合，三季可穿；接缝全压胶处理。",
                        "版型偏大 1 码，日常宽松穿着建议拍小一码。",
                        "/images/p-jacket.png", "/images/g-home.png"),
                "Galaxy Mall 运动户外专营店",
                "7 天无理由 · 运费险 · 支持换码",
                "现货，48 小时内发出",
                false
        ));

        catalog.register(new ProductShowcaseCatalog.Entry(
                "sku-coffee",
                "Comet 云南精品咖啡豆",
                "保山海拔 1600m · 中浅烘焙 · 新鲜现烘",
                "食品生鲜",
                12800, 16800,
                "/images/p-coffee.png",
                List.of("/images/p-coffee.png", "/images/g-home.png"),
                List.of("自营", "第二件半价", "产地直发"),
                List.of(new ProductShowcaseCatalog.SpecGroup("规格", List.of(
                        ProductShowcaseCatalog.SpecOption.of("250g 单袋"),
                        new ProductShowcaseCatalog.SpecOption("500g 家庭装", 7000),
                        new ProductShowcaseCatalog.SpecOption("250g×3 组合", 18000))),
                        new ProductShowcaseCatalog.SpecGroup("研磨度", List.of(
                                ProductShowcaseCatalog.SpecOption.of("整豆"),
                                ProductShowcaseCatalog.SpecOption.of("手冲中等"),
                                ProductShowcaseCatalog.SpecOption.of("意式细粉")))),
                4.9, 4100,
                List.of("SCA 杯测 84.5 分", "烘焙后 48 小时内发货", "单向排气阀养豆袋"),
                detail("Comet 云南精品咖啡豆",
                        "保山高海拔庄园豆，水洗处理，风味为红糖、红苹果与柑橘尾韵。",
                        "中浅烘焙，适合手冲与美式滴滤；意式萃取建议把研磨度调细一档。",
                        "/images/p-coffee.png", "/images/g-home.png"),
                "Galaxy Mall 生鲜自营超市",
                "冷链配送 · 坏单包赔",
                "现烘现发，预计 3 天内送达",
                false
        ));
    }

    private static String detail(String title, String paragraphOne, String paragraphTwo, String image, String banner) {
        return """
                <div class="gm-detail">
                  <h2>%s</h2>
                  <img src="%s" alt="%s" />
                  <p>%s</p>
                  <img src="%s" alt="%s 场景图" />
                  <p>%s</p>
                  <p class="gm-detail-note">以上图文由商品服务 /api/products/{productId}/detail 下发，前端直接渲染。</p>
                </div>
                """.formatted(title, image, title, paragraphOne, banner, title, paragraphTwo);
    }
}
