package com.smartblock.os;

import com.smartblock.model.Task;
import com.smartblock.model.TimeHelper;
import java.util.*;

/** Simple OS example: a section is occupied only while a job is running. */
public class ResourceAllocator {
    public static class Allocation {
        public String taskId, sectionId, status, start, end;

        public Allocation(String taskId, String sectionId, String status, String start, String end) {
            this.taskId = taskId;
            this.sectionId = sectionId;
            this.status = status;
            this.start = start;
            this.end = end;
        }
    }

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

    private static int safeMinutes(String value, int fallback) {
        try { return TimeHelper.toMinutes(value); }
        catch (RuntimeException ex) { return fallback; }
    }
}