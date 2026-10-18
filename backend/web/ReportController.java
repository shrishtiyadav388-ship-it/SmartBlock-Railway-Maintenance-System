package com.smartblock.web;

import com.smartblock.repo.PlanningRepo;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final PlanningRepo planningRepo;

    public ReportController(PlanningRepo planningRepo) {
        this.planningRepo = planningRepo;
    }

    @GetMapping("/counters")
    public Map<String, Object> counters() { return planningRepo.counters(); }

    @GetMapping("/reports/blocks")
    public Object blocks() { return planningRepo.activeBlocks(); }

    @GetMapping("/reports/blocks-range")
    public Object blocksRange(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to);
        if (days < 0) return error("End date must be on or after start date.");
        if (days > 366) return error("Choose a period of 367 days or less.");
        return Map.of("ok", true, "from", from.toString(), "to", to.toString(),
                "blocks", planningRepo.activeBlocks(from, to));
    }

    @GetMapping("/reports/departments")
    public Object departments() { return planningRepo.departmentWork(); }

    @GetMapping("/reports/sections")
    public Object sectionUsage() { return planningRepo.sectionUsage(); }

    @GetMapping("/reports/free")
    public Object freeSections(@RequestParam(required = false) LocalDate date) {
        return planningRepo.freeSections(date == null ? LocalDate.now() : date);
    }

    @GetMapping("/reports/audit")
    public Object audit() { return planningRepo.auditTrail(); }

    @GetMapping("/reports/conflicts")
    public Object conflictLog() { return planningRepo.conflictLog(); }


    private Map<String, Object> error(String message) {
        return Map.of("ok", false, "message", message, "blocks", List.of());
    }
}
