package com.vectrans.aimatrix.entity.enums;

/**
 * 任务终态枚举
 * 用于 task_item 表的 status 字段
 */
public enum TaskStatus {
    /** 未完成（默认） */
    UNCOMPLETED,
    /** 已完成 */
    COMPLETED,
    /** 已删除（逻辑删除） */
    DELETED
}
