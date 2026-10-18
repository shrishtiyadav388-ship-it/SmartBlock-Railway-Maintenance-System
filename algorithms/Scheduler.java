package com.smartblock.os;

import com.smartblock.model.Slot;
import com.smartblock.model.Task;
import com.smartblock.model.TimeHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Scheduler {
    private Scheduler() { }

    public static List<Slot> fcfs(List<Task> tasks) {
        return runCpuSchedule(tasks, Comparator.comparingInt(Task::arrivalInMinutes));
    }

    public static List<Slot> sjf(List<Task> tasks) {
        return runCpuSchedule(tasks, Comparator.comparingInt((Task t) -> t.durationMin)
                .thenComparingInt(Task::arrivalInMinutes));
    }

    public static List<Slot> priority(List<Task> tasks) {
        return runCpuSchedule(tasks, Comparator.comparingInt((Task t) -> t.priorityRank)
                .thenComparingInt(Task::arrivalInMinutes));
    }

    private static List<Slot> runCpuSchedule(List<Task> tasks, Comparator<Task> rule) {
        List<Task> waiting = ScheduleHelper.pendingTasks(tasks);
        List<Slot> plan = new ArrayList<>();
        int time = ScheduleHelper.earliestArrival(waiting);

        while (!waiting.isEmpty()) {
            List<Task> ready = new ArrayList<>();
            for (Task task : waiting) if (task.arrivalInMinutes() <= time) ready.add(task);
            if (ready.isEmpty()) {
                time = ScheduleHelper.earliestArrival(waiting);
                continue;
            }

            ready.sort(rule);
            Task task = ready.get(0);
            int start = Math.max(time, task.arrivalInMinutes());
            int end = start + task.durationMin;
            plan.add(new Slot(task.taskId, task.department, task.sectionId,
                    task.priority, TimeHelper.toHHMM(start),
                    TimeHelper.toHHMM(end), start - task.arrivalInMinutes()));
            time = end;
            waiting.remove(task);
        }
        return plan;
    }

    public static List<Slot> priorityWithAgingAndResources(
            List<Task> tasks, List<String[]> unavailableWindows, int agingStepMinutes) {
        int agingStep = Math.max(1, agingStepMinutes);
        Map<String, List<Task>> bySection = new LinkedHashMap<>();
        for (Task task : ScheduleHelper.pendingTasks(tasks))
            bySection.computeIfAbsent(task.sectionId, key -> new ArrayList<>()).add(task);

        List<Slot> plan = new ArrayList<>();
        for (Map.Entry<String, List<Task>> section : bySection.entrySet())
            plan.addAll(scheduleOneSection(section.getKey(), section.getValue(), unavailableWindows, agingStep));

        plan.sort(Comparator.comparing((Slot slot) -> slot.sectionId)
                .thenComparing(slot -> !slot.scheduled)
                .thenComparingInt(slot -> ScheduleHelper.minutesOrDefault(slot.start, 0)));
        return plan;
    }

    private static List<Slot> scheduleOneSection(String sectionId, List<Task> tasks,
            List<String[]> unavailable, int agingStep) {
        List<Task> waiting = new ArrayList<>(tasks);
        List<Slot> plan = new ArrayList<>();
        int time = ScheduleHelper.earliestRelease(waiting);

        while (!waiting.isEmpty()) {
            List<Task> ready = ScheduleHelper.readyTasks(waiting, time);
            if (ready.isEmpty()) {
                time = ScheduleHelper.earliestRelease(waiting);
                continue;
            }

            List<Task> fitting = ScheduleHelper.tasksThatFitNow(sectionId, ready, time, unavailable);
            if (fitting.isEmpty()) {
                int next = ScheduleHelper.nextAvailableTime(sectionId, waiting, ready, time, unavailable);
                if (next >= ScheduleHelper.DAY_END) {
                    ScheduleHelper.addUnscheduled(plan, waiting, time, agingStep);
                    break;
                }
                time = next <= time ? time + 1 : next;
                continue;
            }

            int currentTime = time;
            fitting.sort(Comparator.comparingInt((Task t) -> ScheduleHelper.effectiveRank(t, currentTime, agingStep))
                    .thenComparingInt(ScheduleHelper::releaseTime).thenComparing(t -> t.taskId));
            Task task = fitting.get(0);
            int end = time + task.durationMin;
            int rank = ScheduleHelper.effectiveRank(task, time, agingStep);
            plan.add(ScheduleHelper.scheduledSlot(task, time, end, rank));
            time = end;
            waiting.remove(task);
        }
        return plan;
    }


    public static double averageWaiting(List<Slot> plan) {
        if (plan.isEmpty()) return 0;
        int total = 0;
        for (Slot slot : plan) total += slot.waitingMin;
        return (double) total / plan.size();
    }
}