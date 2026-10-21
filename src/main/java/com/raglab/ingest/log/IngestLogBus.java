package com.raglab.ingest.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.ingest.dao.LogDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 日志总线。
 *
 * 三条要求决定了它的实现：
 * 1. 实时：任务线程每写一条日志，立刻推给页面（不轮询）
 * 2. 不丢：推送的同时落 MySQL，页面晚连、断线重连、刷新都能回放全过程
 * 3. 不串：每个 taskId 独立的订阅集合与锁，两个任务并发跑互不干扰
 */
@Component
public class IngestLogBus {

    private static final Logger log = LoggerFactory.getLogger(IngestLogBus.class);

    private final LogDao logDao;
    private final ObjectMapper objectMapper;

    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public IngestLogBus(LogDao logDao, ObjectMapper objectMapper) {
        this.logDao = logDao;
        this.objectMapper = objectMapper;
    }

    private Object lockOf(String taskId) {
        return locks.computeIfAbsent(taskId, k -> new Object());
    }

    /**
     * 写日志：先落库（保证不丢），再推给所有在线连接。
     */
    public void publish(String taskId, LogEvent event) {
        synchronized (lockOf(taskId)) {
            try {
                logDao.insert(taskId, event);
            } catch (Exception dbEx) {
                // 日志本身写不进库，不能影响主流程，但要吵出来
                log.error("日志落库失败 taskId={} seq={}", taskId, event.seq(), dbEx);
            }
            Set<SseEmitter> set = subscribers.get(taskId);
            if (set != null) {
                for (SseEmitter emitter : set) {
                    sendLog(emitter, event);
                }
            }
        }
    }

    /**
     * 订阅某个任务的日志流。
     *
     * @param finished 任务是否已结束。已结束的连接会先回放全部历史日志，然后立刻 complete
     */
    public SseEmitter subscribe(String taskId, boolean finished) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 不超时，由任务结束或前端断开决定
        synchronized (lockOf(taskId)) {
            // 1. 回放历史（此时加锁，避免和正在 publish 的线程交错导致重复/丢失）
            List<LogEvent> history = logDao.findByTask(taskId);
            for (LogEvent e : history) {
                sendLog(emitter, e);
            }
            if (finished) {
                sendEnd(emitter, "replay");
                emitter.complete();
                return emitter;
            }
            // 2. 挂到实时集合
            subscribers.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(emitter);
        }
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> {
            remove(taskId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(taskId, emitter));
        return emitter;
    }

    /** 任务结束：通知前端并关闭连接 */
    public void close(String taskId, String status) {
        Set<SseEmitter> set;
        synchronized (lockOf(taskId)) {
            set = subscribers.remove(taskId);
        }
        if (set != null) {
            for (SseEmitter emitter : set) {
                sendEnd(emitter, status);
                emitter.complete();
            }
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        Set<SseEmitter> set = subscribers.get(taskId);
        if (set != null) {
            set.remove(emitter);
        }
    }

    private void sendLog(SseEmitter emitter, LogEvent e) {
        try {
            emitter.send(SseEmitter.event()
                    .name("log")
                    .data(objectMapper.writeValueAsString(e)));
        } catch (IOException | IllegalStateException ex) {
            // 连接已断（用户关页面/刷新），静默忽略，日志在 DB 里还在
            log.debug("SSE 推送失败，连接可能已断开: {}", ex.getMessage());
        }
    }

    private void sendEnd(SseEmitter emitter, String status) {
        try {
            emitter.send(SseEmitter.event()
                    .name("end")
                    .data("{\"status\":\"" + status + "\"}"));
        } catch (IOException | IllegalStateException ignored) {
            // 同上
        }
    }
}
