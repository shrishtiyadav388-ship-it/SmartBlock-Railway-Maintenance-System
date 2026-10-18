package com.smartblock.service;

import com.smartblock.model.Conflict;
import com.smartblock.model.Slot;
import com.smartblock.model.Task;
import com.smartblock.os.*;
import com.smartblock.repo.PlanningRepo;
import com.smartblock.repo.RequestRepo;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class PlanningService {

    private final PlanningRepo planningRepo;
    private final RequestRepo requestRepo;
    private final BlockService blockService;

    public PlanningService(PlanningRepo planningRepo, RequestRepo requestRepo, BlockService blockService) {
        this.planningRepo = planningRepo;
        this.requestRepo = requestRepo;
        this.blockService = blockService;
    }

    public List<Conflict> detectAndSaveConflicts(LocalDate workDate) {
        List<Conflict> conflicts = planningRepo.findRequestConflicts(workDate);
        for (Conflict conflict : conflicts) {
            planningRepo.saveConflict(conflict.sectionId, workDate, conflict.taskA, conflict.taskB);
        }
        return conflicts;
    }

    public Map<String, Object> compareScheduling(LocalDate workDate) {
        List<Task> tasks = planningRepo.findTasksForDate(workDate);

        List<Slot> fcfs = Scheduler.fcfs(tasks);
        List<Slot> sjf = Scheduler.sjf(tasks);
        List<Slot> priority = Scheduler.priority(tasks);

        return Map.of(
                "fcfs", fcfs,
                "sjf", sjf,
                "priority", priority,
                "fcfsAverage", Scheduler.averageWaiting(fcfs),
                "sjfAverage", Scheduler.averageWaiting(sjf),
                "priorityAverage", Scheduler.averageWaiting(priority));
    }


    public List<ResourceAllocator.Allocation> allocation(LocalDate workDate) {
        return ResourceAllocator.allocate(planningRepo.findTasksForDate(workDate));
    }

    public Map<String, Object> buildPlan(LocalDate workDate) {
        List<String[]> unavailableWindows = planningRepo.findTrainWindows(workDate);
        List<Slot> plan = Planner.buildPlan(planningRepo.findTasksForDate(workDate), unavailableWindows);
        return Map.of("date", workDate.toString(), "plan", plan,
                "verification", Planner.verifyPlan(plan, unavailableWindows),
                "planningMethod", "Date-wise, non-preemptive OS Priority Scheduling with Aging",
                "note", "This plan contains requests for " + workDate + " only. Trains and approved blocks are checked for the same date. The Control Room must validate the result against official railway rules.");
    }

    public Map<String, Object> approvePlan(LocalDate workDate, String approvedBy) {
        List<String[]> unavailableWindows = planningRepo.findTrainWindows(workDate);
        List<Slot> plan = Planner.buildPlan(planningRepo.findTasksForDate(workDate), unavailableWindows);

        List<String> saved = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (Slot slot : plan) {
            if (!slot.scheduled) {
                String reason = "No feasible block fits within the requested time window on " + workDate
                        + ". Please submit a revised date or time window.";
                requestRepo.markRescheduleRequiredIfPending(slot.taskId, reason, approvedBy);
                failed.add(slot.taskId + ": " + reason + " Marked Rescheduling Required; not approved.");
                continue;
            }

            try {
                int blockId = blockService.approveBlock(
                        slot.taskId, slot.sectionId, workDate, slot.start, slot.end, approvedBy);
                String approvalReason = "Approved by Control Room for " + workDate + " " + slot.start + "-" + slot.end;
                requestRepo.recordApprovedDecision(slot.taskId, approvalReason, approvedBy);
                saved.add("Block " + blockId + ": " + workDate + " " + slot.sectionId + " "
                        + slot.start + "-" + slot.end + " for " + slot.taskId);
            } catch (BlockService.SectionBusyException e) {
                String reason = "The requested slot became unavailable: " + e.getMessage()
                        + ". Please review or submit a revised time window.";
                requestRepo.markRescheduleRequiredIfPending(slot.taskId, reason, approvedBy);
                failed.add(slot.taskId + ": " + reason + " (not approved; rescheduling required if still pending)");
            }
        }

        return Map.of("date", workDate.toString(), "saved", saved, "failed", failed,
                      "verification", Planner.verifyPlan(plan, unavailableWindows));
    }
}
