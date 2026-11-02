package com.rent.utility.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.rent.utility.R;
import com.rent.utility.data.FeeItem;
import com.rent.utility.util.BillCalculator;

import java.util.ArrayList;
import java.util.List;

/** 收费项目列表适配器（收费标准设置页） */
public class FeeItemAdapter extends RecyclerView.Adapter<FeeItemAdapter.VH> {

    public interface Callback {
        void onEdit(FeeItem item);

        void onDelete(FeeItem item);

        void onToggle(FeeItem item, boolean enabled);
    }

    private final List<FeeItem> data = new ArrayList<>();
    private final Callback cb;

    public FeeItemAdapter(Callback cb) {
        this.cb = cb;
    }

    public void setData(List<FeeItem> items) {
        data.clear();
        if (items != null) data.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fee, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        FeeItem f = data.get(position);
        h.name.setText(f.name);
        h.badge.setVisibility(f.isBuiltin() ? View.VISIBLE : View.GONE);
        String desc;
        if (f.type == FeeItem.TYPE_USAGE) {
            desc = "按用量 · " + BillCalculator.money(f.unitPrice) + " 元/" + label(f.unitLabel);
        } else {
            desc = "固定金额 · " + BillCalculator.money(f.unitPrice) + " 元/月";
        }
        h.desc.setText(desc);
        h.switchView.setChecked(f.enabled);
        h.switchView.setOnCheckedChangeListener(null);
        h.switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            f.enabled = isChecked;
            cb.onToggle(f, isChecked);
        });
        h.root.setOnClickListener(v -> cb.onEdit(f));
        h.root.setOnLongClickListener(v -> {
            cb.onDelete(f);
            return true;
        });
    }

    private String label(String s) {
        return (s == null || s.trim().isEmpty()) ? "单位" : s.trim();
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView name, desc, badge;
        final SwitchCompat switchView;

        VH(View v) {
            super(v);
            root = v;
            name = v.findViewById(R.id.fee_name);
            desc = v.findViewById(R.id.fee_desc);
            badge = v.findViewById(R.id.fee_badge);
            switchView = v.findViewById(R.id.fee_switch);
        }
    }
}
