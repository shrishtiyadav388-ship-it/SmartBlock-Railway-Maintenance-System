package com.smartblock.model;

public class TimeHelper {

    public static int toMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public static String toHHMM(int totalMinutes) {
        int hours = (totalMinutes / 60) % 24;
        int mins = totalMinutes % 60;
        return String.format("%02d:%02d", hours, mins);
    }

    public static boolean overlaps(String startA, String endA, String startB, String endB) {
        int a1 = toMinutes(startA), a2 = toMinutes(endA);
        int b1 = toMinutes(startB), b2 = toMinutes(endB);
        return a1 < b2 && b1 < a2;
    }
}
