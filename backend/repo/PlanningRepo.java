package com.smartblock.repo;

import com.smartblock.model.Conflict;
import com.smartblock.model.Task;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
public class PlanningRepo {
    private final PlanningDataRepo data;
    private final ReportRepo reports;

    public PlanningRepo(PlanningDataRepo data, ReportRepo reports) {
        this.data = data;
        this.reports = reports;
    }

    public List<Task> findAllTasks() { return data.findAllTasks(); }
    public List<Task> findTasksForDate(LocalDate date) { return data.findTasksForDate(date); }
    public List<Conflict> findRequestConflicts(LocalDate date) { return data.findRequestConflicts(date); }
    public List<String[]> findTrainWindows(LocalDate date) { return data.findTrainWindows(date); }
    public List<String> findSectionIds() { return data.findSectionIds(); }
    public List<Map<String,Object>> findSections() { return data.findSections(); }
    public void saveConflict(String section, LocalDate date, String a, String b) { data.saveConflict(section, date, a, b); }
    public List<String> freeSections(LocalDate date) { return data.freeSections(date); }
    public List<Map<String,Object>> activeBlocks() { return reports.activeBlocks(); }
    public List<Map<String,Object>> activeBlocks(LocalDate from, LocalDate to) { return reports.activeBlocks(from, to); }
    public List<Map<String,Object>> departmentWork() { return reports.departmentWork(); }
    public List<Map<String,Object>> sectionUsage() { return reports.sectionUsage(); }
    public List<Map<String,Object>> auditTrail() { return reports.auditTrail(); }
    public List<Map<String,Object>> conflictLog() { return reports.conflictLog(); }
    public Map<String,Object> counters() { return reports.counters(); }
}
