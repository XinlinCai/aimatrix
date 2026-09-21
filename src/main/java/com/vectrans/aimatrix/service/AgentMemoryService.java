package com.vectrans.aimatrix.service;

import java.util.List;

/**
 * Agent 长期记忆服务
 * <p>
 * 负责跨会话的用户偏好/重要事实的写入与语义检索，基于 PgVector 向量存储实现。
 * 与短期记忆（会话消息历史，由 Checkpoint Saver 持久化）分属两层，共同构成分层记忆。
 */
public interface AgentMemoryService {

    /**
     * 记忆一条长期信息（按 userId 隔离存储）
     *
     * @param userId  用户标识
     * @param content 需要长期记住的内容
     */
    void remember(String userId, String content);

    /**
     * 按语义相似度检索与 query 相关的长期记忆
     *
     * @param userId 用户标识（用于隔离不同用户的记忆）
     * @param query  检索问题/关键词
     * @param topK   召回条数，非正数时使用配置默认值
     * @return 命中的记忆文本列表，无命中或异常时返回空列表
     */
    List<String> recall(String userId, String query, int topK);

    /**
     * 构建可直接注入提示词的长期记忆上下文文本
     *
     * @param userId 用户标识
     * @param query  检索问题/关键词
     * @param topK   召回条数
     * @return 以 "- " 开头的记忆条目文本；无命中时返回空字符串
     */
    String buildMemoryContext(String userId, String query, int topK);
}
