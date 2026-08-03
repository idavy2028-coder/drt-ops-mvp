package com.idavy.drtops.domain.task;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TaskStopInsertionPolicy {

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
