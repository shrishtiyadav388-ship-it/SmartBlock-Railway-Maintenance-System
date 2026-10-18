package com.smartblock.model;

public class Slot {

    public String taskId;
    public String department;
    public String sectionId;
    public String priority;
    public String start;
    public String end;
    public int waitingMin;
    public int effectiveRank;
    public String reason;
    public boolean scheduled;

    public Slot(String taskId, String department, String sectionId, String priority,
                String start, String end, int waitingMin) {
        this(taskId, department, sectionId, priority, start, end, waitingMin,
                0, "OS scheduling comparison", true);
    }

    public Slot(String taskId, String department, String sectionId, String priority,
                String start, String end, int waitingMin, int effectiveRank, String reason) {
        this(taskId, department, sectionId, priority, start, end, waitingMin,
                effectiveRank, reason, true);
    }

    private Slot(String taskId, String department, String sectionId, String priority,
                 String start, String end, int waitingMin, int effectiveRank,
                 String reason, boolean scheduled) {
        this.taskId = taskId;
        this.department = department;
        this.sectionId = sectionId;
        this.priority = priority;
        this.start = start;
        this.end = end;
        this.waitingMin = waitingMin;
        this.effectiveRank = effectiveRank;
        this.reason = reason;
        this.scheduled = scheduled;
    }

    public static Slot unscheduled(Task task, int waitingMin, int effectiveRank, String reason) {
        return new Slot(task.taskId, task.department, task.sectionId, task.priority,
                "-", "-", waitingMin, effectiveRank, reason, false);
    }

    public String getScheduleStatus() { return scheduled ? "Scheduled" : "Not scheduled"; }
}
