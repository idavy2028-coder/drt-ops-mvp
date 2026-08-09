package com.idavy.drtops.domain.task;

import com.idavy.drtops.domain.dispatch.TaskInsertionPlan;
import com.idavy.drtops.domain.order.RideOrder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskStopInsertionPolicy {

    public void applyPlan(VehicleTask task, RideOrder order, TaskInsertionPlan plan) {
        if (!plan.feasible()) {
            throw new IllegalArgumentException("cannot apply an infeasible insertion plan");
        }
        validateNewOrderStops(order, plan.orderedStops());

        Map<UUID, TaskStop> remainingById = new HashMap<>();
        for (TaskStop stop : task.getStops()) {
            if (!stop.isExecutionComplete()) {
                remainingById.put(stop.getId(), stop);
            }
        }
        Set<UUID> referencedExistingIds = new HashSet<>();
        for (TaskInsertionPlan.PlannedTaskStop planned : plan.orderedStops()) {
            if (planned.existingStopId() != null) {
                referencedExistingIds.add(planned.existingStopId());
            }
        }
        if (!referencedExistingIds.equals(remainingById.keySet())) {
            throw new IllegalArgumentException("insertion plan does not match remaining task stops");
        }

        for (TaskInsertionPlan.PlannedTaskStop planned : plan.orderedStops()) {
            if (planned.existingStopId() == null) {
                continue;
            }
            TaskStop stop = remainingById.get(planned.existingStopId());
            if (!planned.virtualStopId().equals(stop.getVirtualStopId())
                    || !planned.rideOrderId().equals(stop.getRideOrderId())
                    || !planned.stopType().equals(stop.getStopType())) {
                throw new IllegalArgumentException("insertion plan changed an existing task stop identity");
            }
        }

        List<TaskStop> reordered = new ArrayList<>();
        for (TaskInsertionPlan.PlannedTaskStop planned : plan.orderedStops()) {
            TaskStop stop;
            if (planned.existingStopId() == null) {
                stop = TaskStop.planned(
                        planned.virtualStopId(), planned.rideOrderId(), reordered.size() + 1,
                        planned.stopType(), planned.plannedArrivalAt());
            } else {
                stop = remainingById.get(planned.existingStopId());
                stop.reschedule(planned.plannedArrivalAt());
            }
            reordered.add(stop);
        }
        task.replaceRemainingStops(reordered);
    }

    private void validateNewOrderStops(
            RideOrder order,
            List<TaskInsertionPlan.PlannedTaskStop> plannedStops) {
        int boardingIndex = -1;
        int alightingIndex = -1;
        for (int index = 0; index < plannedStops.size(); index++) {
            TaskInsertionPlan.PlannedTaskStop stop = plannedStops.get(index);
            if (stop.existingStopId() != null || !order.getId().equals(stop.rideOrderId())) {
                continue;
            }
            if ("BOARDING".equals(stop.stopType())) {
                if (boardingIndex >= 0 || !order.getBoardingStopId().equals(stop.virtualStopId())) {
                    throw new IllegalArgumentException("insertion plan has invalid new boarding stop");
                }
                boardingIndex = index;
            } else if ("ALIGHTING".equals(stop.stopType())) {
                if (alightingIndex >= 0 || !order.getAlightingStopId().equals(stop.virtualStopId())) {
                    throw new IllegalArgumentException("insertion plan has invalid new alighting stop");
                }
                alightingIndex = index;
            }
        }
        if (boardingIndex < 0 || alightingIndex <= boardingIndex) {
            throw new IllegalArgumentException("new boarding stop must precede new alighting stop");
        }
    }

    public void insertOrderStops(
            VehicleTask task,
            UUID boardingStopId,
            UUID alightingStopId,
            UUID rideOrderId,
            OffsetDateTime boardingAt,
            OffsetDateTime alightingAt) {
        int originalSize = task.getStops().size();
        TaskStop boarding = TaskStop.planned(
                boardingStopId,
                rideOrderId,
                originalSize + 1,
                "BOARDING",
                boardingAt);
        TaskStop alighting = TaskStop.planned(
                alightingStopId,
                rideOrderId,
                originalSize + 2,
                "ALIGHTING",
                alightingAt);

        int insertionIndex = sameStopInsertionIndex(task.getStops(), boardingStopId);
        if (insertionIndex < 0) {
            task.insertStop(task.getStops().size(), boarding);
        } else {
            task.insertStop(insertionIndex, boarding);
        }
        task.insertStop(task.getStops().size(), alighting);
    }

    private static int sameStopInsertionIndex(List<TaskStop> stops, UUID boardingStopId) {
        for (int index = 0; index < stops.size(); index++) {
            TaskStop stop = stops.get(index);
            if (!isEligibleMatch(stop, boardingStopId)) {
                continue;
            }
            int groupEnd = index + 1;
            while (groupEnd < stops.size() && isSameBoardingStop(stops.get(groupEnd), boardingStopId)) {
                groupEnd++;
            }
            return groupEnd;
        }
        return -1;
    }

    private static boolean isEligibleMatch(TaskStop stop, UUID boardingStopId) {
        return isSameBoardingStop(stop, boardingStopId)
                && ("PLANNED".equals(stop.getStatus()) || "ARRIVED".equals(stop.getStatus()));
    }

    private static boolean isSameBoardingStop(TaskStop stop, UUID boardingStopId) {
        return "BOARDING".equals(stop.getStopType())
                && boardingStopId.equals(stop.getVirtualStopId())
                && !"CANCELLED".equals(stop.getStatus());
    }
}
