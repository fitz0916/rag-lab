package com.raglab.ingest.dao;

import com.raglab.ingest.model.DocumentRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentDao {

    private static final RowMapper<DocumentRow> MAPPER = (rs, i) -> new DocumentRow(
            rs.getLong("id"),
            rs.getString("doc_name"),
            rs.getString("source_type"),
            rs.getString("content_hash"),
            rs.getInt("char_count"),
            rs.getInt("chunk_count"),
            rs.getInt("vector_count"),
            rs.getString("status"),
            rs.getString("error_msg"),
            rs.getString("collection"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public DocumentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String docName, String sourceType, String filePath, long fileSize, String contentHash) {
        String sql = """
                INSERT INTO kb_document(doc_name, source_type, file_path, file_size, content_hash, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """;
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, docName);
            ps.setString(2, sourceType);
            ps.setString(3, filePath);
            ps.setLong(4, fileSize);
            ps.setString(5, contentHash);
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("kb_document 插入成功但拿不到自增主键");
        }
        return key.longValue();
    }

    public void updateStatus(long id, String status, String errorMsg) {
        jdbc.update("UPDATE kb_document SET status = ?, error_msg = ? WHERE id = ?", status, errorMsg, id);
    }

    public void updateTextStats(long id, int charCount) {
        jdbc.update("UPDATE kb_document SET char_count = ? WHERE id = ?", charCount, id);
    }

    public void updateChunkStats(long id, int chunkCount, int vectorCount) {
        jdbc.update("UPDATE kb_document SET chunk_count = ?, vector_count = ? WHERE id = ?",
                chunkCount, vectorCount, id);
    }

    public void updateCollection(long id, String collection) {
        jdbc.update("UPDATE kb_document SET collection = ? WHERE id = ?", collection, id);
    }

    public Optional<DocumentRow> findById(long id) {
        List<DocumentRow> list = jdbc.query("SELECT * FROM kb_document WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<DocumentRow> listRecent(int limit) {
        return jdbc.query("SELECT * FROM kb_document ORDER BY id DESC LIMIT ?", MAPPER, limit);
    }

    /** 同一份内容是否已经成功入库过（用于秒传/去重提示） */
    public Optional<DocumentRow> findDoneByHash(String hash) {
        List<DocumentRow> list = jdbc.query(
                "SELECT * FROM kb_document WHERE content_hash = ? AND status = 'DONE' ORDER BY id DESC LIMIT 1",
                MAPPER, hash);
        return list.stream().findFirst();
    }
}
