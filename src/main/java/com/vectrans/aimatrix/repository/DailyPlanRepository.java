package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.enums.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyPlanRepository extends JpaRepository<DailyPlan, Long> {


    @Query("SELECT dp FROM DailyPlan dp LEFT JOIN FETCH dp.task WHERE dp.id = :id")
    Optional<DailyPlan> findByIdWithTask(@Param("id") Long id);

    @Query("SELECT dp FROM DailyPlan dp LEFT JOIN FETCH dp.task WHERE dp.userId = :userId AND dp.planDate = :planDate")
    List<DailyPlan> findByUserIdAndPlanDate(@Param("userId") Long userId, @Param("planDate") LocalDate planDate);

    List<DailyPlan> findByUserIdAndPlanDateAndStatus(Long userId, LocalDate planDate, PlanStatus status);

    List<DailyPlan> findByTaskId(Long taskId);

    @Query("SELECT dp FROM DailyPlan dp LEFT JOIN FETCH dp.task WHERE dp.userId = :userId AND dp.planDate BETWEEN :startDate AND :endDate")
    List<DailyPlan> findByUserIdAndPlanDateBetween(@Param("userId") Long userId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    List<DailyPlan> findByUserIdAndPlanDateBetweenAndStatus(Long userId, LocalDate startDate, LocalDate endDate, PlanStatus status);
}
