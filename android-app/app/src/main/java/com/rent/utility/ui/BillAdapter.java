package com.rent.utility.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rent.utility.R;
import com.rent.utility.data.Bill;
import com.rent.utility.util.BillCalculator;

import java.util.ArrayList;
import java.util.List;

/** 账单列表适配器（水/电/燃气/合计 四列，其他费用与备注在底行） */
public class BillAdapter extends RecyclerView.Adapter<BillAdapter.VH> {

    public interface Callback {
        void onBillClick(Bill bill);

        void onShare(Bill bill);

        void onExport(Bill bill);
    }

    private final List<Bill> data = new ArrayList<>();
    private final Callback cb;

    public BillAdapter(Callback cb) {
        this.cb = cb;
    }

    public void setData(List<Bill> bills) {
        data.clear();
        if (bills != null) data.addAll(bills);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bill, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Bill b = data.get(position);
        if (b == null) return;
        String room = safe(b.room).trim();
        h.room.setText(room.isEmpty()
                ? safe(b.month)
                : "房间 " + room + " · " + safe(b.month));
        String tenant = b.tenant == null ? "" : b.tenant.trim();
        h.tenant.setText("租客 " + (tenant.isEmpty() ? "—" : tenant));
        h.water.setText(BillCalculator.usage(b.waterUsageTotal()) + " 吨 / " + BillCalculator.money(b.waterFee) + " 元");
        h.elec.setText(BillCalculator.usage(b.elecUsage()) + " 度 / " + BillCalculator.money(b.elecFee) + " 元");
        h.gas.setText(BillCalculator.usage(b.gasUsageTotal()) + " 方 / " + BillCalculator.money(b.gasFee) + " 元");
        h.total.setText(BillCalculator.money(b.total()) + " 元");

        String otherText = "";
        if (b.extras != null) {
            for (Bill.ExtraAmount e : b.extras) {
                if (e == null) continue;
                if (otherText.length() > 0) otherText += "  ";
                otherText += e.name + " " + BillCalculator.money(e.amount) + " 元";
            }
        }
        h.other.setText(otherText);
        if (b.note != null && !b.note.trim().isEmpty()) {
            h.note.setVisibility(View.VISIBLE);
            h.note.setText(b.note.trim());
        } else {
            h.note.setVisibility(View.GONE);
        }
        h.root.setOnClickListener(v -> cb.onBillClick(b));
        h.share.setOnClickListener(v -> cb.onShare(b));
        h.exportBtn.setOnClickListener(v -> cb.onExport(b));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView room, tenant, water, elec, gas, other, note, total;
        final ImageButton share, exportBtn;

        VH(View v) {
            super(v);
            root = v;
            room = v.findViewById(R.id.item_room);
            tenant = v.findViewById(R.id.item_tenant);
            water = v.findViewById(R.id.item_water);
            elec = v.findViewById(R.id.item_elec);
            gas = v.findViewById(R.id.item_gas);
            other = v.findViewById(R.id.item_other);
            note = v.findViewById(R.id.item_note);
            total = v.findViewById(R.id.item_total);
            share = v.findViewById(R.id.item_share);
            exportBtn = v.findViewById(R.id.item_export);
        }
    }
}
