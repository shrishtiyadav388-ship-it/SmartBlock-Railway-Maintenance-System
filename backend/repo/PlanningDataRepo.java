package com.smartblock.repo;

import com.smartblock.model.Conflict;
import com.smartblock.model.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class PlanningDataRepo {
    private final JdbcTemplate jdbc;
    public PlanningDataRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Task> findAllTasks() {
        return queryTasks("", new Object[0]);
    }

    public List<Task> findTasksForDate(LocalDate workDate) {
        return queryTasks(" WHERE t.work_date = ?", Date.valueOf(workDate));
    }

    private List<Task> queryTasks(String filter, Object... args) {
        String sql = """
                SELECT t.task_id, t.work_date, d.dept_name, t.section_id, t.work_desc,
                       t.priority, t.duration_min, t.arrival_time, t.status,
                       COALESCE(r.requested_start, t.arrival_time) AS requested_start,
                       COALESCE(r.requested_end, '17:00') AS requested_end
                FROM maintenance_task t JOIN department d ON d.dept_id = t.dept_id
                LEFT JOIN block_request r ON r.task_id = t.task_id
                """ + filter + " ORDER BY t.work_date, t.arrival_time, t.task_id";

        return jdbc.query(sql, (rs, rowNum) -> {
            Task task = new Task(rs.getString("task_id"), rs.getString("dept_name"),
                    rs.getString("section_id"), rs.getString("work_desc"),
                    rs.getString("priority"), rs.getInt("duration_min"),
                    rs.getString("arrival_time"), rs.getString("status"));
            task.workDate = rs.getDate("work_date").toLocalDate().toString();
            task.requestedStart = rs.getString("requested_start");
            task.requestedEnd = rs.getString("requested_end");
            task.refreshPriorityRank();
            return task;
        }, args);
    }

    public List<Conflict> findRequestConflicts(LocalDate workDate) {
        String sql = """
                SELECT a.section_id, a.task_id AS task_a, b.task_id AS task_b
                FROM block_request a JOIN maintenance_task ta ON ta.task_id = a.task_id
                JOIN block_request b ON a.section_id = b.section_id JOIN maintenance_task tb ON tb.task_id = b.task_id
                WHERE a.task_id < b.task_id AND ta.status IN ('PENDING','APPROVED') AND tb.status IN ('PENDING','APPROVED')
                  AND ta.work_date = ? AND tb.work_date = ? AND a.requested_start < b.requested_end
                  AND a.requested_end > b.requested_start ORDER BY a.section_id, a.requested_start, a.task_id
                """;

        return jdbc.query(sql, (rs, rowNum) -> new Conflict(
                rs.getString("task_a"), rs.getString("task_b"), rs.getString("section_id")),
                Date.valueOf(workDate), Date.valueOf(workDate));
    }

    public List<String[]> findTrainWindows(LocalDate workDate) {
        String sql = """
                SELECT section_id, pass_start AS window_start, pass_end AS window_end,
                       CONCAT(train_type, ' train ', train_no) AS source
                FROM train_schedule WHERE service_date = ? AND pass_start IS NOT NULL AND pass_end IS NOT NULL
                UNION ALL SELECT section_id, start_time, end_time, CONCAT('Approved block ', block_id)
                FROM block_schedule WHERE schedule_date = ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new String[]{rs.getString("section_id"),
                rs.getString("window_start"), rs.getString("window_end"), rs.getString("source")},
                Date.valueOf(workDate), Date.valueOf(workDate));
    }

    public List<String> findSectionIds() {
        return jdbc.query("SELECT section_id FROM section ORDER BY section_id",
                (rs, rowNum) -> rs.getString("section_id"));
    }

    public List<Map<String, Object>> findSections() {
        return jdbc.queryForList("SELECT section_id, section_name FROM section ORDER BY section_id");
    }

    public void saveConflict(String sectionId, LocalDate workDate, String taskA, String taskB) {
        jdbc.update("INSERT IGNORE INTO conflict_log (section_id, work_date, task_id_1, task_id_2) VALUES (?,?,?,?)",
                sectionId, Date.valueOf(workDate), taskA, taskB);
    }

    public List<String> freeSections(LocalDate workDate) {
        return jdbc.query("""
                SELECT s.section_id FROM section s
                WHERE NOT EXISTS (
                    SELECT 1 FROM block_schedule b
                    WHERE b.section_id = s.section_id AND b.schedule_date = ?
                )
                ORDER BY s.section_id
                """, (rs, rowNum) -> rs.getString("section_id"), Date.valueOf(workDate));
    }

}
