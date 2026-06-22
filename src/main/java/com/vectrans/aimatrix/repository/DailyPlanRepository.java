package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.DailyPlan;
import com.vectrans.aimatrix.entity.enums.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailyPlanRepository extends JpaRepository<DailyPlan, Long> {

    List<DailyPlan> findByUserIdAndPlanDate(Long userId, LocalDate planDate);

    List<DailyPlan> findByUserIdAndPlanDateAndStatus(Long userId, LocalDate planDate, PlanStatus status);

    List<DailyPlan> findByTaskId(Long taskId);
}
