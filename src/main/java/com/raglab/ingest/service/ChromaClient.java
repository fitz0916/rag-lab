package com.raglab.ingest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.common.ExternalServiceException;
import com.raglab.config.RagLabProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chroma 客户端，直接用 v2 REST 接口（不引第三方 SDK）。
 *
 * 为什么不引 SDK？因为这一步的目的就是看清「向量库到底收了我什么」：
 * 建集合发的是什么 JSON、向量怎么塞进去、维度对不上时它怎么骂人。
 * 手写 HTTP 反而一目了然，日志里也能把请求/响应原样打出来。
 *
 * Chroma 1.x 的路径规则：
 *   /api/v2/tenants/{tenant}/databases/{database}/collections
 */
@Component
public class ChromaClient {

    private final RagLabProperties.Chroma cfg;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ChromaClient(RagLabProperties props, ObjectMapper objectMapper) {
        this.cfg = props.chroma();
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /** collection 元信息 */
    public record CollectionInfo(String id, String name, Integer dimension, boolean createdNow, String rawSpace) {
    }

    public String baseUrl() {
        return cfg.baseUrl();
    }

    public String collectionName() {
        return cfg.collection();
    }

    public int writeBatchSize() {
        return cfg.writeBatchSize();
    }

    /** 心跳，用于页面上的连通性指示灯 */
    public boolean heartbeat() {
        try {
            String body = restClient.get().uri("/api/v2/heartbeat").retrieve().body(String.class);
            return body != null && body.contains("heartbeat");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保 collection 存在。存在则校验维度，不存在则创建。
     */
    public CollectionInfo ensureCollection(int expectedDimension) {
        String encName = URLEncoder.encode(cfg.collection(), StandardCharsets.UTF_8);
        try {
            String raw = restClient.get()
                    .uri(cfg.collectionsPath() + "/" + encName)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            String id = node.path("id").asText();
            int dim = node.path("dimension").asInt(0);
            if (dim > 0 && dim != expectedDimension) {
                throw new IllegalStateException(("集合 %s 已存在，但维度是 %d，与当前向量模型维度 %d 不一致。"
                        + "两个办法：① 把 raglab.chroma.collection 换个名字；② 在 Chroma 里删掉这个集合")
                        .formatted(cfg.collection(), dim, expectedDimension));
            }
            return new CollectionInfo(id, cfg.collection(), dim == 0 ? null : dim, false, cfg.baseUrl());
        } catch (HttpClientErrorException.NotFound notFound) {
            return createCollection(expectedDimension);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma",
                    "查询集合 " + cfg.collection() + " 失败（" + cfg.baseUrl() + "）", e);
        }
    }

    private CollectionInfo createCollection(int expectedDimension) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", cfg.collection());
        // Chroma 1.x：距离度量放在 configuration.hnsw.space；向量检索统一用 cosine
        body.put("configuration", Map.of("hnsw", Map.of("space", "cosine")));
        body.put("get_or_create", true);
        try {
            String raw = restClient.post()
                    .uri(cfg.collectionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            String id = node.path("id").asText();
            return new CollectionInfo(id, cfg.collection(), expectedDimension, true, cfg.baseUrl());
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma",
                    "创建集合 " + cfg.collection() + " 失败，请求体：" + toJson(body), e);
        }
    }

    /**
     * 写入一批切片。ids / vectors / documents / metadatas 必须一一对应。
     * 用 upsert 而不是 add：同一个 chroma_id 重复写入时覆盖，不会抛 duplicate 异常。
     */
    public void upsert(String collectionId, List<String> ids, List<float[]> vectors,
                       List<String> documents, List<Map<String, Object>> metadatas) {
        if (ids.isEmpty()) {
            return;
        }
        if (ids.size() != vectors.size() || ids.size() != documents.size() || ids.size() != metadatas.size()) {
            throw new IllegalArgumentException("写入参数长度不一致：ids=%d, vectors=%d, documents=%d, metadatas=%d"
                    .formatted(ids.size(), vectors.size(), documents.size(), metadatas.size()));
        }
        List<List<Float>> emb = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            List<Float> list = new ArrayList<>(v.length);
            for (float f : v) {
                list.add(f);
            }
            emb.add(list);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        body.put("embeddings", emb);
        body.put("documents", documents);
        body.put("metadatas", metadatas);

        try {
            restClient.post()
                    .uri(cfg.collectionsPath() + "/" + collectionId + "/upsert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma",
                    "写入 " + ids.size() + " 条向量失败，collectionId=" + collectionId
                            + "，首批 id=" + ids.get(0), e);
        }
    }

    public int count(String collectionId) {
        try {
            Integer n = restClient.get()
                    .uri(cfg.collectionsPath() + "/" + collectionId + "/count")
                    .retrieve()
                    .body(Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma", "查询集合条数失败 collectionId=" + collectionId, e);
        }
    }

    /**
     * 向量检索：拿一个问题向量去找最近邻。
     *
     * Chroma 返回的结构是「数组套数组」（因为支持一次传多个查询向量）：
     *   { "ids": [[...]], "distances": [[...]], "documents": [[...]], "metadatas": [[...]] }
     * 我们每次只问一个问题，所以取 [0]。
     */
    public JsonNode query(String collectionId, float[] queryVector, int nResults) {
        List<Float> v = new ArrayList<>(queryVector.length);
        for (float f : queryVector) {
            v.add(f);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(v));
        body.put("n_results", nResults);
        body.put("include", List.of("documents", "metadatas", "distances"));
        try {
            String raw = restClient.post()
                    .uri(cfg.collectionsPath() + "/" + collectionId + "/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma",
                    "向量检索失败（collectionId=" + collectionId + ", n_results=" + nResults + "）", e);
        }
    }

    /** 按 id 取回，用于校验「写进去的」和「读出来的」一致 */
    public JsonNode getByIds(String collectionId, List<String> ids) {
        return getByIds(collectionId, ids, List.of("documents", "metadatas"));
    }

    /**
     * 按 id 取回任意字段，include 里可以放 documents / metadatas / embeddings。
     * 取 embeddings 是把「存进去的 1024 个数字」原样捞出来给页面看 ——
     * 这是理解向量化最直接的方式。
     */
    public JsonNode getByIds(String collectionId, List<String> ids, List<String> include) {
        Map<String, Object> body = Map.of("ids", ids, "include", include);
        try {
            String raw = restClient.post()
                    .uri(cfg.collectionsPath() + "/" + collectionId + "/get")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma",
                    "按 id 读取向量失败 collectionId=" + collectionId + " ids=" + ids, e);
        }
    }

    /** 只按名字取 collection id，不做维度校验（查看详情时用） */
    public String collectionIdByName(String name) {
        try {
            String raw = restClient.get()
                    .uri(cfg.collectionsPath() + "/"
                            + URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw).path("id").asText();
        } catch (Exception e) {
            throw new ExternalServiceException("Chroma",
                    "查询集合 " + name + " 失败（" + cfg.baseUrl() + "）", e);
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
