package com.fixcampus.view;

import java.time.LocalDateTime;
import com.fixcampus.entity.OrderAction;
import com.fixcampus.entity.Role;

public record OrderHistoryEntry(OrderAction action, LocalDateTime occurredAt, Role actorRole,
                                String actorName, String detail, boolean recorded) { }
