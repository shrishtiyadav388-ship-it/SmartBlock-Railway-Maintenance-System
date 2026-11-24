package com.smartblock.os;

import com.smartblock.model.Slot;
import com.smartblock.model.Task;
import com.smartblock.model.TimeHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private operations shared by the scheduling algorithms.
 */
final class ScheduleHelper {
    /** Minute value immediately after the final minute of the day. */
    static final int DAY_END = 24 * 60;

    private ScheduleHelper() { }

    /**
     * Copies the pending tasks from the supplied list, preserving their order.
     * Status comparison is case-insensitive.
     *
     * @param tasks tasks to filter
     * @return a new list containing only tasks with {@code PENDING} status
     */
    static List<Task> pendingTasks(List<Task> tasks) {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks) if ("PENDING".equalsIgnoreCase(task.status)) result.add(task);
        return result;
    }

    /**
     * Finds the earliest task arrival time.
     *
     * @param tasks tasks to inspect
     * @return earliest arrival in minutes after midnight, or {@code 0} if empty
     */
    static int earliestArrival(List<Task> tasks) {
        return tasks.stream().mapToInt(Task::arrivalInMinutes).min().orElse(0);
    }

    /**
     * Finds the earliest time any task can be released to scheduling.
     *
     * @param tasks tasks to inspect
     * @return earliest release time in minutes after midnight, or {@code 0} if empty
     */
    static int earliestRelease(List<Task> tasks) {
        return tasks.stream().mapToInt(ScheduleHelper::releaseTime).min().orElse(0);
    }

    /**
     * Selects waiting tasks whose release time is at or before the current time.
     *
     * @param waiting tasks not yet scheduled
     * @param time current scheduler time in minutes after midnight
     * @return ready tasks, preserving their order in {@code waiting}
     */
    static List<Task> readyTasks(List<Task> waiting, int time) {
        List<Task> ready = new ArrayList<>();
        for (Task task : waiting) if (releaseTime(task) <= time) ready.add(task);
        return ready;
    }

    /**
     * Filters ready tasks that can start now, finish by their requested end,
     * and avoid unavailable windows. Tasks that would need to move to a later
     * start are not included.
     */
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

    /**
     * Finds the earliest future release or safe start among waiting tasks that
     * can still finish by their requested end. Returns {@link Integer#MAX_VALUE}
     * when no candidate exists; callers treat values at or beyond
     * {@link #DAY_END} as no usable time remaining today.
     *
     * @param section section being scheduled
     * @param waiting all tasks not yet scheduled
     * @param ready tasks already released
     * @param time current scheduler time in minutes after midnight
     * @param unavailable section windows represented as section/start/end arrays
     * @return next candidate time, or {@link Integer#MAX_VALUE} if none exists
     */
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

    /**
     * Appends an unscheduled slot for every remaining task, calculating its
     * waiting time and effective rank at the supplied time. The evaluation
     * time is capped at the last minute of the day.
     *
     * @param plan output plan to append to
     * @param waiting tasks that could not be scheduled
     * @param time current scheduler time in minutes after midnight
     * @param agingStep aging interval in minutes; must be positive
     */
    static void addUnscheduled(List<Slot> plan, List<Task> waiting, int time, int agingStep) {
        for (Task task : waiting) {
            int waitedUntil = Math.min(DAY_END - 1, Math.max(time, releaseTime(task)));
            int waitingTime = Math.max(0, waitedUntil - task.arrivalInMinutes());
            plan.add(Slot.unscheduled(task, waitingTime, effectiveRank(task, waitedUntil, agingStep),
                    "No available time in the requested window."));
        }
    }

    /**
     * Creates a scheduled output slot with formatted times and a reason string.
     *
     * @param task scheduled task
     * @param start start time in minutes after midnight
     * @param end end time in minutes after midnight
     * @param rank effective priority rank at the start time
     * @return scheduled slot representing the task
     */
    static Slot scheduledSlot(Task task, int start, int end, int rank) {
        String reason = "Priority with waiting-time aging (rank " + rank + "); section availability checked";
        return new Slot(task.taskId, task.department, task.sectionId, task.priority,
                TimeHelper.toHHMM(start), TimeHelper.toHHMM(end),
                Math.max(0, start - task.arrivalInMinutes()), rank, reason);
    }

    /**
     * Computes when a task becomes eligible, respecting both arrival and
     * requested start times.
     *
     * @param task task whose release time is needed
     * @return later of arrival and requested start, in minutes after midnight
     */
    static int releaseTime(Task task) {
        int requested = minutesOrDefault(task.requestedStart, task.arrivalInMinutes());
        return Math.max(task.arrivalInMinutes(), requested);
    }

    /**
     * Calculates the task's priority rank after waiting-time aging.
     * Each full aging interval reduces the numeric rank by one, down to 1.
     *
     * @param task task to rank
     * @param time time at which to calculate the rank, in minutes after midnight
     * @param agingStep positive aging interval in minutes
     * @return effective rank, where lower values mean higher priority
     */
    static int effectiveRank(Task task, int time, int agingStep) {
        int waited = Math.max(0, time - releaseTime(task));
        return Math.max(1, task.priorityRank - waited / agingStep);
    }

    /**
     * Moves a proposed start past every overlapping unavailable window for the
     * section. Windows that are null, incomplete, malformed, or invalid are
     * ignored. Returns {@link #DAY_END} if the task cannot finish before day end.
     */
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
