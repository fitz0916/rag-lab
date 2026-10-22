package com.raglab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raglab.config.RagLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 多层缓存（阶段九 / 对应文章「三、优化5」）。
 *
 * 三层，各管一件事：
 *
 *   L1 内存（精确）    同一个问题再问一次 → 亚毫秒返回。生命周期跟进程走。
 *   L2 Redis（精确）   进程重启后还在、多实例共享。这是「持久 + 共享」的一层。
 *   L3 语义（相似）    问题不完全一样但意思相同 → 向量相似度 ≥ 阈值就复用答案。
 *                      这一步最值钱，也最危险：阈值太低会把错答案当对答案返回。
 *
 * 查询顺序：L1（最便宜）→ L2（网络一跳）→ L3（要算 embedding + 余弦）。
 * 为什么不是先 L3？因为 exact 命中几乎零成本，先做便宜的。
 *
 * 生命周期：缓存是「加速器」，不是「数据源」。
 *   所以任何写操作（入库新文档）都必须让缓存失效，
 *   否则用户会拿到「旧知识库生成的答案」，而且完全无感知 —— 这是缓存最隐蔽的坑。
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final RagLabProperties.Cache cfg;
    private final ObjectMapper objectMapper;
    private final RedisClient redis;

    /** L1：LRU + TTL。accessOrder=true 让 get 也算一次访问 */
    private final LinkedHashMap<String, LocalEntry> l1;

    /** L3：语义索引。容量有限，按写入时间淘汰最旧的 */
    private final List<SemEntry> l3 = new ArrayList<>();

    /** 语义索引是否已从 L2 重建过（进程重启后 L3 是空的，必须补） */
    private volatile boolean semanticLoaded = false;

    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l3Hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong stores = new AtomicLong();
    private final AtomicLong clears = new AtomicLong();

    public CacheService(RagLabProperties props, ObjectMapper objectMapper) {
        this.cfg = props.cache();
        this.objectMapper = objectMapper;
        this.redis = new RedisClient(cfg.redisHost(), cfg.redisPort(), 2000);
        this.l1 = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LocalEntry> eldest) {
                return size() > Math.max(1, cfg.capacity());
            }
        };
    }

    // ------------------------------------------------------------------
    // 对外结构
    // ------------------------------------------------------------------

    /** 参与缓存键的参数。换 Top-K 或换模型，结果就不同，必须区分开 */
    public record Params(int topK, String model) {
        public String signature() {
            return topK + "|" + model;
        }
    }

    /**
     * @param queryVector 语义检索时需要的问题向量；命中 L1/L2 时为 null（省掉 embedding）
     */
    public record Lookup(boolean hit, String layer, double similarity, String payload,
                         float[] queryVector, long costMs, String key) {
    }

    public record Stats(boolean redisOk, long l1Size, long l2Keys, long l3Size,
                        long l1Hits, long l2Hits, long l3Hits, long misses, long stores, long clears,
                        double hitRate, int ttlSeconds, double threshold, int capacity, String prefix) {
    }

    /** L1 里的一条：过期时间 + 内容 + 向量（向量只给 L3 用，L1 命中时不需要） */
    private record LocalEntry(long expiresAt, String payload, float[] vector) {
    }

    private record SemEntry(String key, String payload, float[] vector, long expiresAt, long createdAt) {
    }

    private record Persisted(String query, String payload, long createdAt, long expiresAt, float[] vector) {
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    public Lookup lookup(String scope, String query, Params params, Supplier<float[]> vectorSupplier) {
        long t0 = System.currentTimeMillis();
        if (!cfg.enabled()) {
            return new Lookup(false, "OFF", 0, null, null, 0, null);
        }
        String key = buildKey(scope, query, params);
        long now = System.currentTimeMillis();

        // ---- L1 ----
        synchronized (l1) {
            LocalEntry e = l1.get(key);
            if (e != null) {
                if (e.expiresAt() > now) {
                    l1Hits.incrementAndGet();
                    return new Lookup(true, "L1", 1.0, e.payload(), e.vector(),
                            System.currentTimeMillis() - t0, key);
                }
                l1.remove(key);
            }
        }

        // ---- L2 ----
        try {
            String raw = redis.get(key);
            if (raw != null) {
                Persisted p = objectMapper.readValue(raw, Persisted.class);
                if (p.expiresAt() > now) {
                    l2Hits.incrementAndGet();
                    putL1(key, p);
                    return new Lookup(true, "L2", 1.0, p.payload(), p.vector(),
                            System.currentTimeMillis() - t0, key);
                }
            }
        } catch (Exception e) {
            // 缓存不可用不能影响主流程，降级继续
            log.warn("[cache] L2 读取失败，降级：{}", e.getMessage());
        }

        // ---- L3 ----
        float[] qv = null;
        // 即使没命中，也把「最像的那条有多像」带出去 —— 用户才知道差多少、阈值该不该调
        double nearestSim = 0;
        if (cfg.semanticThreshold() > 0 && cfg.semanticThreshold() < 1) {
            ensureSemanticIndex();
            qv = vectorSupplier == null ? null : vectorSupplier.get();
            if (qv != null) {
                SemEntry best = null;
                double bestSim = 0;
                synchronized (l3) {
                    Iterator<SemEntry> it = l3.iterator();
                    while (it.hasNext()) {
                        SemEntry s = it.next();
                        if (s.expiresAt() <= now) {
                            it.remove();
                            continue;
                        }
                        double sim = cosine(qv, s.vector());
                        if (sim > bestSim) {
                            bestSim = sim;
                            best = s;
                        }
                    }
                }
                nearestSim = round4(bestSim);
                if (best != null && bestSim >= cfg.semanticThreshold()) {
                    l3Hits.incrementAndGet();
                    putL1(best.key(), new Persisted("", best.payload(), best.createdAt(), best.expiresAt(), best.vector()));
                    return new Lookup(true, "L3", round4(bestSim), best.payload(), qv,
                            System.currentTimeMillis() - t0, best.key());
                }
            }
        }

        misses.incrementAndGet();
        return new Lookup(false, "MISS", nearestSim, null, qv, System.currentTimeMillis() - t0, key);
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    public void store(String scope, String query, Params params, float[] vector, String payload) {
        if (!cfg.enabled() || payload == null) {
            return;
        }
        String key = buildKey(scope, query, params);
        long now = System.currentTimeMillis();
        long expiresAt = now + cfg.ttlSeconds() * 1000L;
        Persisted p = new Persisted(query, payload, now, expiresAt, vector);

        putL1(key, p);

        try {
            redis.setEx(key, objectMapper.writeValueAsString(p), cfg.ttlSeconds());
        } catch (Exception e) {
            log.warn("[cache] L2 写入失败（不影响本次请求）：{}", e.getMessage());
        }

        if (vector != null && cfg.semanticThreshold() > 0 && cfg.semanticThreshold() < 1) {
            synchronized (l3) {
                l3.removeIf(s -> s.key().equals(key));
                l3.add(new SemEntry(key, payload, vector, expiresAt, now));
                // 超出容量就丢最旧的
                while (l3.size() > Math.max(1, cfg.semanticCapacity())) {
                    l3.remove(0);
                }
            }
        }
        stores.incrementAndGet();
    }

    // ------------------------------------------------------------------
    // 失效与统计
    // ------------------------------------------------------------------

    /**
     * 清空所有层。入库成功后必须调用 —— 否则用户会拿到基于旧知识库的答案。
     */
    public int clearAll(String reason) {
        int l1Count;
        int l3Count;
        synchronized (l1) {
            l1Count = l1.size();
            l1.clear();
        }
        synchronized (l3) {
            l3Count = l3.size();
            l3.clear();
            semanticLoaded = false;
        }
        int redisCount = 0;
        try {
            redisCount = redis.deleteByPrefix(cfg.prefix(), 5000);
        } catch (Exception e) {
            log.warn("[cache] L2 清空失败：{}", e.getMessage());
        }
        clears.incrementAndGet();
        log.info("[cache] 清空缓存（原因：{}）：L1={} L3={} L2={}", reason, l1Count, l3Count, redisCount);
        return l1Count + l3Count + redisCount;
    }

    /** 只清 L1，保留 L2/L3 —— 用于演示「进程内缓存没了，Redis 还在」 */
    public int clearMemoryOnly() {
        synchronized (l1) {
            int n = l1.size();
            l1.clear();
            return n;
        }
    }

    public Stats stats() {
        long l1Size;
        synchronized (l1) {
            l1Size = l1.size();
        }
        long l3Size;
        synchronized (l3) {
            l3Size = l3.size();
        }
        long l2Keys = 0;
        boolean redisOk = false;
        try {
            redisOk = redis.ping();
            if (redisOk) {
                l2Keys = redis.scan(cfg.prefix() + "*", 5000).size();
            }
        } catch (Exception e) {
            redisOk = false;
        }
        long h = l1Hits.get() + l2Hits.get() + l3Hits.get();
        long total = h + misses.get();
        return new Stats(redisOk, l1Size, l2Keys, l3Size,
                l1Hits.get(), l2Hits.get(), l3Hits.get(), misses.get(), stores.get(), clears.get(),
                total == 0 ? 0 : round4((double) h / total),
                cfg.ttlSeconds(), cfg.semanticThreshold(), cfg.capacity(), cfg.prefix());
    }

    public String redisInfo() {
        return cfg.redisHost() + ":" + cfg.redisPort();
    }

    // ------------------------------------------------------------------

    /**
     * 从 L2 重建语义索引。
     *
     * 为什么必须有这一步：L3 的向量索引只在内存里，进程重启就空了。
     * 如果不重建，重启之后「语义命中」会静默失效 —— 你在监控上只看到命中率从 60% 掉到 20%，
     * 却找不到任何报错。这种「缓存悄悄不工作」比缓存报错难查得多。
     *
     * 只在第一次需要语义比对时做一次，之后就是一个 boolean 判断。
     */
    private void ensureSemanticIndex() {
        if (semanticLoaded) {
            return;
        }
        synchronized (l3) {
            if (semanticLoaded) {
                return;
            }
            // 先清空：store() 可能已经往 l3 里加过几条，直接重建避免重复
            l3.clear();
            try {
                List<String> keys = redis.scan(cfg.prefix() + "*", Math.max(100, cfg.semanticCapacity() * 4));
                long now = System.currentTimeMillis();
                for (String k : keys) {
                    try {
                        String raw = redis.get(k);
                        if (raw == null) {
                            continue;
                        }
                        Persisted p = objectMapper.readValue(raw, Persisted.class);
                        if (p.expiresAt() <= now || p.vector() == null) {
                            continue;
                        }
                        l3.add(new SemEntry(k, p.payload(), p.vector(), p.expiresAt(), p.createdAt()));
                    } catch (Exception ignored) {
                        // 单条坏数据不影响整体
                    }
                }
                log.info("[cache] 已从 L2 重建语义索引：{} 条", l3.size());
            } catch (Exception e) {
                log.warn("[cache] 语义索引重建失败（L3 本次不可用，L1/L2 不受影响）：{}", e.getMessage());
            } finally {
                semanticLoaded = true;
            }
        }
    }

    private void putL1(String key, Persisted p) {
        synchronized (l1) {
            l1.put(key, new LocalEntry(p.expiresAt(), p.payload(), p.vector()));
        }
    }

    /**
     * 键 = 前缀 + 业务 + 归一化问题 + 参数的 SHA-256 前 16 位。
     * 用哈希而不是把问题原文拼进 key：问题可能很长、含特殊字符，Redis key 越短越省内存。
     */
    private String buildKey(String scope, String query, Params params) {
        String sig = normalize(query) + "##" + (params == null ? "" : params.signature());
        return cfg.prefix() + scope + ":" + sha256(sig).substring(0, 16);
    }

    /** 归一化：小写 + 空白折叠 + 去掉句末标点。数字和中间的点必须保留（3.10 ≠ 310） */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[。！？；!?;，,]+$", "")
                .strip();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** 余弦相似度；向量没归一化也能算对 */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double round4(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }
}
