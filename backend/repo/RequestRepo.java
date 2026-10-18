package com.smartblock.repo;

import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class RequestRepo {
    private final RequestQueryRepo queries;
    private final RequestCreateRepo creator;
    private final RequestDecisionRepo decisions;
    private final CompletionRepo completions;

    public RequestRepo(RequestQueryRepo queries, RequestCreateRepo creator,
                       RequestDecisionRepo decisions, CompletionRepo completions) {
        this.queries = queries;
        this.creator = creator;
        this.decisions = decisions;
        this.completions = completions;
    }

    public static class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException(String message) { super(message); }
    }

    public List<Map<String,Object>> findRequestsForDept(String dept) { return queries.findRequestsForDept(dept); }
    public List<Map<String,Object>> findControlRequests(LocalDate from, LocalDate to) { return queries.findControlRequests(from, to); }
    public List<Map<String,Object>> findCompletionStatusForControl() { return completions.findControlStatus(); }
    public void reportTaskStatus(String taskId, String dept, String username, String action, String note) {
        completions.reportWorkStatus(taskId, dept, username, action, note);
    }
    public void recordCompletionFollowUp(String taskId, String note, String username) {
        completions.recordFollowUp(taskId, note, username);
    }
    public void requestWorkStatus(String taskId, String note, String username) {
        completions.requestStatusUpdate(taskId, note, username);
    }
    public void verifyTaskCompletion(String taskId, String action, String note, String controlUser) {
        completions.verifyCompletion(taskId, action, note, controlUser);
    }
    public List<String[]> findAllRequests() { return queries.findAllRequests(); }
    public Integer findDeptId(String dept) { return queries.findDeptId(dept); }
    public String nextTaskId() { return queries.nextTaskId(); }

    public String addTask(String dept, String requestedBy, String section, LocalDate date, String work,
            String priority, int duration, String arrival, String start, String end, String revisesTaskId) {
        return creator.addTask(dept, requestedBy, section, date, work, priority, duration,
                arrival, start, end, revisesTaskId);
    }

    public void markUnfinishedForRescheduling(String id, String reason, String user) {
        decisions.markUnfinishedForRescheduling(id, reason, user);
    }
    public void markRescheduled(String id, String reason, String user) { decisions.markRescheduled(id, reason, user); }
    public void decidePendingRequest(String id, String decision, String reason, String user) {
        decisions.decidePendingRequest(id, decision, reason, user);
    }
    public void withdrawDepartmentRequest(String id, String dept, String user) {
        decisions.withdrawDepartmentRequest(id, dept, user);
    }
    public void markRescheduleRequiredIfPending(String id, String reason, String user) {
        decisions.markRescheduleRequiredIfPending(id, reason, user);
    }
    public void recordApprovedDecision(String id, String reason, String user) {
        decisions.recordApprovedDecision(id, reason, user);
    }
}
