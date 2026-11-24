package com.smartblock.model;

import java.time.LocalDate;

/**
 * Mutable data model for a maintenance task submitted to the schedulers.
 */
public class Task {

    /** Unique identifier for this task. */
    public String taskId;
    /** Maintenance date in ISO {@code yyyy-MM-dd} format. */
    public String workDate;
    /** Department responsible for the maintenance work. */
    public String department;
    /** Railway section on which the work is requested. */
    public String sectionId;
    /** Short description of the requested work. */
    public String workDesc;
    /** Priority label used to calculate {@link #priorityRank}. */
    public String priority;
    /** Requested task duration in minutes. */
    public int durationMin;
    /** Queue arrival time in {@code HH:mm} format. */
    public String arrivalTime;
    /** Workflow status, such as {@code PENDING}. */
    public String status;
    /** Earliest requested start time in {@code HH:mm} format. */
    public String requestedStart = "09:00";
    /** Latest requested end time in {@code HH:mm} format. */
    public String requestedEnd = "17:00";

    /** Base priority rank: lower values represent higher priority. */
    public int priorityRank;

    public int effectiveRank;

    /** Creates an empty task for repository or framework population. */
    public Task() { }

    /**
     * Creates a task and initializes its work date to the current local date
     * and its ranks from the supplied priority.
     *
     * @param taskId unique task identifier
     * @param department responsible department
     * @param sectionId railway section for the work
     * @param workDesc description of the requested work
     * @param priority priority label
     * @param durationMin task duration in minutes
     * @param arrivalTime queue arrival time in {@code HH:mm} format
     * @param status workflow status
     */
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

    /**
     * Maps a priority label to its scheduling rank.
     * Critical maps to 1, High to 2, Medium to 3, and every other value,
     * including null or an unrecognized label, maps to 4.
     *
     * @param priority priority label to map
     * @return numeric rank, with lower values indicating higher priority
     */
    public static int rankOf(String priority) {
        if ("Critical".equalsIgnoreCase(priority)) return 1;
        if ("High".equalsIgnoreCase(priority))     return 2;
        if ("Medium".equalsIgnoreCase(priority))   return 3;
        return 4;
    }

    /**
     * Recalculates {@link #priorityRank} from the current priority and resets
     * {@link #effectiveRank} to that base rank.
     */
    public void refreshPriorityRank() {
        priorityRank = rankOf(priority);
        effectiveRank = priorityRank;
    }

    /**
     * Converts {@link #arrivalTime} to minutes after midnight.
     *
     * @return arrival time as a minute count from 00:00
     * @throws RuntimeException if the arrival time cannot be parsed by
     *         {@link TimeHelper#toMinutes(String)}
     */
    public int arrivalInMinutes() {
        return TimeHelper.toMinutes(arrivalTime);
    }


}
