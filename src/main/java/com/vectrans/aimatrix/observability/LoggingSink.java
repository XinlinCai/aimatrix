package com.vectrans.aimatrix.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 日志输出通道：把一次完整对话渲染为单行结构化日志，便于 {@code grep '[OBS]'} 回溯。
 * <p>
 * 采用「主动推」模式：请求结束时立即打印一行，无需任何外部平台即可在终端查看。
 * <p>
 * 渲染结果严格保持单行（提示词/回复中的换行、制表符、引号会被转义），
 * 保证「一次对话一行」的可检索性；原文仅在 {@code agent.observability.log-payload=true}
 * 时输出，默认关闭以避免敏感信息外泄与日志膨胀。
 */
@Component
public class LoggingSink implements ObservabilitySink {

    /** 日志前缀，便于按 [OBS] 快速过滤 */
    public static final String PREFIX = "[OBS] ";

    /**
     * 使用本类作为 logger 名称，确保其继承 com.vectrans.aimatrix 的 INFO 级别；
     * 若使用自定义 logger 名会落到 root=WARN 下而无法输出。
     */
    private static final Logger log = LoggerFactory.getLogger(LoggingSink.class);

    private final boolean logPayload;

    public LoggingSink(@Value("${agent.observability.log-payload:false}") boolean logPayload) {
        this.logPayload = logPayload;
    }

    @Override
    public void accept(LlmCallEvent event) {
        if (event == null) {
            return;
        }
        try {
            log.info(render(event));
        } catch (Exception e) {
            log.warn("可观测事件渲染失败，已忽略本次输出：{}", e.getMessage());
        }
    }

    /**
     * 将事件渲染为单行日志文本（纯函数，便于单元测试）
     *
     * @param event 可观测事件
     * @return 单行日志文本
     */
    public String render(LlmCallEvent event) {
        StringBuilder sb = new StringBuilder(PREFIX);
        if (hasText(event.traceId())) {
            sb.append("trace=").append(event.traceId()).append(' ');
        }
        sb.append("session=").append(text(event.sessionId()))
                .append(" user=").append(text(event.userId()))
                .append(" model=").append(text(event.model()))
                .append(" iter=").append(event.iterations()).append('/').append(event.maxIterations())
                .append(" in=").append(event.promptTokens())
                .append(" out=").append(event.completionTokens())
                .append(" total=").append(event.totalTokens())
                .append(" costMs=").append(event.llmMs())
                .append(" wallMs=").append(event.wallMs())
                .append(" trim=").append(event.trimmedMessages()).append('/').append(event.originalMessages())
                .append(" memInject=").append(event.injectedMemories())
                .append(" memRecall=").append(text(event.memoryRecall()))
                .append(" tools=").append(text(event.toolNames()))
                .append(" toolCalls=").append(event.toolCalls())
                .append(" status=").append(text(event.status()))
                .append(" empty=").append(event.emptyAnswer())
                .append(" slow=").append(event.slow());
        if (logPayload) {
            sb.append(" prompt=\"").append(escape(event.prompt()))
                    .append("\" reply=\"").append(escape(event.reply())).append('"');
        }
        return sb.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 空值统一渲染为 '-'，避免日志中出现难以检索的空白字段 */
    private static String text(String value) {
        return hasText(value) ? value.trim() : "-";
    }

    /** 转义换行与引号，保证日志严格单行 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\"", "\\\"");
    }
}
