package com.rent.utility.data;

/**
 * 收费项目 / 收费标准。
 * type = 0 按用量计费（用量×单价）；type = 1 固定金额（每月固定）。
 * bindField：内置项目绑定字段（water=水费, gas=燃气费, elec=电表；水/燃气/电表均支持多块，共用单价），
 * 自定义项目为 null。
 */
public class FeeItem {

    public static final int TYPE_USAGE = 0;
    public static final int TYPE_FIXED = 1;

    public long id;
    public String name;
    public int type = TYPE_USAGE;
    public double unitPrice;
    public String unitLabel = "";
    public String bindField; // "water" / "gas" / "elec" / null
    public boolean enabled = true;

    public boolean isBuiltin() {
        return bindField != null;
    }

    public String typeText() {
        return type == TYPE_USAGE ? "按用量" : "固定金额";
    }
}
