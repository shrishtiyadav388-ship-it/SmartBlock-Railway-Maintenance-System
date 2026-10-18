package com.smartblock.web;

import com.smartblock.repo.RequestRepo;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RequestController {
    private final RequestRepo requests;

    public RequestController(RequestRepo requests) {
        this.requests = requests;
    }

    @GetMapping("/control/requests")
    public Map<String, Object> controlRequests(@RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? start : to;
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end);
        if (days < 0) return Map.of("ok", false, "message", "End date must be on or after start date.", "requests", List.of());
        if (days > 366) return Map.of("ok", false, "message", "Choose a date range of 367 days or less.", "requests", List.of());
        return Map.of("ok", true, "from", start.toString(), "to", end.toString(),
                "requests", requests.findControlRequests(start, end));
    }

    @GetMapping("/control/completion-status")
    public Map<String, Object> completionStatus(HttpSession session) {
        if (!isController(session)) return error("Only the Control Room can view completion status.");
        return Map.of("ok", true, "tasks", requests.findCompletionStatusForControl());
    }

    @PostMapping("/my-requests/{taskId}/complete")
    public Map<String, Object> reportWorkStatus(@PathVariable String taskId,
                                                @RequestBody Map<String, String> body,
                                                HttpSession session) {
        Object dept = session.getAttribute("dept");
        Object username = session.getAttribute("username");
        if (!"DEPARTMENT".equals(session.getAttribute("role")) || dept == null)
            return error("Only a department can update its own task.");
        String action = String.valueOf(body.getOrDefault("action", "")).trim().toUpperCase();
        try {
            requests.reportTaskStatus(taskId, dept.toString(),
                    username == null ? dept.toString() : username.toString(), action, body.get("note"));
            return Map.of("ok", true, "message", action.equals("COMPLETED")
                    ? "Completion report sent to Control Room for verification."
                    : "Work marked as not completed. Control Room can now follow up.");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    @PostMapping("/control/tasks/{taskId}/request-status")
    public Map<String, Object> requestStatusUpdate(@PathVariable String taskId,
                                                   @RequestBody Map<String, String> body,
                                                   HttpSession session) {
        if (!isController(session)) return error("Only the Control Room can message a department.");
        Object user = session.getAttribute("username");
        try {
            requests.requestWorkStatus(taskId, body.get("note"), user == null ? "control" : user.toString());
            return Map.of("ok", true, "message", "Status request sent to the department.");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    @PostMapping("/control/tasks/{taskId}/follow-up")
    public Map<String, Object> recordFollowUp(@PathVariable String taskId,
                                              @RequestBody Map<String, String> body,
                                              HttpSession session) {
        if (!isController(session)) return error("Only the Control Room can record follow-up.");
        Object user = session.getAttribute("username");
        try {
            requests.recordCompletionFollowUp(taskId, body.get("note"), user == null ? "control" : user.toString());
            return Map.of("ok", true, "message", "Follow-up note saved for " + taskId + ".");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    @PostMapping("/control/tasks/{taskId}/reschedule")
    public Map<String, Object> requestNewSlot(@PathVariable String taskId,
                                              @RequestBody Map<String, String> body,
                                              HttpSession session) {
        if (!isController(session)) return error("Only the Control Room can request a new slot.");
        Object user = session.getAttribute("username");
        try {
            requests.markUnfinishedForRescheduling(taskId, body.get("reason"),
                    user == null ? "control" : user.toString());
            return Map.of("ok", true, "message", "A revised slot is required. The department can submit a new request.");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    @PostMapping("/control/tasks/{taskId}/completion")
    public Map<String, Object> verifyCompletion(@PathVariable String taskId,
                                                @RequestBody Map<String, String> body,
                                                HttpSession session) {
        if (!isController(session)) return error("Only the Control Room can verify work completion.");
        Object user = session.getAttribute("username");
        try {
            requests.verifyTaskCompletion(taskId, body.get("action"), body.get("note"),
                    user == null ? "control" : user.toString());
            String action = String.valueOf(body.getOrDefault("action", "")).trim().toUpperCase();
            return Map.of("ok", true, "message", action.equals("CONFIRM")
                    ? "Completion verified for " + taskId + "."
                    : "Completion report returned to the department for follow-up.");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    @GetMapping("/my-requests")
    public Object myRequests(HttpSession session) {
        Object dept = session.getAttribute("dept");
        return dept == null || dept.toString().isBlank()
                ? List.of() : requests.findRequestsForDept(dept.toString());
    }

    @GetMapping("/requests")
    public List<String[]> requests() { return requests.findAllRequests(); }

    @PostMapping("/control/requests/{taskId}/decision")
    public Map<String, Object> decideRequest(@PathVariable String taskId,
                                             @RequestBody Map<String, String> body,
                                             HttpSession session) {
        if (!isController(session)) return error("Only the Control Room can make request decisions.");
        String decision = body.getOrDefault("decision", "").trim().toUpperCase();
        String reason = body.getOrDefault("reason", "");
        Object user = session.getAttribute("username");
        try {
            requests.decidePendingRequest(taskId, decision, reason, user == null ? "control" : user.toString());
            return Map.of("ok", true, "message", "Request " + taskId + " marked " + decision.replace('_', ' ') + ".");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    @PostMapping("/my-requests/{taskId}/withdraw")
    public Map<String, Object> withdrawRequest(@PathVariable String taskId, HttpSession session) {
        Object dept = session.getAttribute("dept");
        Object user = session.getAttribute("username");
        if (!"DEPARTMENT".equals(session.getAttribute("role")) || dept == null)
            return error("Only a department can withdraw its own request.");
        try {
            requests.withdrawDepartmentRequest(taskId, dept.toString(), user == null ? dept.toString() : user.toString());
            return Map.of("ok", true, "message", "Request " + taskId + " withdrawn. Its history was retained.");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    private boolean isController(HttpSession session) {
        return "CONTROL".equals(session.getAttribute("role"));
    }

    private Map<String, Object> error(String message) {
        return Map.of("ok", false, "message", message);
    }
}
