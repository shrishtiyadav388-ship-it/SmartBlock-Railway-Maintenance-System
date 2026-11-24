package com.smartblock.model;

/**
 * One task's result in a generated schedule, either allocated or unscheduled.

 */
public class Slot {

        /** Identifier of the task represented by this slot. */
    public String taskId;
        /** Department responsible for the task. */
    public String department;
        /** Railway section assigned to the task. */
    public String sectionId;
        /** Priority label copied from the task. */
    public String priority;
        /** Scheduled start time in {@code HH:mm}, or {@code -} if unscheduled. */
    public String start;
        /** Scheduled end time in {@code HH:mm}, or {@code -} if unscheduled. */
    public String end;
        /** Minutes between task arrival and its scheduled or considered start. */
    public int waitingMin;
        /** Effective priority rank; lower values represent higher priority. */
    public int effectiveRank;
        /** Human-readable explanation for the scheduling result. */
    public String reason;
        /** Whether the task was assigned a time slot. */
    public boolean scheduled;

        /**
         * Creates a scheduled slot with the default rank and reason used by the
         * basic scheduling algorithms.
         *
         * @param taskId task identifier
         * @param department responsible department
         * @param sectionId assigned section
         * @param priority task priority label
         * @param start scheduled start time in {@code HH:mm}
         * @param end scheduled end time in {@code HH:mm}
         * @param waitingMin minutes waited before the scheduled start
         */
    public Slot(String taskId, String department, String sectionId, String priority,
                String start, String end, int waitingMin) {
        this(taskId, department, sectionId, priority, start, end, waitingMin,
                0, "OS scheduling comparison", true);
    }

        /**
         * Creates a scheduled slot with an effective rank and explanation.
         *
         * @param taskId task identifier
         * @param department responsible department
         * @param sectionId assigned section
         * @param priority task priority label
         * @param start scheduled start time in {@code HH:mm}
         * @param end scheduled end time in {@code HH:mm}
         * @param waitingMin minutes waited before the scheduled start
         * @param effectiveRank effective priority rank
         * @param reason explanation for the scheduling result
         */
    public Slot(String taskId, String department, String sectionId, String priority,
                String start, String end, int waitingMin, int effectiveRank, String reason) {
        this(taskId, department, sectionId, priority, start, end, waitingMin,
                effectiveRank, reason, true);
    }

        /** Creates a slot with an explicit scheduled state. */
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

        /**
         * Creates an unscheduled slot from a task, using {@code -} for its times.
         */
    public static Slot unscheduled(Task task, int waitingMin, int effectiveRank, String reason) {
        return new Slot(task.taskId, task.department, task.sectionId, task.priority,
                "-", "-", waitingMin, effectiveRank, reason, false);
    }

        /**
         * Returns the display status corresponding to {@link #scheduled}.
         *
         * @return {@code Scheduled} when this slot has a time allocation;
         *         otherwise {@code Not scheduled}
         */
    public String getScheduleStatus() { return scheduled ? "Scheduled" : "Not scheduled"; }
}
