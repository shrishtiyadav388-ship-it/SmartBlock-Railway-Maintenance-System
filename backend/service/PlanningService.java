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
    private final BankersResourceSafetyService resourceSafety;

    public PlanningService(PlanningRepo planningRepo, RequestRepo requestRepo, BlockService blockService,
            BankersResourceSafetyService resourceSafety) {
        this.planningRepo = planningRepo;
        this.requestRepo = requestRepo;
        this.blockService = blockService;
        this.resourceSafety = resourceSafety;
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

    public Map<String, Object> buildTimeline(LocalDate workDate) {
        List<Map<String, Object>> requests = new ArrayList<>();
        for (Map<String, Object> row : planningRepo.findTimelineRequests(workDate)) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            int age = ((Number) row.get("ageMin")).intValue();
            int baseRank = Task.rankOf(String.valueOf(row.get("priority")));

            item.put("effectiveRank", Math.max(1, baseRank - age / 30));
            item.put("aged", baseRank - age / 30 < baseRank);
            requests.add(item);
        }
        return Map.of("ok", true, "date", workDate.toString(),
                "sections", planningRepo.findSections(),
                "trains", planningRepo.findTimelineTrains(workDate),
                "blocks", planningRepo.activeBlocks(workDate, workDate),
                "requests", requests,
                "conflicts", planningRepo.findRequestConflicts(workDate));
    }

    public Map<String, Object> buildPlan(LocalDate workDate) {
        List<String[]> unavailableWindows = planningRepo.findTrainWindows(workDate);
        List<Slot> plan = Planner.buildPlan(planningRepo.findTasksForDate(workDate), unavailableWindows);
        return Map.of("date", workDate.toString(), "plan", plan,
                "verification", Planner.verifyPlan(plan, unavailableWindows),
                "planningMethod", "Date-wise, non-preemptive OS Priority Scheduling with Aging",
                "resourceSafety", resourceSafety.assessPlan(workDate, plan),
                "note", "This plan contains requests for " + workDate
                        + " only. SmartBlock checks section conflicts and qualified department-team capacity for each time window. At approval, it locks and reserves named workers together with the block in one database transaction; if either resource is unavailable, the entire approval is cancelled. This is a planning aid, not official railway authorization.");
    }

    public synchronized Map<String, Object> approvePlan(LocalDate workDate, String approvedBy) {
        List<String[]> unavailableWindows = planningRepo.findTrainWindows(workDate);
        List<Slot> plan = Planner.buildPlan(planningRepo.findTasksForDate(workDate), unavailableWindows);

        List<String> saved = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        List<Slot> approvalOrder = new ArrayList<>(plan);
        approvalOrder.sort(Comparator.comparing((Slot slot) -> slot.scheduled ? slot.start : "99:99")
                .thenComparing(slot -> slot.sectionId).thenComparing(slot -> slot.taskId));

        for (Slot slot : approvalOrder) {
            if (!slot.scheduled) {
                String reason = "No feasible block fits within the requested time window on " + workDate
                        + ". Please submit a revised date or time window.";
                requestRepo.markRescheduleRequiredIfPending(slot.taskId, reason, approvedBy);
                failed.add(slot.taskId + ": " + reason + " Marked Rescheduling Required; not approved.");
                continue;
            }

            try {
                Map<String, Object> safety = resourceSafety.checkSlot(
                        workDate, slot.taskId, slot.sectionId, slot.start, slot.end);
                if (!Boolean.TRUE.equals(safety.get("safe"))) {
                    String reason = String.valueOf(safety.get("reason"));
                    requestRepo.markRescheduleRequiredIfPending(slot.taskId, reason, approvedBy);
                    failed.add(slot.taskId + ": " + reason + " Not approved.");
                    continue;
                }

                int blockId = blockService.approveBlock(
                        slot.taskId, slot.sectionId, workDate, slot.start, slot.end, approvedBy);
                List<String> assignedWorkers = blockService.assignedWorkerLabels(blockId);
                String workerList = assignedWorkers.isEmpty() ? "worker assignment not recorded"
                        : String.join(", ", assignedWorkers);
                String approvalReason = "Approved by Control Room for " + workDate + " " + slot.start + "-" + slot.end
                        + ". Resource check passed; assigned team: " + workerList + ".";
                requestRepo.recordApprovedDecision(slot.taskId, approvalReason, approvedBy);
                saved.add("Block " + blockId + ": " + workDate + " " + slot.sectionId + " "
                        + slot.start + "-" + slot.end + " for " + slot.taskId
                        + " (assigned team: " + workerList + ")");
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
