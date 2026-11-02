package com.rent.utility.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.rent.utility.data.Bill;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 账单数据导出为 CSV（UTF-8 带 BOM，Excel/WPS 可直接打开中文不乱码）。
 * 列：月份,房间,租客,水起码,水止码,水用量,水费,
 *    燃气起码,燃气止码,燃气用量,燃气费,
 *    电表1起码,电表1止码,电表1用量,电表1费用,电表2起码,电表2止码,电表2用量,电表2费用,
 *    电费,其他费用,其他费用明细,合计,备注
 * 水/燃气/电表都支持多块：固定列导出第 1 块起止码；第 2 块及以上的费用并入
 * “其他费用明细”列（按 表名=金额 记录，避免列错位）。
 */
public class CsvExporter {

    private static final String HEADER =
            "月份,房间,租客,水起码,水止码,水用量,水费," +
            "燃气起码,燃气止码,燃气用量,燃气费," +
            "电表1起码,电表1止码,电表1用量,电表1费用,电表2起码,电表2止码,电表2用量,电表2费用," +
            "电费,其他费用,其他费用明细,合计,备注";

    public static String buildCsv(List<Bill> bills) {
        StringBuilder sb = new StringBuilder();
        sb.append((char) 0xFEFF); // UTF-8 BOM，保证 Excel 打开中文不乱码
        sb.append(HEADER).append("\r\n");
        for (Bill b : bills) {
            StringBuilder detail = new StringBuilder();
            for (Bill.ExtraAmount e : b.extras) {
                if (detail.length() > 0) detail.append(';');
                detail.append(e.name).append('=').append(BillCalculator.money(e.amount));
            }
            Bill.Meter wm1 = b.waterMeters != null && b.waterMeters.size() > 0 ? b.waterMeters.get(0) : null;
            Bill.Meter gm1 = b.gasMeters != null && b.gasMeters.size() > 0 ? b.gasMeters.get(0) : null;
            Bill.Meter m1 = b.meters != null && b.meters.size() > 0 ? b.meters.get(0) : null;
            Bill.Meter m2 = b.meters != null && b.meters.size() > 1 ? b.meters.get(1) : null;
            // 第 2 块及以上水表/燃气表、第 3 块及以上电表 → 并入明细列
            if (b.waterMeters != null) {
                for (int i = 1; i < b.waterMeters.size(); i++) {
                    Bill.Meter m = b.waterMeters.get(i);
                    if (m == null) continue;
                    if (detail.length() > 0) detail.append(';');
                    detail.append(m.label).append('=').append(BillCalculator.money(m.fee));
                }
            }
            if (b.gasMeters != null) {
                for (int i = 1; i < b.gasMeters.size(); i++) {
                    Bill.Meter m = b.gasMeters.get(i);
                    if (m == null) continue;
                    if (detail.length() > 0) detail.append(';');
                    detail.append(m.label).append('=').append(BillCalculator.money(m.fee));
                }
            }
            if (b.meters != null) {
                for (int i = 2; i < b.meters.size(); i++) {
                    Bill.Meter m = b.meters.get(i);
                    if (m == null) continue;
                    if (detail.length() > 0) detail.append(';');
                    detail.append(m.label).append('=').append(BillCalculator.money(m.fee));
                }
            }
            sb.append(cell(b.month)).append(',')
                    .append(cell(b.room)).append(',')
                    .append(cell(b.tenant)).append(',')
                    .append(BillCalculator.usage(wm1 != null ? wm1.start : b.waterStart)).append(',')
                    .append(BillCalculator.usage(wm1 != null ? wm1.end : b.waterEnd)).append(',')
                    .append(BillCalculator.usage(wm1 != null ? wm1.usage : b.waterUsage)).append(',')
                    .append(BillCalculator.money(b.waterFee)).append(',')
                    .append(BillCalculator.usage(gm1 != null ? gm1.start : b.gasStart)).append(',')
                    .append(BillCalculator.usage(gm1 != null ? gm1.end : b.gasEnd)).append(',')
                    .append(BillCalculator.usage(gm1 != null ? gm1.usage : b.gasUsage)).append(',')
                    .append(BillCalculator.money(b.gasFee)).append(',')
                    .append(BillCalculator.usage(m1 != null ? m1.start : 0)).append(',')
                    .append(BillCalculator.usage(m1 != null ? m1.end : 0)).append(',')
                    .append(BillCalculator.usage(m1 != null ? m1.usage : 0)).append(',')
                    .append(BillCalculator.money(m1 != null ? m1.fee : 0)).append(',')
                    .append(BillCalculator.usage(m2 != null ? m2.start : 0)).append(',')
                    .append(BillCalculator.usage(m2 != null ? m2.end : 0)).append(',')
                    .append(BillCalculator.usage(m2 != null ? m2.usage : 0)).append(',')
                    .append(BillCalculator.money(m2 != null ? m2.fee : 0)).append(',')
                    .append(BillCalculator.money(b.elecFee)).append(',')
                    .append(BillCalculator.money(b.otherFee())).append(',')
                    .append(cell(detail.toString())).append(',')
                    .append(BillCalculator.money(b.total())).append(',')
                    .append(cell(b.note))
                    .append("\r\n");
        }
        return sb.toString();
    }

    /** 保存 CSV 到系统 Downloads/租房水电记账 目录（API 29+ 无需权限） */
    public static boolean saveToDownloads(Context context, String fileName, String content) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/房东小助手");
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                OutputStream os = context.getContentResolver().openOutputStream(uri);
                if (os == null) return false;
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.close();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "房东小助手");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 写入缓存文件（供分享使用） */
    public static File writeCacheFile(Context context, String fileName, String content) throws Exception {
        File dir = new File(context.getCacheDir(), "export");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    /** 通过系统分享面板把 CSV 分享到微信等；失败返回 false，不崩溃 */
    public static boolean shareFile(Context context, File f) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", f);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(Intent.createChooser(intent, "分享账单数据"));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void toastSaved(Context context, boolean ok) {
        Toast.makeText(context,
                ok ? "CSV 已保存到 Download/房东小助手 目录" : "保存失败，请检查存储权限",
                Toast.LENGTH_LONG).show();
    }

    private static String cell(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
