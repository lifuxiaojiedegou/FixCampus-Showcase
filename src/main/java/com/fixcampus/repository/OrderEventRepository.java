package com.fixcampus.repository;

import java.util.List;
import com.fixcampus.entity.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrder_IdOrderByIdAsc(Long orderId);
}
