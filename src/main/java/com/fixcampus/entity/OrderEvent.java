package com.fixcampus.entity;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The application only appends events; actor names and roles are snapshots. */
@Entity
@Table(name = "order_events", uniqueConstraints = @UniqueConstraint(name = "uk_events_order_action", columnNames = {"order_id", "action"}))
public class OrderEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private RepairOrder order;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private OrderAction action;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_role", nullable = false, length = 16)
    private Role actorRole;
    @Column(name = "actor_name", nullable = false, length = 50)
    private String actorName;
    @Column(name = "occurred_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime occurredAt;
    @Column(length = 1000)
    private String detail;

    protected OrderEvent() { }
    public OrderEvent(RepairOrder order, OrderAction action, User actor, Role actorRole, String actorName,
                      LocalDateTime occurredAt, String detail) {
        this.order = order; this.action = action; this.actor = actor; this.actorRole = actorRole;
        this.actorName = actorName; this.occurredAt = occurredAt; this.detail = detail;
    }
    public Long getId() { return id; }
    public OrderAction getAction() { return action; }
    public Role getActorRole() { return actorRole; }
    public String getActorName() { return actorName; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public String getDetail() { return detail; }
}
