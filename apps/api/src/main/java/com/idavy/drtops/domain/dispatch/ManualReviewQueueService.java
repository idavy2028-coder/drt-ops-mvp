package com.idavy.drtops.domain.dispatch;

import com.idavy.drtops.domain.order.OrderStatus;
import com.idavy.drtops.domain.order.RideOrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ManualReviewQueueService {

    private static final List<String> MANUAL_REVIEW_RESULTS = List.of(
            "MANUAL_REVIEW",
            "PENDING_MANUAL_REVIEW");

    private final DispatchDecisionRepository dispatchDecisionRepository;
    private final RideOrderRepository rideOrderRepository;

    public ManualReviewQueueService(
            DispatchDecisionRepository dispatchDecisionRepository,
            RideOrderRepository rideOrderRepository) {
        this.dispatchDecisionRepository = dispatchDecisionRepository;
        this.rideOrderRepository = rideOrderRepository;
    }

    public List<ManualReviewQueueItem> list() {
        return dispatchDecisionRepository.findByDecisionResultInOrderByCreatedAtAsc(MANUAL_REVIEW_RESULTS)
                .stream()
                .flatMap(decision -> rideOrderRepository.findById(decision.getRideOrderId())
                        .filter(order -> order.getStatus() == OrderStatus.PENDING_MANUAL_REVIEW)
                        .map(order -> new ManualReviewQueueItem(
                                decision.getId(),
                                order.getId(),
                                order.getPassengerName(),
                                order.getPassengerCount(),
                                order.getRequestedDepartureAt(),
                                decision.getBestVehicleId(),
                                decision.getCandidateCount()))
                        .stream())
                .toList();
    }
}
