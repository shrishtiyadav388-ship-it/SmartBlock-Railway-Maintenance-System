package com.smartblock.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class RequestQueryRepo {
    private final JdbcTemplate jdbc;
    public RequestQueryRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> findRequestsForDept(String deptName) {
        String sql = """
                SELECT t.task_id, t.work_date, t.work_desc, t.section_id, t.priority, t.status,
                       t.duration_min, t.arrival_time, t.decision_reason, t.status_updated_at,
                       t.supersedes_task_id,
                       CASE WHEN b.task_id IS NOT NULL AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.start_time), '%Y-%m-%d %H:%i') THEN 1 ELSE 0 END AS work_started,
                       CASE WHEN b.task_id IS NOT NULL AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') THEN 1 ELSE 0 END AS work_ended,
                       CASE WHEN t.status = 'RESCHEDULE_REQUIRED' THEN 'RESCHEDULE_REQUIRED'
                            WHEN t.status <> 'APPROVED' THEN 'NOT_APPLICABLE'
                            WHEN t.completion_status = 'COMPLETED' THEN 'COMPLETED'
                            WHEN t.completion_status = 'REPORTED' THEN 'AWAITING_VERIFICATION'
                            WHEN t.completion_status = 'NOT_COMPLETED' THEN 'NOT_COMPLETED'
                            WHEN t.completion_status = 'RETURNED' THEN 'NEEDS_FOLLOW_UP'
                            WHEN b.task_id IS NOT NULL AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') THEN 'REPORT_MISSING'
                            WHEN b.task_id IS NOT NULL AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.start_time), '%Y-%m-%d %H:%i') THEN 'IN_PROGRESS'
                            WHEN b.task_id IS NULL THEN 'SCHEDULED'
                            ELSE 'SCHEDULED' END AS completion_status,
                       DATE_FORMAT(t.completion_reported_at, '%Y-%m-%d %H:%i') AS completion_reported_at,
                       t.completion_reported_by, t.completion_report_note,
                       DATE_FORMAT(t.status_requested_at, '%Y-%m-%d %H:%i') AS status_requested_at,
                       t.status_requested_by, t.status_request_note,
                       DATE_FORMAT(t.completed_at, '%Y-%m-%d %H:%i') AS completed_at, t.completed_by, t.completion_note,
                       r.requested_start, r.requested_end, b.schedule_date, b.start_time, b.end_time
                FROM maintenance_task t JOIN department d ON d.dept_id = t.dept_id
                LEFT JOIN block_request r ON r.task_id = t.task_id LEFT JOIN block_schedule b ON b.task_id = t.task_id
                WHERE d.dept_name = ? ORDER BY t.work_date, t.arrival_time
                """;
        return jdbc.queryForList(sql, deptName);
    }

    public List<Map<String, Object>> findControlRequests(LocalDate from, LocalDate to) {
        String sql = """
                SELECT t.task_id AS taskId, DATE_FORMAT(t.work_date, '%Y-%m-%d') AS workDate,
                       d.dept_name AS department, t.section_id AS sectionId, s.section_name AS sectionName,
                       t.work_desc AS workDesc, t.priority AS priority, t.duration_min AS durationMin,
                       t.decision_reason AS decisionReason, t.status_updated_at AS statusUpdatedAt,
                       t.supersedes_task_id AS supersedesTaskId, t.arrival_time AS arrivalTime,
                       CASE WHEN t.status = 'RESCHEDULE_REQUIRED' THEN 'RESCHEDULE_REQUIRED'
                            WHEN t.status <> 'APPROVED' THEN 'NOT_APPLICABLE'
                            WHEN t.completion_status = 'COMPLETED' THEN 'COMPLETED'
                            WHEN t.completion_status = 'REPORTED' THEN 'AWAITING_VERIFICATION'
                            WHEN t.completion_status = 'NOT_COMPLETED' THEN 'NOT_COMPLETED'
                            WHEN t.completion_status = 'RETURNED' THEN 'NEEDS_FOLLOW_UP'
                            WHEN b.task_id IS NOT NULL AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') THEN 'REPORT_MISSING'
                            WHEN b.task_id IS NOT NULL AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.start_time), '%Y-%m-%d %H:%i') THEN 'IN_PROGRESS'
                            WHEN b.task_id IS NULL THEN 'SCHEDULED'
                            ELSE 'SCHEDULED' END AS completionStatus,
                       DATE_FORMAT(t.completion_reported_at, '%Y-%m-%d %H:%i') AS reportedAt,
                       t.completion_reported_by AS reportedBy, t.completion_report_note AS completionReportNote, DATE_FORMAT(t.completed_at, '%Y-%m-%d %H:%i') AS completedAt,
                       t.completed_by AS completedBy, t.completion_note AS completionNote,
                       DATE_FORMAT(t.status_requested_at, '%Y-%m-%d %H:%i') AS statusRequestedAt,
                       t.status_requested_by AS statusRequestedBy, t.status_request_note AS statusRequestNote,
                       COALESCE(r.requested_start, t.arrival_time) AS requestedStart, COALESCE(r.requested_end, '17:00') AS requestedEnd,
                       t.status AS status, b.start_time AS approvedStart, b.end_time AS approvedEnd, b.approved_by AS approvedBy,
                       CASE WHEN t.status IN ('PENDING','APPROVED') AND EXISTS (
                         SELECT 1 FROM block_request r2 JOIN maintenance_task t2 ON t2.task_id = r2.task_id
                         WHERE t2.status IN ('PENDING','APPROVED') AND r2.section_id = t.section_id
                           AND t2.work_date = t.work_date AND t2.task_id <> t.task_id
                           AND r.requested_start < r2.requested_end AND r.requested_end > r2.requested_start
                       ) THEN 1 ELSE 0 END AS hasConflict
                FROM maintenance_task t JOIN department d ON d.dept_id = t.dept_id JOIN section s ON s.section_id = t.section_id
                LEFT JOIN block_request r ON r.task_id = t.task_id LEFT JOIN block_schedule b ON b.task_id = t.task_id
                WHERE t.work_date BETWEEN ? AND ? ORDER BY t.work_date, requestedStart, t.task_id
                """;
        return jdbc.queryForList(sql, Date.valueOf(from), Date.valueOf(to));
    }

    public List<Map<String, Object>> findCompletionStatusForControl() {
        return jdbc.queryForList("""
                SELECT t.task_id AS taskId,
                       DATE_FORMAT(b.schedule_date, '%Y-%m-%d') AS workDate,
                       d.dept_name AS department, t.section_id AS sectionId,
                       s.section_name AS sectionName, t.work_desc AS workDesc,
                       t.priority AS priority,
                       CASE WHEN t.completion_status = 'COMPLETED' THEN 'COMPLETED'
                            WHEN t.completion_status = 'REPORTED' THEN 'AWAITING_VERIFICATION'
                            WHEN t.completion_status = 'NOT_COMPLETED' THEN 'NOT_COMPLETED'
                            WHEN t.completion_status = 'RETURNED' THEN 'NEEDS_FOLLOW_UP'
                            WHEN NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') THEN 'REPORT_MISSING'
                            WHEN NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.start_time), '%Y-%m-%d %H:%i') THEN 'IN_PROGRESS'
                            ELSE 'SCHEDULED' END AS completionStatus,
                       DATE_FORMAT(t.completion_reported_at, '%Y-%m-%d %H:%i') AS reportedAt,
                       t.completion_reported_by AS reportedBy, t.completion_report_note AS completionReportNote, DATE_FORMAT(t.completed_at, '%Y-%m-%d %H:%i') AS completedAt,
                       t.completed_by AS completedBy, t.completion_note AS completionNote,
                       DATE_FORMAT(t.status_requested_at, '%Y-%m-%d %H:%i') AS statusRequestedAt,
                       t.status_requested_by AS statusRequestedBy, t.status_request_note AS statusRequestNote
                FROM maintenance_task t
                JOIN department d ON d.dept_id = t.dept_id
                JOIN block_schedule b ON b.task_id = t.task_id
                JOIN section s ON s.section_id = t.section_id
                WHERE t.status = 'APPROVED'
                ORDER BY CASE WHEN t.completion_status = 'REPORTED' THEN 0
                              WHEN t.completion_status IN ('NOT_COMPLETED','RETURNED') THEN 1
                              WHEN t.completion_status = 'NOT_STARTED' AND NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.end_time), '%Y-%m-%d %H:%i') THEN 2
                              WHEN t.completion_status = 'COMPLETED' THEN 5
                              WHEN NOW() >= STR_TO_DATE(CONCAT(DATE_FORMAT(b.schedule_date, '%Y-%m-%d'), ' ', b.start_time), '%Y-%m-%d %H:%i') THEN 3 ELSE 4 END,
                         b.schedule_date, b.start_time, t.task_id
                """);
    }

    public List<String[]> findAllRequests() {
        return jdbc.query("""
                SELECT r.task_id, t.work_date, r.section_id, r.requested_start, r.requested_end
                FROM block_request r
                JOIN maintenance_task t ON t.task_id = r.task_id
                ORDER BY t.work_date, r.section_id, r.requested_start
                """, (rs, rowNum) -> new String[]{
                        rs.getString("task_id"), rs.getDate("work_date").toLocalDate().toString(),
                        rs.getString("section_id"), rs.getString("requested_start"),
                        rs.getString("requested_end")});
    }

    public Integer findDeptId(String deptName) {
        List<Integer> ids = jdbc.query("SELECT dept_id FROM department WHERE dept_name = ?",
                (rs, rowNum) -> rs.getInt("dept_id"), deptName);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public String nextTaskId() {
        jdbc.update("INSERT INTO task_id_sequence () VALUES ()");
        Long number = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return "P" + number;
    }

}
