package com.raglab.ingest.log;

import com.raglab.common.ErrorDiagnoser;
import com.raglab.common.Stage;
import com.raglab.ingest.dao.TaskDao;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务级日志器。流水线里的每一步都通过它说话，保证顺序、耗时、错误诊断统一。
 *
 * 不注册成 Bean —— 每个任务 new 一个，天然隔离序号。
 */
public class TaskLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String taskId;
    private final IngestLogBus bus;
    private final TaskDao taskDao;
    private final AtomicInteger seq = new AtomicInteger(0);

    public TaskLogger(String taskId, IngestLogBus bus, TaskDao taskDao) {
        this.taskId = taskId;
        this.bus = bus;
        this.taskDao = taskDao;
    }

    /**
     * 检索场景没有 kb_ingest_task 记录，只写日志总线。
     * taskDao 传 null 即可，enter() 会跳过任务表更新。
     */
    public TaskLogger(String taskId, IngestLogBus bus) {
        this(taskId, bus, null);
    }

    /** 进入某个步骤：更新任务当前阶段（如有），页面进度条跟着走 */
    public void enter(Stage stage) {
        if (taskDao != null) {
            taskDao.updateStage(taskId, stage.name());
        }
        emit(LogEvent.INFO, stage, "▶ 开始：" + stage.getLabel() + " —— " + stage.getDescription(), null, 0);
    }

    public void info(Stage stage, String message) {
        emit(LogEvent.INFO, stage, message, null, 0);
    }

    public void info(Stage stage, String message, String detail) {
        emit(LogEvent.INFO, stage, message, detail, 0);
    }

    public void ok(Stage stage, String message, long costMs) {
        emit(LogEvent.OK, stage, message, null, costMs);
    }

    public void ok(Stage stage, String message, String detail, long costMs) {
        emit(LogEvent.OK, stage, message, detail, costMs);
    }

    public void warn(Stage stage, String message, String detail) {
        emit(LogEvent.WARN, stage, message, detail, 0);
    }

    /** 失败：自动把异常翻译成人话，附带排查命令 */
    public void fail(Stage stage, String message, Throwable t, String target, long costMs) {
        String detail = ErrorDiagnoser.diagnose(t, target, message);
        emit(LogEvent.ERROR, stage, message + " → " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage()), detail, costMs);
    }

    public void error(Stage stage, String message, String detail, long costMs) {
        emit(LogEvent.ERROR, stage, message, detail, costMs);
    }

    private void emit(String level, Stage stage, String message, String detail, long costMs) {
        LogEvent e = new LogEvent(
                seq.incrementAndGet(),
                LocalTime.now().format(TS),
                level,
                stage.name(),
                message,
                costMs,
                detail
        );
        bus.publish(taskId, e);
    }
}
