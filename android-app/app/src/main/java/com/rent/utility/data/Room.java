package com.rent.utility.data;

/**
 * 房间（一级导航实体）。
 * 一个房间下挂多张账单；删除房间会级联删除该房间全部账单。
 * 租房时间/到期时间为年月日（yyyy-MM-dd），可留空。
 */
public class Room {

    public long id;
    public String name = "";
    public String tenant = "";
    public String rentStart = "";
    public String rentEnd = "";

    // 统计（由 DbHelper 汇总填充）
    public int billCount;
    public String latestMonth = "";
    public double latestTotal;
    public double totalAll;

    public Room() {
    }

    public Room(String name) {
        this.name = name;
    }

    public boolean hasRentPeriod() {
        return (rentStart != null && !rentStart.trim().isEmpty())
                || (rentEnd != null && !rentEnd.trim().isEmpty());
    }

    /** 展示用：租房 2025-01-01 ~ 2025-12-31（缺哪边显示哪边） */
    public String rentPeriodText() {
        StringBuilder sb = new StringBuilder();
        if (rentStart != null && !rentStart.trim().isEmpty()) sb.append(rentStart.trim());
        if (rentEnd != null && !rentEnd.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" ~ ");
            sb.append(rentEnd.trim());
        }
        return sb.toString();
    }
}
