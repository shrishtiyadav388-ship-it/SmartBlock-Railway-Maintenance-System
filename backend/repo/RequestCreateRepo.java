package com.smartblock.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.sql.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Repository
public class RequestCreateRepo {
    private final JdbcTemplate jdbc;
    private final RequestQueryRepo queries;
    private final RequestDecisionRepo decisions;

    public RequestCreateRepo(JdbcTemplate jdbc, RequestQueryRepo queries, RequestDecisionRepo decisions) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.decisions = decisions;
    }

    @Transactional
    public String addTask(String deptName, String requestedBy, String sectionId, LocalDate workDate, String workDesc,
                          String priority, int durationMin, String arrivalTime,
                          String requestedStart, String requestedEnd, String supersedesTaskId) {

        sectionId = sectionId == null ? "" : sectionId.trim().toUpperCase(Locale.ROOT);
        if (!List.of("S1", "S2", "S3").contains(sectionId)) {
            throw new IllegalArgumentException("Choose one of the available sections: S1, S2, or S3.");
        }

        Integer deptId = queries.findDeptId(deptName);
        if (deptId == null) throw new IllegalArgumentException("The logged-in department was not found.");

        String parentTaskId = supersedesTaskId == null ? "" : supersedesTaskId.trim().toUpperCase(Locale.ROOT);
        if (!parentTaskId.isBlank()) {
            List<String> parent = jdbc.query("SELECT status FROM maintenance_task WHERE task_id = ? AND dept_id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString("status"), parentTaskId, deptId);
            if (parent.isEmpty() || !"RESCHEDULE_REQUIRED".equals(parent.get(0))) {
                throw new IllegalArgumentException("Only your own request marked Rescheduling Required can be revised.");
            }
        }

        String safeWork = (workDesc == null || workDesc.isBlank()) ? "Maintenance work" : workDesc.trim();
        String fingerprint = requestFingerprint(deptId, sectionId, workDate, safeWork, priority,
                durationMin, arrivalTime, requestedStart, requestedEnd, parentTaskId);
        List<String> matches = jdbc.query("SELECT task_id FROM maintenance_task WHERE request_fingerprint = ?",
                (rs, rowNum) -> rs.getString(1), fingerprint);
        if (!matches.isEmpty()) {
            throw new RequestRepo.DuplicateRequestException("This exact request already exists as " + matches.get(0)
                    + " for " + workDate + ". It was not saved again.");
        }

        String taskId = queries.nextTaskId();
        jdbc.update("""
                INSERT INTO maintenance_task (task_id, dept_id, section_id, work_date, work_desc,
                    priority, duration_min, arrival_time, request_fingerprint, supersedes_task_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                taskId, deptId, sectionId, Date.valueOf(workDate), safeWork, priority,
                durationMin, arrivalTime, fingerprint, parentTaskId.isBlank() ? null : parentTaskId);

        jdbc.update("""
                INSERT INTO block_request (task_id, section_id, requested_start, requested_end)
                VALUES (?,?,?,?)
                """,
                taskId, sectionId, requestedStart, requestedEnd);

        String actor = requestedBy == null || requestedBy.isBlank() ? deptName : requestedBy.trim();
        if (actor.length() > 40) actor = actor.substring(0, 40);
        jdbc.update("""
                INSERT INTO request_decision_history (task_id, previous_status, new_status, reason, changed_by)
                VALUES (?, 'NEW', 'PENDING', ?, ?)
                """, taskId,
                parentTaskId.isBlank() ? "Request submitted" : "Revised request submitted", actor);

        if (!parentTaskId.isBlank()) {
            decisions.markRescheduled(parentTaskId, "Revised request submitted as " + taskId, actor);
        }
        return taskId;
    }

    private String requestFingerprint(int deptId, String sectionId, LocalDate workDate, String workDesc,
                                      String priority, int durationMin, String arrivalTime,
                                      String start, String end, String parentTaskId) {
        String normalized = deptId + "|" + sectionId.toUpperCase(Locale.ROOT) + "|" + workDate + "|"
                + workDesc.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) + "|"
                + priority.toUpperCase(Locale.ROOT) + "|" + durationMin + "|" + arrivalTime
                + "|" + start + "|" + end;
        // A legitimate revised request must not collide with its earlier version.
        if (parentTaskId != null && !parentTaskId.isBlank()) normalized += "|revision_of=" + parentTaskId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

}
