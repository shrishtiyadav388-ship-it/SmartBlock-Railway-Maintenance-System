package com.smartblock.os;

import com.smartblock.model.Slot;
import com.smartblock.model.Task;
import com.smartblock.model.TimeHelper;

import java.util.ArrayList;
import java.util.List;

final class ScheduleHelper {
    static final int DAY_END = 24 * 60;

    private ScheduleHelper() { }

    static List<Task> pendingTasks(List<Task> tasks) {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks) if ("PENDING".equalsIgnoreCase(task.status)) result.add(task);
        return result;
    }

    static int earliestArrival(List<Task> tasks) {
        return tasks.stream().mapToInt(Task::arrivalInMinutes).min().orElse(0);
    }

    static int earliestRelease(List<Task> tasks) {
        return tasks.stream().mapToInt(ScheduleHelper::releaseTime).min().orElse(0);
    }

    static List<Task> readyTasks(List<Task> waiting, int time) {
        List<Task> ready = new ArrayList<>();
        for (Task task : waiting) if (releaseTime(task) <= time) ready.add(task);
        return ready;
    }

    static List<Task> tasksThatFitNow(String section, List<Task> ready, int time,
                                      List<String[]> unavailable) {
        List<Task> fitting = new ArrayList<>();
        for (Task task : ready) {
            int end = time + task.durationMin;
            int start = findSafeStart(section, time, task.durationMin, unavailable);
            int requestedEnd = minutesOrDefault(task.requestedEnd, DAY_END);
            if (start == time && end <= requestedEnd && end < DAY_END) fitting.add(task);
        }
        return fitting;
    }

    static int nextAvailableTime(String section, List<Task> waiting, List<Task> ready,
                                 int time, List<String[]> unavailable) {
        int next = Integer.MAX_VALUE;
        for (Task task : waiting) {
            int release = releaseTime(task);
            int end = minutesOrDefault(task.requestedEnd, DAY_END);
            if (release > time && release + task.durationMin <= end) next = Math.min(next, release);
        }
        for (Task task : ready) {
            int start = findSafeStart(section, time, task.durationMin, unavailable);
            int end = minutesOrDefault(task.requestedEnd, DAY_END);
            if (start > time && start < DAY_END && start + task.durationMin <= end)
                next = Math.min(next, start);
        }
        return next;
    }

    static void addUnscheduled(List<Slot> plan, List<Task> waiting, int time, int agingStep) {
        for (Task task : waiting) {
            int waitedUntil = Math.min(DAY_END - 1, Math.max(time, releaseTime(task)));
            int waitingTime = Math.max(0, waitedUntil - task.arrivalInMinutes());
            plan.add(Slot.unscheduled(task, waitingTime, effectiveRank(task, waitedUntil, agingStep),
                    "No available time in the requested window."));
        }
    }

    static Slot scheduledSlot(Task task, int start, int end, int rank) {
        String reason = "Priority with waiting-time aging (rank " + rank + "); section availability checked";
        return new Slot(task.taskId, task.department, task.sectionId, task.priority,
                TimeHelper.toHHMM(start), TimeHelper.toHHMM(end),
                Math.max(0, start - task.arrivalInMinutes()), rank, reason);
    }

    static int releaseTime(Task task) {
        int requested = minutesOrDefault(task.requestedStart, task.arrivalInMinutes());
        return Math.max(task.arrivalInMinutes(), requested);
    }

    static int effectiveRank(Task task, int time, int agingStep) {
        int waited = Math.max(0, time - releaseTime(task));
        return Math.max(1, task.priorityRank - waited / agingStep);
    }

    private static int findSafeStart(String section, int proposedStart, int duration,
                                     List<String[]> unavailable) {
        int start = proposedStart;
        while (true) {
            if (start + duration >= DAY_END) return DAY_END;
            boolean moved = false;
            for (String[] window : unavailable) {
                if (window == null || window.length < 3 || !section.equalsIgnoreCase(window[0])) continue;
                int blockedStart = minutesOrDefault(window[1], -1);
                int blockedEnd = minutesOrDefault(window[2], -1);
                if (blockedStart < 0 || blockedEnd <= blockedStart) continue;
                if (start < blockedEnd && blockedStart < start + duration) {
                    start = blockedEnd;
                    moved = true;
                    break;
                }
            }
            if (!moved) return start;
        }
    }

    static int minutesOrDefault(String time, int fallback) {
        try {
            return time == null || time.isBlank() ? fallback : TimeHelper.toMinutes(time);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}