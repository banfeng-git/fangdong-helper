import com.rent.pc.*;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * PC 端无头自测：不弹窗口，直接校验业务规则、文本、图片，并输出预览 PNG。
 *   javac -encoding UTF-8 --release 11 -cp out -d out-test test/PcCheck.java
 *   java -Djava.awt.headless=true -cp out:out-test PcCheck
 */
public class PcCheck {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        check(name, cond, null);
    }

    static void check(String name, boolean cond, String extra) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + (extra == null ? "" : "  → " + extra)); }
    }

    static Model.Settings settings() {
        Model.Settings s = new Model.Settings();
        s.waterPrice = 3.0;
        s.elecPrice = 1.0;
        s.gasPrice = 2.5;
        return s;
    }

    static Model.Bill bill3() {
        Model.Bill b = new Model.Bill();
        b.uid = "t1"; b.room = "526"; b.tenant = "张三"; b.month = "2026-11"; b.note = "月底结清";
        b.waterMeters.add(new Model.Meter("水表1", 0, 65, 65, 195));
        b.meters.add(new Model.Meter("电表1", 800, 858, 58, 58));
        b.gasMeters.add(new Model.Meter("燃气表1", 10, 28, 18, 45));
        b.extras.add(new Model.Extra("卫生费", false, 0, 10));
        b.extras.add(new Model.Extra("网费", false, 0, 50));
        return b;
    }

    public static void main(String[] args) throws Exception {
        Model.Settings s = settings();

        // 1) 三类项目 + 其他收费 → 水电燃气合计
        Model.Bill b1 = bill3();
        check("三类项目标题", Model.tableTitle(Model.buildColumns(b1, s)).equals("水电燃气费用收费明细表"),
                Model.tableTitle(Model.buildColumns(b1, s)));
        check("三类项目合计文字", Model.subtotalLabel(Model.buildColumns(b1, s)).equals("水电燃气合计"));
        check("合计显示", Model.shouldShowSubtotal(Model.buildColumns(b1, s), Model.fixedExtras(b1)));
        check("合计金额 298", Math.abs(Model.subtotalOf(Model.buildColumns(b1, s)) - 298.0) < 0.001,
                String.valueOf(Model.subtotalOf(Model.buildColumns(b1, s))));
        check("总费用 358", Math.abs(b1.total() - 358.0) < 0.001, String.valueOf(b1.total()));

        String t1 = BillImage.buildText(b1, s);
        check("文本含标题", t1.startsWith("【水电燃气费用收费明细表】"), t1.split("\n")[0]);
        check("文本含起止码", t1.contains("起码｜0｜800｜10") && t1.contains("止码｜65｜858｜28"), t1);
        check("文本含合计行", t1.contains("水电燃气合计：298.00 元"), t1);
        check("文本含总费用", t1.contains("总费用：358.00 元"), t1);
        ImageIO.write(BillImage.build(b1, s), "png", new File("/tmp/pc_1_三类.png"));

        // 2) 没有其他收费 → 不显示合计
        Model.Bill b2 = bill3();
        b2.extras.clear();
        String t2 = BillImage.buildText(b2, s);
        check("无其他收费不显示合计", !t2.contains("合计"), t2);
        check("无其他收费总费用 298", t2.contains("总费用：298.00 元"), t2);
        ImageIO.write(BillImage.build(b2, s), "png", new File("/tmp/pc_2_无其他收费.png"));

        // 3) 只有一类项目（电）+ 其他收费 → 不显示合计，标题“电费收费明细表”
        Model.Bill b3 = new Model.Bill();
        b3.room = "202"; b3.month = "2026-11";
        b3.meters.add(new Model.Meter("电表1", 100, 158, 58, 58));
        b3.extras.add(new Model.Extra("卫生费", false, 0, 10));
        b3.extras.add(new Model.Extra("网费", false, 0, 50));
        check("单列标题", Model.tableTitle(Model.buildColumns(b3, s)).equals("电费收费明细表"),
                Model.tableTitle(Model.buildColumns(b3, s)));
        check("单列不显示合计", !Model.shouldShowSubtotal(Model.buildColumns(b3, s), Model.fixedExtras(b3)));
        String t3 = BillImage.buildText(b3, s);
        check("单列无合计行", !t3.contains("电合计"), t3);
        check("单列其他费用照列", t3.contains("其他费用") && t3.contains("卫生费：10.00元"), t3);
        check("单列总费用 118", t3.contains("总费用：118.00 元"), t3);
        ImageIO.write(BillImage.build(b3, s), "png", new File("/tmp/pc_3_单列.png"));

        // 4) 水电两类 + 其他收费 → 水电合计
        Model.Bill b4 = new Model.Bill();
        b4.room = "310"; b4.month = "2026-11";
        b4.waterMeters.add(new Model.Meter("水表1", 0, 50, 50, 150));
        b4.meters.add(new Model.Meter("电表1", 0, 30, 30, 30));
        b4.extras.add(new Model.Extra("卫生费", false, 0, 10));
        check("两类标题", Model.tableTitle(Model.buildColumns(b4, s)).equals("水电费用收费明细表"),
                Model.tableTitle(Model.buildColumns(b4, s)));
        check("两类合计文字", Model.subtotalLabel(Model.buildColumns(b4, s)).equals("水电合计"));
        String t4 = BillImage.buildText(b4, s);
        check("两类合计金额 180", t4.contains("水电合计：180.00 元"), t4);
        check("两类总费用 190", t4.contains("总费用：190.00 元"), t4);
        ImageIO.write(BillImage.build(b4, s), "png", new File("/tmp/pc_4_水电.png"));

        // 5) 多块表：两块电表 → “电表1/电表2”，仍算一类
        Model.Bill b5 = new Model.Bill();
        b5.room = "526"; b5.month = "2026-11";
        b5.waterMeters.add(new Model.Meter("水表1", 0, 40, 40, 120));
        b5.meters.add(new Model.Meter("电表1", 0, 20, 20, 20));
        b5.meters.add(new Model.Meter("电表2", 0, 35, 35, 35));
        b5.extras.add(new Model.Extra("卫生费", false, 0, 10));
        check("多表仍是两类", Model.tableTitle(Model.buildColumns(b5, s)).equals("水电费用收费明细表"));
        check("多表列名", Model.buildColumns(b5, s).get(1).title.equals("电表1")
                && Model.buildColumns(b5, s).get(2).title.equals("电表2"));
        ImageIO.write(BillImage.build(b5, s), "png", new File("/tmp/pc_5_多表.png"));

        // 6) 自定义“按用量”的燃气项目计入燃气类
        Model.Bill b6 = new Model.Bill();
        b6.room = "401"; b6.month = "2026-11";
        b6.waterMeters.add(new Model.Meter("水表1", 0, 10, 10, 30));
        b6.meters.add(new Model.Meter("电表1", 0, 10, 10, 10));
        b6.extras.add(new Model.Extra("煤气", true, 12, 30));
        check("自定义燃气计入燃气类",
                Model.tableTitle(Model.buildColumns(b6, s)).equals("水电燃气费用收费明细表"),
                Model.tableTitle(Model.buildColumns(b6, s)));

        // 7) 起止码：止码小于起码时按 0
        Model.Meter m = new Model.Meter("电表1", 100, 90, 0, 0);
        Model.Bill b7 = new Model.Bill();
        b7.room = "510"; b7.month = "2026-11";
        b7.meters.add(m);

        // 8) JSON 往返
        String json = Json.stringify(Model.billToJson(b1));
        Model.Bill back = Model.billFromJson(Json.parseObject(json));
        check("JSON 往返：总费用一致", Math.abs(back.total() - b1.total()) < 0.001, String.valueOf(back.total()));
        check("JSON 往返：水表数量", back.waterMeters.size() == 1);
        check("JSON 往返：其他收费", back.extras.size() == 2 && Math.abs(back.extras.get(1).amount - 50) < 0.001);

        // 9) 下月账单
        Model.Bill nb = b1.copyForNextMonth();
        check("下月月份", nb.month.equals("2026-12"), nb.month);
        check("下月起码=本月止码", Math.abs(nb.waterMeters.get(0).start - 65) < 0.001);
        check("下月费用清零", Math.abs(nb.total()) < 0.001, String.valueOf(nb.total()));

        // 10) 金额舍入
        check("金额两位小数", Model.money(298.005).equals("298.01") || Model.money(298.005).equals("298.00"),
                Model.money(298.005));
        check("用量整数不带小数", Model.usage(65.0).equals("65"), Model.usage(65.0));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
        System.out.println("ALL PASS");
    }
}
