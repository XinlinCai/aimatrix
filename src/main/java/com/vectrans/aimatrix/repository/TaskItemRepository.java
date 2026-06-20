package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.TaskItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {

    List<TaskItem> findByFocusDate(LocalDate focusDate);

    List<TaskItem> findByStatus(String status);
}
