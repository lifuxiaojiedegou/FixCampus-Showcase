package com.fixcampus.view;

import java.time.LocalDateTime;
import com.fixcampus.entity.RepairCategory;
import com.fixcampus.entity.RepairStatus;

public record OrderSummary(Long id, String location, RepairCategory category, RepairStatus status,
                           LocalDateTime createdAt) { }
