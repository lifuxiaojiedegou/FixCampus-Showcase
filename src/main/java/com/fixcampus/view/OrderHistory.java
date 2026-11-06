package com.fixcampus.view;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.OrderEvent;
import com.fixcampus.entity.RepairOrder;

public record OrderHistory(List<OrderHistoryEntry> entries, boolean incomplete) {
    public OrderHistory { entries = List.copyOf(entries); }

    public static OrderHistory from(RepairOrder order, List<OrderEvent> records) {
        var byAction = new EnumMap<OrderAction, OrderEvent>(OrderAction.class);
        records.forEach(event -> byAction.put(event.getAction(), event));
        var entries = new ArrayList<OrderHistoryEntry>();
        for (var action : OrderAction.expectedFor(order.getStatus())) {
            var event = byAction.remove(action);
            if (event != null) entries.add(entry(event));
            else {
                var knownTime = action == OrderAction.SUBMITTED ? order.getCreatedAt()
                        : action == OrderAction.CONFIRMED ? order.getCompletedAt() : null;
                entries.add(new OrderHistoryEntry(action, knownTime, null, null, null, false));
            }
        }
        // Do not hide actual events if out-of-band SQL has made the current state inconsistent.
        records.stream().filter(event -> byAction.containsKey(event.getAction())).forEach(event -> entries.add(entry(event)));
        return new OrderHistory(entries, entries.stream().anyMatch(entry -> !entry.recorded()) || !byAction.isEmpty());
    }

    private static OrderHistoryEntry entry(OrderEvent event) {
        return new OrderHistoryEntry(event.getAction(), event.getOccurredAt(), event.getActorRole(),
                event.getActorName(), event.getDetail(), true);
    }
}
