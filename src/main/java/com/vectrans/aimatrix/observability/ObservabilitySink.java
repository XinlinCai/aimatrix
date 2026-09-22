package com.vectrans.aimatrix.observability;

/**
 * 可观测事件输出通道（采集层的唯一依赖）。
 * <p>
 * 采集点只负责把数据交到这里，不关心最终落到日志、指标端点还是外部平台；
 * 本期只提供 {@link LoggingSink} 一个实现，后续接入外部平台时新增实现即可，
 * 采集点代码零改动。
 * <p>
 * 实现必须自行保证「非阻塞 + 异常静默」：输出失败不得向上抛出，
 * 更不得影响主对话链路。
 */
public interface ObservabilitySink {

    /**
     * 输出一次完整对话的可观测事件
     *
     * @param event 已聚合完成的事件
     */
    void accept(LlmCallEvent event);
}
