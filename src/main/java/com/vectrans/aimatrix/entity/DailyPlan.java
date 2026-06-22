package com.vectrans.aimatrix.entity;

import com.vectrans.aimatrix.entity.enums.PlanStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_plan", indexes = {
        @Index(name = "idx_plan_user_date", columnList = "user_id, plan_date"),
        @Index(name = "idx_plan_task_id", columnList = "task_id")
})
public class DailyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private TaskItem task;

    @Column(columnDefinition = "VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED'))")
    @Enumerated(EnumType.STRING)
    private PlanStatus status = PlanStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public DailyPlan() {
    }

    public DailyPlan(Long userId, LocalDate planDate, TaskItem task) {
        this.userId = userId;
        this.planDate = planDate;
        this.task = task;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public void setPlanDate(LocalDate planDate) {
        this.planDate = planDate;
    }

    public TaskItem getTask() {
        return task;
    }

    public void setTask(TaskItem task) {
        this.task = task;
    }

    public PlanStatus getStatus() {
        return status;
    }

    public void setStatus(PlanStatus status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "DailyPlan{id=" + id + ", userId=" + userId + ", planDate=" + planDate
                + ", taskId=" + (task != null ? task.getId() : null)
                + ", status=" + status + "}";
    }
}
