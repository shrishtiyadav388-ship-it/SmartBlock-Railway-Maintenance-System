package com.smartblock.web;

import com.smartblock.model.TimeHelper;
import com.smartblock.repo.RequestRepo;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TaskRequestController {
    private final RequestRepo requests;

    public TaskRequestController(RequestRepo requests) {
        this.requests = requests;
    }

    @PostMapping("/tasks")
    public Map<String, Object> addTask(@RequestBody Map<String, String> form, HttpSession session) {
        if (form == null) return error("Enter the maintenance request details.");
        if (!"DEPARTMENT".equals(session.getAttribute("role")))
            return error("Only a department can raise a request.");

        String section = text(form.get("sectionId")).trim().toUpperCase(Locale.ROOT);
        if (!List.of("S1", "S2", "S3").contains(section))
            return error("Choose one of the available sections: S1, S2, or S3.");

        LocalDate workDate;
        try {
            workDate = LocalDate.parse(text(form.get("workDate")));
            if (workDate.isBefore(LocalDate.now())) return error("Choose today or a future maintenance date.");
        } catch (DateTimeParseException ex) {
            return error("Choose a valid maintenance date.");
        }

        String work = text(form.get("workDesc")).trim().replaceAll("\\s+", " ");
        if (work.length() < 3 || work.length() > 100)
            return error("Enter a work description (3–100 characters).");

        String priority = textOr(form.get("priority"), "Medium");
        if (!List.of("Critical", "High", "Medium", "Low").contains(priority))
            return error("Choose a valid priority.");

        int duration = number(form.get("durationMin"), -1);
        if (duration < 15 || duration > 720 || duration % 15 != 0)
            return error("Duration must be 15 to 720 minutes in 15-minute steps.");

        String start = text(form.get("requestedStart"));
        String end = text(form.get("requestedEnd"));
        if (!validTime(start) || !validTime(end) || start.compareTo(end) >= 0)
            return error("Requested end must be after start.");
        if (duration > TimeHelper.toMinutes(end) - TimeHelper.toMinutes(start))
            return error("Duration must fit within the requested time.");
        String currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        if (workDate.isEqual(LocalDate.now()) && start.compareTo(currentTime) < 0)
            return error("Today's requested start has already passed. Choose a future time or date.");
        // The requested start is the process ready time for the OS scheduler.
        String arrival = start;

        try {
            String department = session.getAttribute("dept").toString();
            Object username = session.getAttribute("username");
            String requestedBy = username == null ? department : username.toString();
            String id = requests.addTask(department, requestedBy, section, workDate,
                    work, priority, duration, arrival, start, end, form.get("revisesTaskId"));
            return Map.of("ok", true, "taskId", id, "message", "Request " + id + " saved for " + workDate);
        } catch (RequestRepo.DuplicateRequestException ex) {
            return error(ex.getMessage());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return error("This exact request is already saved for that date; it was not duplicated.");
        } catch (IllegalArgumentException ex) {
            return error(ex.getMessage());
        }
    }

    private Map<String, Object> error(String message) {
        return Map.of("ok", false, "message", message);
    }

    private String text(String value) { return value == null ? "" : value; }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int number(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Exception ex) { return fallback; }
    }

    private boolean validTime(String value) {
        return value != null && value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d");
    }


}
