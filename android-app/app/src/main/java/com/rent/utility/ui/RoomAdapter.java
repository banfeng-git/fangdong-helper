package com.rent.utility.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.rent.utility.R;
import com.rent.utility.data.Room;
import com.rent.utility.util.BillCalculator;

import java.util.ArrayList;
import java.util.List;

/** 房间列表适配器（一级导航） */
public class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.VH> {

    public interface Callback {
        void onRoomClick(Room room);

        void onRoomLongClick(Room room);
    }

    private final List<Room> data = new ArrayList<>();
    private final Callback cb;

    public RoomAdapter(Callback cb) {
        this.cb = cb;
    }

    public void setData(List<Room> rooms) {
        data.clear();
        if (rooms != null) data.addAll(rooms);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_room, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Room r = data.get(position);
        if (r == null) return;
        h.name.setText(r.name);
        h.count.setText(h.count.getContext()
                .getString(R.string.room_bill_count, r.billCount));

        String tenant = r.tenant == null ? "" : r.tenant.trim();
        StringBuilder sub = new StringBuilder();
        if (!tenant.isEmpty()) {
            sub.append("租客 ").append(tenant);
        }
        if (r.latestMonth != null && !r.latestMonth.isEmpty()) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(r.latestMonth);
        }
        h.tenant.setText(sub.toString());

        if (r.hasRentPeriod()) {
            h.rentPeriod.setText(r.rentPeriodText());
        } else {
            h.rentPeriod.setText("");
        }
        h.total.setText(BillCalculator.money(r.totalAll) + " 元");
        h.root.setOnClickListener(v -> cb.onRoomClick(r));
        h.root.setOnLongClickListener(v -> {
            cb.onRoomLongClick(r);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView name, count, tenant, rentPeriod, latest, total;

        VH(View v) {
            super(v);
            root = v;
            name = v.findViewById(R.id.room_name);
            count = v.findViewById(R.id.room_bill_count);
            tenant = v.findViewById(R.id.room_tenant);
            rentPeriod = v.findViewById(R.id.room_rent_period);
            latest = v.findViewById(R.id.room_latest);
            total = v.findViewById(R.id.room_total);
        }
    }
}
