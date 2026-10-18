package com.smartblock.web;

import com.smartblock.model.Task;
import com.smartblock.repo.PlanningRepo;
import com.smartblock.service.PlanningService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlanController {
    private final PlanningRepo repo;
    private final PlanningService planner;

    public PlanController(PlanningRepo repo, PlanningService planner) {
        this.repo = repo;
        this.planner = planner;
    }

    @GetMapping("/tasks")
    public List<Task> tasks() { return repo.findAllTasks(); }

    @GetMapping("/conflicts")
    public Object conflicts(@RequestParam(required = false) LocalDate date) {
        return planner.detectAndSaveConflicts(todayIfMissing(date));
    }

    @GetMapping("/scheduling")
    public Map<String, Object> scheduling(@RequestParam(required = false) LocalDate date) {
        return planner.compareScheduling(todayIfMissing(date));
    }

    @GetMapping("/allocation")
    public Object allocation(@RequestParam(required = false) LocalDate date) {
        return planner.allocation(todayIfMissing(date));
    }


    @GetMapping("/plan")
    public Map<String, Object> plan(@RequestParam(required = false) LocalDate date) {
        return planner.buildPlan(todayIfMissing(date));
    }

    @PostMapping("/plan/approve")
    public Map<String, Object> approve(@RequestParam(required = false) LocalDate date, HttpSession session) {
        if (!isController(session)) return Map.of("saved", List.of(),
                "failed", List.of("Only the Control Room approver can approve a plan."),
                "verification", "-");

        LocalDate day = todayIfMissing(date);
        if (day.isBefore(LocalDate.now())) return Map.of("date", day.toString(),
                "saved", List.of(), "failed", List.of("A past date can be viewed but cannot be approved."),
                "verification", "-");

        Object user = session.getAttribute("username");
        return planner.approvePlan(day, user == null ? "control" : user.toString());
    }

    private LocalDate todayIfMissing(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }

    private boolean isController(HttpSession session) {
        return "CONTROL".equals(session.getAttribute("role"));
    }

}
