package com.smartblock.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.sql.Date;
import java.util.List;
import java.util.Map;

@Repository
public class ReportRepo {
    private final JdbcTemplate jdbc;
    public ReportRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> activeBlocks() {
        return jdbc.queryForList("SELECT * FROM active_block_view ORDER BY schedule_date, section_id, start_time");
    }

    public List<Map<String, Object>> activeBlocks(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT b.block_id, b.schedule_date, b.start_time, b.end_time, b.section_id,
                       b.task_id, t.work_desc, d.dept_name, t.priority, b.approved_by
                FROM block_schedule b JOIN maintenance_task t ON t.task_id = b.task_id
                JOIN department d ON d.dept_id = t.dept_id
                WHERE b.schedule_date BETWEEN ? AND ? ORDER BY b.schedule_date, b.start_time, b.section_id
                """, Date.valueOf(from), Date.valueOf(to));
    }

    public List<Map<String, Object>> departmentWork() {
        return jdbc.queryForList("SELECT * FROM department_work_view");
    }

    public List<Map<String, Object>> sectionUsage() {
        return jdbc.queryForList("SELECT * FROM section_utilization_view");
    }

    public List<Map<String, Object>> auditTrail() {
        return jdbc.queryForList("""
                SELECT log_id, block_id, action, performed_by, log_time
                FROM audit_log ORDER BY log_id DESC LIMIT 25
                """);
    }

    public List<Map<String, Object>> conflictLog() {
        return jdbc.queryForList("SELECT * FROM conflict_log ORDER BY conflict_id DESC LIMIT 25");
    }

    public Map<String, Object> counters() {
        Integer requests = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_task", Integer.class);
        Integer approved = jdbc.queryForObject("SELECT COUNT(*) FROM block_schedule", Integer.class);
        Integer clashes  = jdbc.queryForObject("SELECT COUNT(*) FROM conflict_log", Integer.class);
        Integer critical = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_task WHERE priority = 'Critical'", Integer.class);

        return Map.of("requests", requests, "approved", approved,
                      "conflicts", clashes, "critical", critical);
    }


}
