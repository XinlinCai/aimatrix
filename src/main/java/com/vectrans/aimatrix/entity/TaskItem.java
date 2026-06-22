package com.vectrans.aimatrix.entity;

import com.vectrans.aimatrix.entity.enums.TaskStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_item", indexes = {
        @Index(name = "idx_task_user_status", columnList = "user_id, status")
})
public class TaskItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "VARCHAR(20) DEFAULT 'UNCOMPLETED' CHECK (status IN ('UNCOMPLETED','COMPLETED','DELETED'))")
    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.UNCOMPLETED;

    @Column(name = "is_important")
    private Boolean isImportant = false;

    @Column(name = "is_urgent")
    private Boolean isUrgent = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TaskItem() {
    }

    public TaskItem(Long userId, String title) {
        this.userId = userId;
        this.title = title;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Boolean getIsImportant() {
        return isImportant;
    }

    public void setIsImportant(Boolean isImportant) {
        this.isImportant = isImportant;
    }

    public Boolean getIsUrgent() {
        return isUrgent;
    }

    public void setIsUrgent(Boolean isUrgent) {
        this.isUrgent = isUrgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "TaskItem{id=" + id + ", userId=" + userId + ", title='" + title
                + "', status=" + status + ", important=" + isImportant
                + ", urgent=" + isUrgent + "}";
    }
}
