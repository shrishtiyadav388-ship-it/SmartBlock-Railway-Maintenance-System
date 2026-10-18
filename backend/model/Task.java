package com.smartblock.model;

import java.time.LocalDate;

public class Task {

    public String taskId;
    public String workDate;
    public String department;
    public String sectionId;
    public String workDesc;
    public String priority;
    public int durationMin;
    public String arrivalTime;
    public String status;
    public String requestedStart = "09:00";
    public String requestedEnd = "17:00";

    public int priorityRank;

    public int effectiveRank;

    public Task() { }

    public Task(String taskId, String department, String sectionId, String workDesc,
                String priority, int durationMin, String arrivalTime, String status) {
        this.taskId = taskId;
        this.workDate = LocalDate.now().toString();
        this.department = department;
        this.sectionId = sectionId;
        this.workDesc = workDesc;
        this.priority = priority;
        this.durationMin = durationMin;
        this.arrivalTime = arrivalTime;
        this.status = status;
        this.priorityRank = rankOf(priority);
        this.effectiveRank = this.priorityRank;
    }

    public static int rankOf(String priority) {
        if ("Critical".equalsIgnoreCase(priority)) return 1;
        if ("High".equalsIgnoreCase(priority))     return 2;
        if ("Medium".equalsIgnoreCase(priority))   return 3;
        return 4;
    }

    public void refreshPriorityRank() {
        priorityRank = rankOf(priority);
        effectiveRank = priorityRank;
    }

    public int arrivalInMinutes() {
        return TimeHelper.toMinutes(arrivalTime);
    }


}
