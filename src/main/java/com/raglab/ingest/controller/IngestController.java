package com.raglab.ingest.controller;

import com.raglab.common.ApiResponse;
import com.raglab.ingest.dao.ChunkDao;
import com.raglab.ingest.dao.DocumentDao;
import com.raglab.ingest.dao.TaskDao;
import com.raglab.ingest.log.IngestLogBus;
import com.raglab.ingest.model.DocumentRow;
import com.raglab.ingest.model.TaskSnapshot;
import com.raglab.ingest.service.ChunkInspectService;
import com.raglab.ingest.service.IngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestService ingestService;
    private final IngestLogBus logBus;
    private final TaskDao taskDao;
    private final DocumentDao documentDao;
    private final ChunkDao chunkDao;
    private final ChunkInspectService chunkInspectService;

    public IngestController(IngestService ingestService, IngestLogBus logBus,
                            TaskDao taskDao, DocumentDao documentDao, ChunkDao chunkDao,
                            ChunkInspectService chunkInspectService) {
        this.ingestService = ingestService;
        this.logBus = logBus;
        this.taskDao = taskDao;
        this.documentDao = documentDao;
        this.chunkDao = chunkDao;
        this.chunkInspectService = chunkInspectService;
    }

    /** 文件入库 */
    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> upload(@RequestPart("file") MultipartFile file,
                                                   @RequestParam(value = "docName", required = false) String docName,
                                                   @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
                                                   @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap)
            throws IOException {
        String taskId = ingestService.submitFile(file, docName, chunkSize, chunkOverlap);
        return ApiResponse.ok(Map.of("taskId", taskId));
    }

    /** 文本直接入库（贴一段 Markdown 最快，不用找文件） */
    @PostMapping("/text")
    public ApiResponse<Map<String, String>> ingestText(@RequestParam String name,
                                                       @RequestParam String text,
                                                       @RequestParam(required = false) Integer chunkSize,
                                                       @RequestParam(required = false) Integer chunkOverlap) {
        String taskId = ingestService.submitText(name, text, chunkSize, chunkOverlap);
        return ApiResponse.ok(Map.of("taskId", taskId));
    }

    /**
     * 订阅某个任务的日志流。
     *
     * 任务已结束时不会挂长连接，而是把 DB 里的全过程回放完再关闭 ——
     * 所以刷新页面、或者晚几秒点进来，一样能看到完整日志。
     */
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        TaskSnapshot task = taskDao.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在：" + taskId));
        return logBus.subscribe(taskId, task.finished());
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskSnapshot> task(@PathVariable String taskId) {
        return taskDao.findById(taskId)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail("任务不存在：" + taskId));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskSnapshot>> tasks(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(taskDao.listRecent(limit));
    }

    /** 文档列表 */
    @GetMapping("/docs")
    public ApiResponse<List<DocumentRow>> docs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(documentDao.listRecent(limit));
    }

    /** 某份文档的切片，左侧预览用 */
    @GetMapping("/docs/{docId}/chunks")
    public ApiResponse<List<ChunkDao.ChunkRow>> chunks(@PathVariable long docId,
                                                       @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(chunkDao.findByDoc(docId, limit));
    }

    /**
     * 单片「解剖」：完整正文 + 与上一片的 overlap 片段 + Chroma 里真实存储的向量。
     * 学习用，所以向量是真的从库里读回来的。
     */
    @GetMapping("/docs/{docId}/chunks/{index}")
    public ApiResponse<ChunkInspectService.ChunkDetail> chunkDetail(@PathVariable long docId,
                                                                    @PathVariable int index) {
        return ApiResponse.ok(chunkInspectService.inspect(docId, index));
    }
}
