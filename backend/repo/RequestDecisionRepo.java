package com.smartblock.repo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class RequestDecisionRepo {
    private final JdbcTemplate jdbc;

    public RequestDecisionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void markRescheduled(String taskId, String reason, String changedBy) {
        updateRequestStatus(taskId, "RESCHEDULED", reason, changedBy, List.of("RESCHEDULE_REQUIRED"));
    }

    @Transactional
    public void decidePendingRequest(String taskId, String decision, String reason, String changedBy) {
        if (!List.of("REJECTED", "RESCHEDULE_REQUIRED").contains(decision)) {
            throw new IllegalArgumentException("Choose Reject or Request Rescheduling.");
        }
        String cleanReason = cleanDecisionReason(reason);
        updateRequestStatus(taskId, decision, cleanReason, changedBy, List.of("PENDING"));
    }

    @Transactional
    public void withdrawDepartmentRequest(String taskId, String deptName, String changedBy) {
        String cleanTaskId = taskId == null ? "" : taskId.trim().toUpperCase(Locale.ROOT);
        List<String> owned = jdbc.query("""
                SELECT t.status FROM maintenance_task t
                JOIN department d ON d.dept_id = t.dept_id
                WHERE t.task_id = ? AND d.dept_name = ? FOR UPDATE
                """, (rs, rowNum) -> rs.getString("status"), cleanTaskId, deptName);
        if (owned.isEmpty()) throw new IllegalArgumentException("Request not found in your department.");
        String current = owned.get(0);
        if (!List.of("PENDING", "RESCHEDULE_REQUIRED").contains(current)) {
            throw new IllegalArgumentException("Only pending or rescheduling-required requests can be withdrawn.");
        }
        updateRequestStatus(cleanTaskId, "WITHDRAWN", "Withdrawn by the requesting department", changedBy,
                List.of(current));
    }
    @Transactional
    public void markRescheduleRequiredIfPending(String taskId, String reason, String changedBy) {
        if ("PENDING".equals(statusForUpdate(taskId))) {
            updateRequestStatus(taskId, "RESCHEDULE_REQUIRED", cleanDecisionReason(reason), changedBy,
                    List.of("PENDING"));
        }
    }

    @Transactional
    public void markUnfinishedForRescheduling(String taskId, String reason, String changedBy) {
        String cleanTaskId = taskId == null ? "" : taskId.trim().toUpperCase(Locale.ROOT);
        String cleanReason = cleanDecisionReason(reason);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.status, t.completion_status, b.schedule_date, b.end_time,
                       CASE WHEN b.task_id IS NULL THEN 1
                            WHEN NOW() < STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') THEN 1
                            ELSE 0 END AS before_end
                FROM maintenance_task t LEFT JOIN block_schedule b ON b.task_id = t.task_id
                WHERE t.task_id = ? FOR UPDATE
                """, cleanTaskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Request not found.");
        Map<String, Object> row = rows.get(0);
        if (!"APPROVED".equals(String.valueOf(row.get("status"))))
            throw new IllegalArgumentException("Only an approved task can be sent for rescheduling.");
        String completion = String.valueOf(row.get("completion_status"));
        if (!List.of("NOT_STARTED", "NOT_COMPLETED", "RETURNED").contains(completion))
            throw new IllegalArgumentException("Only work with no report, a failed report, or a returned report can be rescheduled.");
        if (row.get("schedule_date") == null || row.get("end_time") == null)
            throw new IllegalArgumentException("No approved time slot was found.");
        Object beforeEnd = row.get("before_end");
        boolean waiting = beforeEnd instanceof Number
                ? ((Number) beforeEnd).intValue() != 0
                : beforeEnd instanceof Boolean && (Boolean) beforeEnd;
        if (waiting)
            throw new IllegalArgumentException("Wait until the approved slot ends before requesting a new slot.");
        int changed = jdbc.update("""
                UPDATE maintenance_task SET status = 'RESCHEDULE_REQUIRED',
                    decision_reason = ?, completion_note = ?
                WHERE task_id = ? AND status = 'APPROVED'
                """, cleanReason, cleanReason, cleanTaskId);
        if (changed != 1) throw new IllegalArgumentException("Request changed. Refresh and try again.");
        jdbc.update("""
                INSERT INTO request_decision_history (task_id, previous_status, new_status, reason, changed_by)
                VALUES (?, 'APPROVED', 'RESCHEDULE_REQUIRED', ?, ?)
                """, cleanTaskId, cleanReason, safeChangedBy(changedBy));
        jdbc.update("""
                INSERT INTO completion_history (task_id, event_type, performed_by, event_note)
                VALUES (?, 'FOLLOW_UP', ?, ?)
                """, cleanTaskId, safeChangedBy(changedBy), "Control Room requested a new slot: " + cleanReason);
    }

    @Transactional
    public void recordApprovedDecision(String taskId, String reason, String changedBy) {
        if (!"APPROVED".equals(statusForUpdate(taskId))) return;
        List<String> priorStatuses = jdbc.query("""
                SELECT new_status FROM request_decision_history
                WHERE task_id = ? ORDER BY decision_id DESC LIMIT 1
                """, (rs, rowNum) -> rs.getString("new_status"), taskId);
        // Seeded examples may not have a history row yet.
        String prior = priorStatuses.isEmpty() ? "PENDING" : priorStatuses.get(0);
        jdbc.update("UPDATE maintenance_task SET decision_reason = ? WHERE task_id = ?",
                cleanDecisionReason(reason), taskId);
        if (!"APPROVED".equals(prior)) {
            jdbc.update("""
                    INSERT INTO request_decision_history (task_id, previous_status, new_status, reason, changed_by)
                    VALUES (?, ?, 'APPROVED', ?, ?)
                    """, taskId, prior,
                    cleanDecisionReason(reason), safeChangedBy(changedBy));
        }
    }
    private void updateRequestStatus(String taskId, String newStatus, String reason, String changedBy,
                                     List<String> allowedPreviousStatuses) {
        String cleanTaskId = taskId == null ? "" : taskId.trim().toUpperCase(Locale.ROOT);
        String previous = statusForUpdate(cleanTaskId);
        if (previous == null) throw new IllegalArgumentException("Request not found.");
        if (!allowedPreviousStatuses.contains(previous)) {
            throw new IllegalArgumentException("This request is now " + previous.replace('_', ' ')
                    + ". Refresh the dashboard before changing it.");
        }
        Integer blocks = jdbc.queryForObject("SELECT COUNT(*) FROM block_schedule WHERE task_id = ?",
                Integer.class, cleanTaskId);
        boolean keepingOldBlockAsHistory = blocks != null && blocks > 0
                && "RESCHEDULED".equals(newStatus) && "RESCHEDULE_REQUIRED".equals(previous);
        if (blocks != null && blocks > 0 && !keepingOldBlockAsHistory)
            throw new IllegalArgumentException("An approved block cannot be rejected or withdrawn here.");

        int changed = jdbc.update("""
                UPDATE maintenance_task SET status = ?, decision_reason = ?
                WHERE task_id = ? AND status = ?
                """, newStatus, reason, cleanTaskId, previous);
        if (changed != 1) throw new IllegalArgumentException("Request changed while you were deciding. Refresh and try again.");
        jdbc.update("""
                INSERT INTO request_decision_history (task_id, previous_status, new_status, reason, changed_by)
                VALUES (?,?,?,?,?)
                """, cleanTaskId, previous, newStatus, reason, safeChangedBy(changedBy));
    }

    private String statusForUpdate(String taskId) {
        List<String> rows = jdbc.query("SELECT status FROM maintenance_task WHERE task_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("status"), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String cleanDecisionReason(String reason) {
        String value = reason == null ? "" : reason.trim().replaceAll("\\s+", " ");
        if (value.length() < 5 || value.length() > 500) {
            throw new IllegalArgumentException("Enter a reason between 5 and 500 characters.");
        }
        return value;
    }
    private String safeChangedBy(String changedBy) {
        String value = changedBy == null || changedBy.isBlank() ? "unknown" : changedBy.trim();
        return value.length() > 40 ? value.substring(0, 40) : value;
    }

}
