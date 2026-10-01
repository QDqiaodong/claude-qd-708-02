package com.kart.track.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.Maintenance;

import jakarta.persistence.LockModeType;

public interface MaintenanceRepository extends JpaRepository<Maintenance, Long> {

    List<Maintenance> findAllByOrderByStartTimeDesc();

    Optional<Maintenance> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    List<Maintenance> findAllByKartIdOrderByStartTimeDesc(Long kartId);

    List<Maintenance> findAllByStatusOrderByStartTimeAsc(String status);

    long countByStatus(String status);

    /** 同车指定状态的工单（如未结 = 待修 + 维修中），按开始时间升序 */
    List<Maintenance> findByKartIdAndStatusInOrderByStartTimeAsc(Long kartId, Collection<String> statuses);

    /**
     * 悲观锁读取（SELECT ... FOR UPDATE）：开工/完工前先把工单行锁住，
     * 重复点击或并发操作在后者事务里读到的是最新状态，不会互相覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Maintenance m where m.id = :id")
    Optional<Maintenance> findByIdForUpdate(@Param("id") Long id);
}
