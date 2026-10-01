package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.Kart;

import jakarta.persistence.LockModeType;

public interface KartRepository extends JpaRepository<Kart, Long> {

    List<Kart> findAllByOrderByKartNoAsc();

    Optional<Kart> findByKartNo(String kartNo);

    boolean existsByKartNo(String kartNo);

    List<Kart> findAllByStatusOrderByKartNoAsc(String status);

    long countByStatus(String status);

    /**
     * 悲观锁读取（SELECT ... FOR UPDATE）：车辆状态与维修单是同一条状态链，
     * 开单/开工/完工/转状态都先锁车辆行，把同车的并发写串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from Kart k where k.id = :id")
    Optional<Kart> findByIdForUpdate(@Param("id") Long id);
}
