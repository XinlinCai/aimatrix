package com.vectrans.aimatrix.entity.enums;

/**
 * 每日计划执行状态枚举
 * 用于 daily_plan 表的 status 字段，表示任务在当天的执行状态
 */
public enum PlanStatus {
    /** 挂起/未完成/当天待执行 （默认）*/
    PENDING,
    /** 当天已完成 */
    COMPLETED
}
