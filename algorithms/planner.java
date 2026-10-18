package com.smartblock.os;

import com.smartblock.model.Slot;
import com.smartblock.model.Task;
import com.smartblock.model.TimeHelper;
import java.util.List;

public class Planner {

    private Planner() { }

    public static List<Slot> buildPlan(List<Task> tasks, List<String[]> unavailableWindows) {
        return Scheduler.priorityWithAgingAndResources(tasks, unavailableWindows, 30);
    }

    public static String verifyPlan(List<Slot> plan, List<String[]> unavailableWindows) {
        int unscheduled = 0;
        for (int i = 0; i < plan.size(); i++) {
            Slot first = plan.get(i);
            if (!first.scheduled) {
                unscheduled++;
                continue;
            }

            for (String[] window : unavailableWindows) {
                if (window == null || window.length < 3
                        || !first.sectionId.equalsIgnoreCase(window[0])) continue;
                if (TimeHelper.overlaps(first.start, first.end, window[1], window[2])) {
                    return "Problem: " + first.taskId + " overlaps an unavailable window on " + first.sectionId;
                }
            }

            for (int j = i + 1; j < plan.size(); j++) {
                Slot second = plan.get(j);
                if (!second.scheduled) continue;
                if (first.sectionId.equals(second.sectionId)
                        && TimeHelper.overlaps(first.start, first.end, second.start, second.end)) {
                    return "Problem: " + first.taskId + " and " + second.taskId
                            + " overlap on " + first.sectionId;
                }
            }
        }
        if (unscheduled > 0) {
            return "No overlaps found. " + unscheduled
                    + " task(s) could not fit safely within the requested window on the selected date and were left unscheduled.";
        }
        return "Checked: no overlap with another planned job, a train window or an approved block.";
    }
}