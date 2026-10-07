package com.example.oms.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * 商品 SKU。
 *
 * <p>一行 = 一个款号 + 颜色 + 尺码 组合，sku 默认按 "款号-颜色-尺码" 生成。
 * status: ON 上架 / OFF 下架</p>
 *
 * <p>末尾 12 个字段是 44 号迁移加的国际申报属性（HS 编码 / 中英文报关品名 /
 * 原产国 / 危险品与包装说明 / 单件重量体积）。它们挂在商品档案上是**同一条链路**：
 * 报关申报要素是「货的属性」，同一 SKU 在不同出口单里应当一致；
 * 让人手在每张报关单的明细里重填既慢又容易填错。
 * 明细行仍然存快照（oms_export_order_items）——商品档案改了也不该改已报关的历史单据。</p>
 */
public record Product(
        Long id,
        String styleNo,
        String name,
        String category,
        String season,
        String color,
        String size,
        String sku,
        BigDecimal price,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String hsCode,
        String customsName,
        String customsNameEn,
        String originCountry,
        Integer isDangerous,
        String unNumber,
        String dgClass,
        String packingInstruction,
        BigDecimal batteryWattHours,
        BigDecimal netWeight,
        BigDecimal grossWeight,
        BigDecimal volumeCbm) {

    public static final RowMapper<Product> ROW_MAPPER = (rs, i) -> new Product(
            rs.getLong("id"),
            rs.getString("style_no"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("season"),
            rs.getString("color"),
            rs.getString("size"),
            rs.getString("sku"),
            rs.getBigDecimal("price"),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("hs_code"),
            rs.getString("customs_name"),
            rs.getString("customs_name_en"),
            rs.getString("origin_country"),
            rs.getObject("is_dangerous", Integer.class),
            rs.getString("un_number"),
            rs.getString("dg_class"),
            rs.getString("packing_instruction"),
            rs.getBigDecimal("battery_watt_hours"),
            rs.getBigDecimal("net_weight"),
            rs.getBigDecimal("gross_weight"),
            rs.getBigDecimal("volume_cbm"));
}
