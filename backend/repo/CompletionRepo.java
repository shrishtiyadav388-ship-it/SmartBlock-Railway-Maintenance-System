package com.smartblock.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Locale;

@Repository
public class CompletionRepo {
    private final JdbcTemplate jdbc;
    private final RequestQueryRepo queries;

    public CompletionRepo(JdbcTemplate jdbc, RequestQueryRepo queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    public List<Map<String, Object>> findControlStatus() {
        return queries.findCompletionStatusForControl();
    }

    /** Departments report either completed work or work that could not be completed. */
    @Transactional
    public void reportWorkStatus(String taskId, String department, String username, String action, String note) {
        String cleanAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        String cleanNote = note == null ? "" : note.trim().replaceAll("\\s+", " ");
        if (!List.of("COMPLETED", "NOT_COMPLETED").contains(cleanAction))
            throw new IllegalArgumentException("Choose Completed or Not completed.");
        if (cleanNote.length() < 5 || cleanNote.length() > 500)
            throw new IllegalArgumentException("Add a short note between 5 and 500 characters.");

        Integer deptId = queries.findDeptId(department);
        if (deptId == null) throw new IllegalArgumentException("Your department was not found.");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dept_id, status, completion_status FROM maintenance_task WHERE task_id = ? FOR UPDATE", taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Task " + taskId + " was not found.");
        Map<String, Object> task = rows.get(0);
        if (((Number) task.get("dept_id")).intValue() != deptId)
            throw new IllegalArgumentException("You can only update your own department's task.");
        if (!"APPROVED".equals(String.valueOf(task.get("status"))))
            throw new IllegalArgumentException("Only an approved maintenance task can be updated.");

        String current = String.valueOf(task.get("completion_status"));
        if ("COMPLETED".equals(current))
            throw new IllegalArgumentException("Control Room has already verified this task as completed.");
        if ("REPORTED".equals(current))
            throw new IllegalArgumentException("Your previous report is waiting for Control Room verification.");

        List<Map<String, Object>> blocks = jdbc.queryForList("""
                SELECT schedule_date, start_time, end_time,
                       NOW() < STR_TO_DATE(CONCAT(DATE_FORMAT(schedule_date, '%Y-%m-%d'), ' ', start_time), '%Y-%m-%d %H:%i') AS before_start
                FROM block_schedule WHERE task_id = ? FOR UPDATE
                """, taskId);
        if (blocks.isEmpty()) throw new IllegalArgumentException("No approved block exists for this task.");

        Map<String, Object> block = blocks.get(0);
        // Use MySQL's clock, the same clock used by the dashboard.
        if (asBoolean(block.get("before_start")))
            throw new IllegalArgumentException("Work status can be reported once the approved time has started.");

        String safe = safeUsername(username);
        String savedStatus = cleanAction.equals("COMPLETED") ? "REPORTED" : "NOT_COMPLETED";
        String event = cleanAction.equals("COMPLETED") ? "REPORTED" : "NOT_COMPLETED";
        jdbc.update("""
                UPDATE maintenance_task
                SET completion_status = ?, completion_reported_at = CURRENT_TIMESTAMP,
                    completion_reported_by = ?, completion_report_note = ?,
                    status_requested_at = NULL, status_requested_by = NULL, status_request_note = NULL,
                    completed_at = NULL, completed_by = NULL, completion_note = NULL
                WHERE task_id = ?
                """, savedStatus, safe, cleanNote, taskId);
        jdbc.update("INSERT INTO completion_history (task_id, event_type, performed_by, event_note) VALUES (?, ?, ?, ?)",
                taskId, event, safe, cleanNote);
    }

    /** Sends an in-app request to the department when a work report is missing. */
    @Transactional
    public void requestStatusUpdate(String taskId, String note, String controlUser) {
        String cleanNote = note == null ? "" : note.trim().replaceAll("\\s+", " ");
        if (cleanNote.length() < 5 || cleanNote.length() > 500)
            throw new IllegalArgumentException("Enter a message between 5 and 500 characters.");

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.status, t.completion_status, b.schedule_date, b.end_time,
                       NOW() < STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') AS before_end
                FROM maintenance_task t
                LEFT JOIN block_schedule b ON b.task_id = t.task_id
                WHERE t.task_id = ? FOR UPDATE
                """, taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Task " + taskId + " was not found.");
        Map<String, Object> task = rows.get(0);
        if (!"APPROVED".equals(String.valueOf(task.get("status"))))
            throw new IllegalArgumentException("Only approved work can receive a status request.");
        if (task.get("schedule_date") == null || task.get("end_time") == null)
            throw new IllegalArgumentException("This task has no approved work time.");
        if ("COMPLETED".equals(String.valueOf(task.get("completion_status")))
                || "REPORTED".equals(String.valueOf(task.get("completion_status"))))
            throw new IllegalArgumentException("This task already has a completion report or is verified.");

        if (asBoolean(task.get("before_end")))
            throw new IllegalArgumentException("Request a status report after the approved work time ends.");

        String safeControl = safeUsername(controlUser);
        jdbc.update("""
                UPDATE maintenance_task
                SET status_requested_at = CURRENT_TIMESTAMP,
                    status_requested_by = ?, status_request_note = ?
                WHERE task_id = ?
                """, safeControl, cleanNote, taskId);
        jdbc.update("INSERT INTO completion_history (task_id, event_type, performed_by, event_note) VALUES (?, 'STATUS_REQUESTED', ?, ?)",
                taskId, safeControl, cleanNote);
    }

    /** Control Room verifies completion reports or returns them for follow-up. */
    @Transactional
    public void verifyCompletion(String taskId, String action, String note, String controlUser) {
        String cleanAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        String cleanNote = note == null ? "" : note.trim().replaceAll("\\s+", " ");
        if (!List.of("CONFIRM", "RETURN").contains(cleanAction))
            throw new IllegalArgumentException("Choose Confirm or Return for follow-up.");
        if (cleanAction.equals("RETURN") && (cleanNote.length() < 5 || cleanNote.length() > 500))
            throw new IllegalArgumentException("Enter a return reason between 5 and 500 characters.");
        if (cleanAction.equals("CONFIRM") && cleanNote.length() > 500)
            throw new IllegalArgumentException("Verification note must be 500 characters or fewer.");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, completion_status FROM maintenance_task WHERE task_id = ? FOR UPDATE", taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Task " + taskId + " was not found.");
        Map<String, Object> task = rows.get(0);
        if (!"APPROVED".equals(String.valueOf(task.get("status"))))
            throw new IllegalArgumentException("Only an approved task can have its completion verified.");
        if (!"REPORTED".equals(String.valueOf(task.get("completion_status"))))
            throw new IllegalArgumentException("This task has no pending completion report. Refresh the dashboard.");

        String safeControl = safeUsername(controlUser);
        if (cleanAction.equals("CONFIRM")) {
            jdbc.update("""
                    UPDATE maintenance_task SET completion_status = 'COMPLETED',
                        completed_at = CURRENT_TIMESTAMP, completed_by = ?, completion_note = ?
                    WHERE task_id = ? AND completion_status = 'REPORTED'
                    """, safeControl, cleanNote.isBlank() ? null : cleanNote, taskId);
            jdbc.update("INSERT INTO completion_history (task_id, event_type, performed_by, event_note) VALUES (?, 'VERIFIED', ?, ?)",
                    taskId, safeControl, cleanNote.isBlank() ? "Control Room verified completion." : cleanNote);
        } else {
            jdbc.update("""
                    UPDATE maintenance_task SET completion_status = 'RETURNED',
                        completed_at = NULL, completed_by = NULL, completion_note = ?
                    WHERE task_id = ? AND completion_status = 'REPORTED'
                    """, cleanNote, taskId);
            jdbc.update("INSERT INTO completion_history (task_id, event_type, performed_by, event_note) VALUES (?, 'RETURNED', ?, ?)",
                    taskId, safeControl, cleanNote);
        }
    }

    /** Saves what Control Room did after a missing or unsuccessful work report. */
    @Transactional
    public void recordFollowUp(String taskId, String note, String controlUser) {
        String cleanNote = note == null ? "" : note.trim().replaceAll("\\s+", " ");
        if (cleanNote.length() < 5 || cleanNote.length() > 500)
            throw new IllegalArgumentException("Enter a follow-up note between 5 and 500 characters.");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, completion_status FROM maintenance_task WHERE task_id = ? FOR UPDATE", taskId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Task " + taskId + " was not found.");
        Map<String, Object> task = rows.get(0);
        if (!"APPROVED".equals(String.valueOf(task.get("status"))))
            throw new IllegalArgumentException("Follow-up is available only for approved work.");
        String current = String.valueOf(task.get("completion_status"));
        if (List.of("COMPLETED", "REPORTED").contains(current))
            throw new IllegalArgumentException("This task is already completed or waiting for verification.");

        if ("NOT_STARTED".equals(current)) {
            List<Map<String, Object>> blocks = jdbc.queryForList(
                    "SELECT schedule_date, end_time, NOW() < STR_TO_DATE(CONCAT(DATE_FORMAT(schedule_date, '%Y-%m-%d'), ' ', end_time), '%Y-%m-%d %H:%i') AS before_end FROM block_schedule WHERE task_id = ?", taskId);
            if (blocks.isEmpty()) throw new IllegalArgumentException("No approved block exists for this task.");
            if (asBoolean(blocks.get(0).get("before_end")))
                throw new IllegalArgumentException("Follow-up becomes available after the approved work time ends.");
        }

        String safe = safeUsername(controlUser);
        jdbc.update("UPDATE maintenance_task SET completion_note = ? WHERE task_id = ?", cleanNote, taskId);
        jdbc.update("INSERT INTO completion_history (task_id, event_type, performed_by, event_note) VALUES (?, 'FOLLOW_UP', ?, ?)",
                taskId, safe, cleanNote);
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private String safeUsername(String username) {
        if (username == null || username.isBlank()) return "unknown";
        return username.length() > 40 ? username.substring(0, 40) : username;
    }
}
