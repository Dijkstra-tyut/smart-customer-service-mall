package com.supermarket.agent;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Database-backed outbox: retries survive Java restarts; leases coordinate multiple instances. */
@Component
public class AgentDecisionDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(AgentDecisionDispatcher.class);
    private final AgentRuntimeService runtime;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), runnable -> {
                Thread thread = new Thread(runnable, "agent-decision-delivery"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private boolean migrationWarning;

    public AgentDecisionDispatcher(AgentRuntimeService runtime) { this.runtime = runtime; }

    @Scheduled(fixedDelayString = "${mall.customer.resume-poll-ms:3000}")
    public void dispatch() {
        try {
            for (String taskId : runtime.dueDecisions()) {
                if (executor.getQueue().remainingCapacity() == 0) break;
                executor.execute(() -> {
                    try { runtime.deliverDecision(taskId); }
                    catch (Exception error) { LOG.warn("人工决定交付暂不可用，任务将保留待重试"); }
                });
            }
            for (String taskId : runtime.preparingTasks()) {
                if (executor.getQueue().remainingCapacity() == 0) break;
                executor.execute(() -> {
                    try { runtime.reconcilePreparing(taskId); }
                    catch (Exception error) { LOG.debug("人工中断现场尚不可核对，将稍后重试"); }
                });
            }
        } catch (DataAccessException error) {
            if (!migrationWarning) { LOG.warn("客服人工任务表尚不可用，请应用客服 Agent 增量迁移"); migrationWarning = true; }
        }
    }

    @PreDestroy public void close() { executor.shutdown(); }
}
