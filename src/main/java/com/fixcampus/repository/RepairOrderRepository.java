package com.fixcampus.repository;

import java.util.List;
import java.util.Optional;
import com.fixcampus.entity.RepairOrder;
import com.fixcampus.entity.RepairStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepairOrderRepository extends JpaRepository<RepairOrder, Long> {
    @Query("select o from RepairOrder o where o.student.id = :studentId order by o.createdAt desc, o.id desc")
    List<RepairOrder> findForStudent(@Param("studentId") Long studentId);

    @Query("select o from RepairOrder o where o.student.id = :studentId "
            + "and (:status is null or o.status = :status) "
            + "and (:pattern is null or o.id = :orderId or o.location like :pattern escape '!') "
            + "order by o.createdAt desc, o.id desc")
    List<RepairOrder> searchForStudent(@Param("studentId") Long studentId, @Param("status") RepairStatus status,
                                      @Param("orderId") Long orderId, @Param("pattern") String pattern);

    long countByStudent_IdAndStatus(Long studentId, RepairStatus status);

    @Query("select o from RepairOrder o left join fetch o.worker where o.id = :id and o.student.id = :studentId")
    Optional<RepairOrder> findForStudentById(@Param("id") Long id, @Param("studentId") Long studentId);

    @Query("select o from RepairOrder o where (:status is null or o.status = :status) order by o.createdAt desc, o.id desc")
    List<RepairOrder> findAllOrStatus(@Param("status") RepairStatus status);

    @Query("select o from RepairOrder o where (:status is null or o.status = :status) "
            + "and (:pattern is null or o.id = :orderId or o.location like :pattern escape '!') "
            + "order by o.createdAt desc, o.id desc")
    List<RepairOrder> searchForAdmin(@Param("status") RepairStatus status, @Param("orderId") Long orderId,
                                    @Param("pattern") String pattern);

    long countByStatus(RepairStatus status);

    @Query("select o from RepairOrder o join fetch o.student left join fetch o.worker where o.id = :id")
    Optional<RepairOrder> findForAdminById(@Param("id") Long id);

    @Query("select o from RepairOrder o where o.worker.id = :workerId order by o.createdAt desc, o.id desc")
    List<RepairOrder> findForWorker(@Param("workerId") Long workerId);

    @Query("select o from RepairOrder o where o.worker.id = :workerId "
            + "and (:status is null or o.status = :status) "
            + "and (:pattern is null or o.id = :orderId or o.location like :pattern escape '!') "
            + "order by o.createdAt desc, o.id desc")
    List<RepairOrder> searchForWorker(@Param("workerId") Long workerId, @Param("status") RepairStatus status,
                                     @Param("orderId") Long orderId, @Param("pattern") String pattern);

    long countByWorker_IdAndStatus(Long workerId, RepairStatus status);

    @Query("select o from RepairOrder o join fetch o.student where o.id = :id and o.worker.id = :workerId")
    Optional<RepairOrder> findForWorkerById(@Param("id") Long id, @Param("workerId") Long workerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RepairOrder o where o.id = :id")
    Optional<RepairOrder> findByIdForUpdate(@Param("id") Long id);
}
