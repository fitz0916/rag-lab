package com.raglab.ingest.service;

import com.raglab.ingest.dao.TaskDao;
import com.raglab.ingest.model.IngestJob;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * 任务提交入口。
 *
 * 顺序很重要：先建任务记录，再扔给异步线程。
 * 否则前端拿到 taskId 立刻订阅时，DB 里还查不到这个任务，SSE 会 404。
 */
@Service
public class IngestService {

    private final TaskDao taskDao;
    private final IngestRunner runner;

    public IngestService(TaskDao taskDao, IngestRunner runner) {
        this.taskDao = taskDao;
        this.runner = runner;
    }

    public String submitFile(MultipartFile file, String docName, Integer chunkSize, Integer chunkOverlap)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件为空");
        }
        String fileName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String name = (docName == null || docName.isBlank()) ? fileName : docName.strip();
        String taskId = UUID.randomUUID().toString();
        taskDao.insert(taskId, name);
        byte[] bytes = file.getBytes();
        runner.run(IngestJob.ofFile(taskId, fileName, name, bytes, chunkSize, chunkOverlap));
        return taskId;
    }

    public String submitText(String name, String text, Integer chunkSize, Integer chunkOverlap) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("粘贴的文本为空");
        }
        String docName = (name == null || name.isBlank()) ? "未命名文本-" + System.currentTimeMillis() : name.strip();
        String taskId = UUID.randomUUID().toString();
        taskDao.insert(taskId, docName);
        runner.run(IngestJob.ofText(taskId, docName, text, chunkSize, chunkOverlap));
        return taskId;
    }
}
