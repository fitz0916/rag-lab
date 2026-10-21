package com.raglab.ingest.dao;

import com.raglab.ingest.model.ChunkDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class ChunkDao {

    public record ChunkRow(long id, int chunkIndex, String heading, String content,
                           int charCount, String chromaId, String status) {
    }

    private static final RowMapper<ChunkRow> MAPPER = (rs, i) -> new ChunkRow(
            rs.getLong("id"),
            rs.getInt("chunk_index"),
            rs.getString("heading"),
            rs.getString("content"),
            rs.getInt("char_count"),
            rs.getString("chroma_id"),
            rs.getString("status")
    );

    private final JdbcTemplate jdbc;

    public ChunkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void batchInsert(long docId, List<ChunkDraft> chunks) {
        String sql = """
                INSERT INTO kb_chunk(doc_id, chunk_index, heading, content, char_count, token_est, chroma_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """;
        List<Object[]> args = new ArrayList<>(chunks.size());
        for (ChunkDraft c : chunks) {
            args.add(new Object[]{docId, c.index(), c.heading(), c.content(),
                    c.charCount(), c.tokenEstimate(), c.chromaId()});
        }
        jdbc.batchUpdate(sql, args);
    }

    public void updateStatusByChromaIds(List<String> chromaIds, String status) {
        if (chromaIds.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(chromaIds.size());
        for (String id : chromaIds) {
            args.add(new Object[]{status, id});
        }
        jdbc.batchUpdate("UPDATE kb_chunk SET status = ? WHERE chroma_id = ?", args);
    }

    public List<ChunkRow> findByDoc(long docId, int limit) {
        return jdbc.query(
                "SELECT id, chunk_index, heading, content, char_count, chroma_id, status " +
                        "FROM kb_chunk WHERE doc_id = ? ORDER BY chunk_index LIMIT ?",
                MAPPER, docId, limit);
    }

    public java.util.Optional<ChunkRow> findByDocAndIndex(long docId, int index) {
        List<ChunkRow> list = jdbc.query(
                "SELECT id, chunk_index, heading, content, char_count, chroma_id, status " +
                        "FROM kb_chunk WHERE doc_id = ? AND chunk_index = ?",
                MAPPER, docId, index);
        return list.stream().findFirst();
    }

    public int countByDoc(long docId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM kb_chunk WHERE doc_id = ?", Integer.class, docId);
        return n == null ? 0 : n;
    }

    public int countStoredByDoc(long docId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE doc_id = ? AND status = 'STORED'", Integer.class, docId);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 阶段三 BM25 索引用：把已入库（STORED）的切片全量读进内存
    // ==================================================================

    public record IndexRow(long chunkId, long docId, String docName, int chunkIndex,
                           String heading, String content, int charCount, String chromaId) {
    }

    private static final RowMapper<IndexRow> INDEX_MAPPER = (rs, i) -> new IndexRow(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getString("doc_name"),
            rs.getInt("chunk_index"),
            rs.getString("heading"),
            rs.getString("content"),
            rs.getInt("char_count"),
            rs.getString("chroma_id")
    );

    /**
     * 只取 status='STORED' 的切片 —— 没写进向量库的切片，参与 BM25 会导致两路召回的 id 对不上。
     */
    public List<IndexRow> loadAllForIndex() {
        return jdbc.query("""
                SELECT c.id, c.doc_id, d.doc_name, c.chunk_index, c.heading, c.content,
                       c.char_count, c.chroma_id
                  FROM kb_chunk c
                  JOIN kb_document d ON d.id = c.doc_id
                 WHERE c.status = 'STORED'
                 ORDER BY c.doc_id, c.chunk_index
                """, INDEX_MAPPER);
    }

    /** 用于判断索引是否需要重建 */
    public int countStored() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE status = 'STORED'", Integer.class);
        return n == null ? 0 : n;
    }
}
