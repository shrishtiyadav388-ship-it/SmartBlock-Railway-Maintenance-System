package com.smartblock.service;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
public class BlockService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public BlockService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(15);
    }

    public static class SectionBusyException extends RuntimeException {
        public SectionBusyException(String message) { super(message); }
    }

    public int approveBlock(String taskId, String sectionId, LocalDate workDate,
                            String start, String end, String by) {
        try {
            Integer result = transaction.execute(status -> approveInsideTransaction(
                    taskId, sectionId, workDate, start, end, by));
            if (result == null || result < 1) throw new SectionBusyException("The block could not be saved.");
            return result;
        } catch (PessimisticLockingFailureException ex) {
        
            throw new SectionBusyException("Database lock conflict while reserving section and team. No partial booking was saved; retry or choose another slot.");
        }
    }

    private int approveInsideTransaction(String taskId, String sectionId, LocalDate workDate,
                                        String start, String end, String by) 
        try {
            jdbc.queryForObject("SELECT section_id FROM section WHERE section_id = ? FOR UPDATE",
                    String.class, sectionId);
        } catch (EmptyResultDataAccessException ex) {
            throw new SectionBusyException("Section " + sectionId + " is not registered.");
        }

        Map<String, Object> task;
        try {
            task = jdbc.queryForMap("""
                    SELECT t.status, t.work_date, t.section_id, t.arrival_time, t.duration_min,
                           t.required_workers, t.requested_team, r.requested_start, r.requested_end,
                           CASE d.dept_name
                             WHEN 'Engineering' THEN 'Track maintenance'
                             WHEN 'Signal' THEN 'Signal maintenance'
                             WHEN 'Traction' THEN 'Electrical maintenance'
                             ELSE 'General maintenance'
                           END AS worker_skill
                    FROM maintenance_task t
                    JOIN block_request r ON r.task_id = t.task_id
                    JOIN department d ON d.dept_id = t.dept_id
                    WHERE t.task_id = ? FOR UPDATE
                    """, taskId);
        } catch (EmptyResultDataAccessException ex) {
            throw new SectionBusyException("The maintenance request no longer exists.");
        }

        if (!"PENDING".equalsIgnoreCase(String.valueOf(task.get("status")))) {
            throw new SectionBusyException("This request is no longer pending approval.");
        }
        if (!Date.valueOf(workDate).equals(task.get("work_date"))
                || !sectionId.equalsIgnoreCase(String.valueOf(task.get("section_id")))) {
            throw new SectionBusyException("The proposed date or section does not match the request.");
        }
        if (start == null || end == null || start.compareTo(end) >= 0) {
            throw new SectionBusyException("Enter a valid start and end time.");
        }
        String requestedStart = String.valueOf(task.get("requested_start"));
        String requestedEnd = String.valueOf(task.get("requested_end"));
        String arrival = String.valueOf(task.get("arrival_time"));
        int duration = ((Number) task.get("duration_min")).intValue();
        int requiredWorkers = ((Number) task.get("required_workers")).intValue();
        String requestedTeam = String.valueOf(task.get("requested_team")).toUpperCase(Locale.ROOT);
        String skill = String.valueOf(task.get("worker_skill"));

        if (workDate.isBefore(LocalDate.now()))
            throw new SectionBusyException("A past maintenance date cannot be approved.");
        if (workDate.equals(LocalDate.now()) && start.compareTo(LocalTime.now().withSecond(0).withNano(0).toString().substring(0, 5)) < 0)
            throw new SectionBusyException("The requested start time has already passed today.");
        if (start.compareTo(requestedStart) < 0 || end.compareTo(requestedEnd) > 0 || start.compareTo(arrival) < 0)
            throw new SectionBusyException("The proposed time must fit inside the department's requested window.");
        if (minutesBetween(start, end) < duration)
            throw new SectionBusyException("The proposed block is shorter than the required work duration.");

        Integer sectionOverlap = jdbc.queryForObject("""
                SELECT COUNT(*) FROM block_schedule
                WHERE section_id = ? AND schedule_date = ? AND start_time < ? AND end_time > ?
                """, Integer.class, sectionId, Date.valueOf(workDate), end, start);
        if (sectionOverlap != null && sectionOverlap > 0)
            throw new SectionBusyException("Section " + sectionId + " is already booked during this time.");

        Integer trainOverlap = jdbc.queryForObject("""
                SELECT COUNT(*) FROM train_schedule
                WHERE section_id = ? AND service_date = ?
                  AND pass_start IS NOT NULL AND pass_end IS NOT NULL
                  AND pass_start < ? AND pass_end > ?
                """, Integer.class, sectionId, Date.valueOf(workDate), end, start);
        if (trainOverlap != null && trainOverlap > 0)
            throw new SectionBusyException("This time overlaps a train movement window.");

        // Lock both team rows in stable worker_id order. Preferred team is chosen first;
        // if two teams are required, both must be available. The row locks serialize
        // concurrent approvals competing for the same department crews.
        List<Map<String, Object>> qualifiedWorkers = jdbc.query("""
                SELECT worker_id, team_code, worker_code, worker_name
                FROM worker_resource
                WHERE active = TRUE AND skill = ?
                ORDER BY worker_id FOR UPDATE
                """, (rs, rowNum) -> {
                    Map<String, Object> w = new HashMap<>();
                    w.put("worker_id", rs.getInt("worker_id"));
                    w.put("team_code", rs.getString("team_code"));
                    w.put("worker_code", rs.getString("worker_code"));
                    w.put("worker_name", rs.getString("worker_name"));
                    return w;
                }, skill);
        List<Integer> qualifiedWorkerIds = qualifiedWorkers.stream()
                .map(w -> (Integer) w.get("worker_id")).toList();
        if (qualifiedWorkerIds.isEmpty())
            throw new SectionBusyException("No active worker is registered for skill: " + skill + ".");

        // Current-read lock makes this check see committed assignments after any worker-row wait.
        List<Integer> busyWorkerIds = jdbc.query("""
                SELECT a.worker_id
                FROM block_worker_assignment a
                JOIN block_schedule b ON b.block_id = a.block_id
                WHERE b.schedule_date = ? AND b.start_time < ? AND b.end_time > ?
                FOR UPDATE
                """, (rs, rowNum) -> rs.getInt(1), Date.valueOf(workDate), end, start);
        Set<Integer> busy = new HashSet<>(busyWorkerIds);

        Integer unassignedOverlaps = jdbc.queryForObject("""
                SELECT COUNT(*) FROM block_schedule b
                JOIN maintenance_task t ON t.task_id = b.task_id
                JOIN department d ON d.dept_id = t.dept_id
                WHERE b.schedule_date = ? AND b.start_time < ? AND b.end_time > ?
                  AND CASE d.dept_name
                        WHEN 'Engineering' THEN 'Track maintenance'
                        WHEN 'Signal' THEN 'Signal maintenance'
                        WHEN 'Traction' THEN 'Electrical maintenance'
                        ELSE 'General maintenance'
                      END = ?
                  AND NOT EXISTS (SELECT 1 FROM block_worker_assignment a WHERE a.block_id = b.block_id)
                """, Integer.class, Date.valueOf(workDate), end, start, skill);
        if (unassignedOverlaps != null && unassignedOverlaps > 0)
            throw new SectionBusyException("An older overlapping block has no saved worker assignment. Review that block before allocating more workers.");

        List<Map<String, Object>> orderedWorkers = new ArrayList<>(qualifiedWorkers);
        if ("A".equals(requestedTeam) || "B".equals(requestedTeam)) {
            orderedWorkers.sort(Comparator
                    .comparing((Map<String, Object> w) -> !requestedTeam.equalsIgnoreCase(String.valueOf(w.get("team_code"))))
                    .thenComparing(w -> (Integer) w.get("worker_id")));
        }
        List<Integer> chosen = new ArrayList<>();
        List<String> chosenLabels = new ArrayList<>();
        for (Map<String, Object> worker : orderedWorkers) {
            Integer workerId = (Integer) worker.get("worker_id");
            if (!busy.contains(workerId)) {
                chosen.add(workerId);
                chosenLabels.add(String.valueOf(worker.get("worker_name")) + " (" + worker.get("worker_code") + ")");
            }
            if (chosen.size() == requiredWorkers) break;
        }
        if (chosen.size() < requiredWorkers)
            throw new SectionBusyException("Worker resource conflict: only " + chosen.size() + " qualified team(s) are free; "
                    + requiredWorkers + " required. Preferred team: " + requestedTeam + ". Try another time or choose Any available team.");

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO block_schedule (section_id, task_id, schedule_date, start_time, end_time, approved_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sectionId);
            ps.setString(2, taskId);
            ps.setDate(3, Date.valueOf(workDate));
            ps.setString(4, start);
            ps.setString(5, end);
            ps.setString(6, by);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("The approved block ID was not returned.");
        int blockId = key.intValue();

        for (Integer workerId : chosen) {
            jdbc.update("INSERT INTO block_worker_assignment (block_id, worker_id) VALUES (?, ?)", blockId, workerId);
        }
        jdbc.update("INSERT INTO audit_log (block_id, action, performed_by) VALUES (?, ?, ?)",
                blockId, "WORKERS_ASSIGNED", by);
        return blockId;
    }

    public List<String> assignedWorkerLabels(int blockId) {
        return jdbc.query("""
                SELECT w.worker_name
                FROM block_worker_assignment a
                JOIN worker_resource w ON w.worker_id = a.worker_id
                WHERE a.block_id = ? ORDER BY w.worker_code
                """, (rs, rowNum) -> rs.getString(1), blockId);
    }

    private int minutesBetween(String start, String end) {
        return (int) java.time.Duration.between(LocalTime.parse(start), LocalTime.parse(end)).toMinutes();
    }
}
