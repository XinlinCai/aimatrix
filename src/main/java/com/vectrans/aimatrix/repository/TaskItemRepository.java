package com.vectrans.aimatrix.repository;

import com.vectrans.aimatrix.entity.TaskItem;
import com.vectrans.aimatrix.entity.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskItemRepository extends JpaRepository<TaskItem, Long> {

    List<TaskItem> findByUserIdAndStatus(Long userId, TaskStatus status);

    List<TaskItem> findByUserIdAndIsImportantAndIsUrgent(Long userId, Boolean isImportant, Boolean isUrgent);
}
