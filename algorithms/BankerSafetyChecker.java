package com.smartblock.os;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class BankersSafetyChecker {
    private BankersSafetyChecker() { }


     */
    public static Result checkRequest(String process, int[] available, int[] allocation, int[] maximum) {
        if (available == null || allocation == null || maximum == null
                || available.length != maximum.length || allocation.length != maximum.length) {
            throw new IllegalArgumentException("Resource vectors must have the same length.");
        }
        int[][] allocations = { Arrays.copyOf(allocation, allocation.length) };
        int[][] maxima = { Arrays.copyOf(maximum, maximum.length) };
        return checkState(available, allocations, maxima, List.of(process));
    }

  
    public static Result checkState(int[] available, int[][] allocation,
                                    int[][] maximum, List<String> processNames) {
        if (available == null || allocation == null || maximum == null || processNames == null
                || allocation.length != maximum.length || allocation.length != processNames.size()) {
            throw new IllegalArgumentException("Banker's matrices and process names do not match.");
        }

        int processCount = allocation.length;
        int resourceCount = available.length;
        int[][] need = new int[processCount][resourceCount];
        for (int p = 0; p < processCount; p++) {
            if (allocation[p].length != resourceCount || maximum[p].length != resourceCount) {
                throw new IllegalArgumentException("Every resource row must have the same length.");
            }
            for (int r = 0; r < resourceCount; r++) {
                if (available[r] < 0 || allocation[p][r] < 0 || maximum[p][r] < allocation[p][r]) {
                    throw new IllegalArgumentException("Invalid Banker's Algorithm resource values.");
                }
                need[p][r] = maximum[p][r] - allocation[p][r];
            }
        }

        int[] work = Arrays.copyOf(available, resourceCount);
        boolean[] finish = new boolean[processCount];
        List<String> sequence = new ArrayList<>();
        boolean progressed;
        do {
            progressed = false;
            for (int p = 0; p < processCount; p++) {
                if (finish[p]) continue;
                boolean canFinish = true;
                for (int r = 0; r < resourceCount; r++) {
                    if (need[p][r] > work[r]) {
                        canFinish = false;
                        break;
                    }
                }
                if (canFinish) {
                    for (int r = 0; r < resourceCount; r++) work[r] += allocation[p][r];
                    finish[p] = true;
                    sequence.add(processNames.get(p));
                    progressed = true;
                }
            }
        } while (progressed);

        boolean safe = sequence.size() == processCount;
        String explanation = safe
                ? "Safe sequence exists: " + String.join(" → ", sequence)
                : "Unsafe state: no safe sequence can finish every process.";
        return new Result(safe, sequence, explanation);
    }

    public static final class Result {
        public final boolean safe;
        public final List<String> safeSequence;
        public final String explanation;

        public Result(boolean safe, List<String> safeSequence, String explanation) {
            this.safe = safe;
            this.safeSequence = List.copyOf(safeSequence);
            this.explanation = explanation;
        }
    }
}
