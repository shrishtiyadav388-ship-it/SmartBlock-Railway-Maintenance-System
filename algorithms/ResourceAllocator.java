package com.smartblock.os;

import com.smartblock.model.Task;
import com.smartblock.model.TimeHelper;
import java.util.*;

/**
 * Allocates pending tasks to sections in arrival order.

 */
public class ResourceAllocator {
    /** The result of attempting to allocate one task. */
    public static class Allocation {
        /** Identifier of the task that was considered. */
        public String taskId;
        /** Identifier of the section used for this allocation. */
        public String sectionId;
        /** Either {@code Allocated} or {@code No time left in requested window}. */
        public String status;
        /** Allocated start time in {@code HH:mm}; {@code -} if not allocated. */
        public String start;
        /** Allocated end time in {@code HH:mm}; {@code -} if not allocated. */
        public String end;

        /**
         * Creates an allocation result.
         *
         * @param taskId identifier of the task
         * @param sectionId identifier of the section
         * @param status allocation status
         * @param start start time, or {@code -} when not allocated
         * @param end end time, or {@code -} when not allocated
         */
        public Allocation(String taskId, String sectionId, String status, String start, String end) {
            this.taskId = taskId;
            this.sectionId = sectionId;
            this.status = status;
            this.start = start;
            this.end = end;
        }
    }

    /**
     * Allocates pending tasks in ascending arrival-time order, breaking ties
     * by task ID. Each section is tracked independently using its exact ID
     * string, so section IDs that differ only by letter case are treated as
     * separate resources.
     *
     *The start time is the latest of the task's arrival, its requested
    * start, and the section's previous allocation end. Missing or
    * unparsable requested times fall back to the arrival time for the start
    * and 17:00 for the end. For tasks with positive durations, an allocation
    * is returned only when it ends no later than the requested end and
    * before midnight. Tasks that do not fit receive a result with status
    * 
     * Only tasks whose status is are included. The input
     * list and its tasks are not modified.
     *
     * @param tasks tasks to consider
     * @return one result for each pending task, in allocation processing order
     */
    public static List<Allocation> allocate(List<Task> tasks) {
        Map<String, Integer> sectionFreeAt = new HashMap<>();
        List<Allocation> result = new ArrayList<>();
        List<Task> queue = new ArrayList<>();
        for (Task task : tasks) if ("PENDING".equalsIgnoreCase(task.status)) queue.add(task);
        queue.sort(Comparator.comparingInt(Task::arrivalInMinutes).thenComparing(task -> task.taskId));

        for (Task task : queue) {
            int requestedStart = safeMinutes(task.requestedStart, task.arrivalInMinutes());
            int requestedEnd = safeMinutes(task.requestedEnd, 17 * 60);
            int start = Math.max(Math.max(task.arrivalInMinutes(), requestedStart),
                    sectionFreeAt.getOrDefault(task.sectionId, 0));
            int end = start + task.durationMin;

            if (end <= requestedEnd && end < 24 * 60) {
                result.add(new Allocation(task.taskId, task.sectionId, "Allocated",
                        TimeHelper.toHHMM(start), TimeHelper.toHHMM(end)));
                sectionFreeAt.put(task.sectionId, end);
            } else {
                result.add(new Allocation(task.taskId, task.sectionId,
                        "No time left in requested window", "-", "-"));
            }
        }
        return result;
    }

    /**
     * Parses a time value, using a fallback if parsing fails.
     *
     * @param value time string to parse
     * @param fallback minute value to return when parsing fails
    * @return the parsed minute value, or {@code fallback}
     */
    private static int safeMinutes(String value, int fallback) {
        try { return TimeHelper.toMinutes(value); }
        catch (RuntimeException ex) { return fallback; }
    }
}
