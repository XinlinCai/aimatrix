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

    /**
     * 根据 ID 查询计划并预加载关联任务（避免 LazyInitializationException）
     */
    @Query("SELECT dp FROM DailyPlan dp LEFT JOIN FETCH dp.task WHERE dp.id = :id")
    Optional<DailyPlan> findByIdWithTask(@Param("id") Long id);

    @Query("SELECT dp FROM DailyPlan dp LEFT JOIN FETCH dp.task WHERE dp.userId = :userId AND dp.planDate = :planDate")
    List<DailyPlan> findByUserIdAndPlanDate(@Param("userId") Long userId, @Param("planDate") LocalDate planDate);

    List<DailyPlan> findByUserIdAndPlanDateAndStatus(Long userId, LocalDate planDate, PlanStatus status);

    List<DailyPlan> findByTaskId(Long taskId);

    /**
     * 查询用户指定日期范围内的所有计划（用于复盘分析/历史查询），并预加载关联任务
     */
    @Query("SELECT dp FROM DailyPlan dp LEFT JOIN FETCH dp.task WHERE dp.userId = :userId AND dp.planDate BETWEEN :startDate AND :endDate")
    List<DailyPlan> findByUserIdAndPlanDateBetween(@Param("userId") Long userId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /**
     * 查询用户指定日期范围内指定状态的计划（用于统计完成率）
     */
    List<DailyPlan> findByUserIdAndPlanDateBetweenAndStatus(Long userId, LocalDate startDate, LocalDate endDate, PlanStatus status);
}
