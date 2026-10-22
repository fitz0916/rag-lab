package com.raglab.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Redis 客户端（只实现缓存要用的 5 个命令）。
 *
 * 为什么手写、不引 Jedis/Lettuce？
 *   1. 这个项目只需要 SET / GET / DEL / SCAN / DBSIZE，引一个连接池 + 集群 + 编码器齐全的
 *      客户端，反而看不清「缓存到底发生了什么」。
 *   2. Redis 协议（RESP2）简单到可以当场读完：先发一个「参数个数 + 每个参数」的数组，
 *      服务端回一行类型标记 + 内容。看懂这 100 行，以后遇到任何 Redis 客户端都不慌。
 *
 * 协议速记：
 *   +OK\r\n              简单字符串
 *   -ERR msg\r\n         错误
 *   :123\r\n             整数
 *   $5\r\nhello\r\n      批量字符串（$ 后面是字节数，-1 表示 null）
 *   *2\r\n...            数组（* 后面是元素个数）
 *
 * 连接管理：单连接 + synchronized。缓存操作都是亚毫秒级，够用；
 * 出异常就断开重连一次 —— 不要让「缓存挂了」变成「业务挂了」。
 */
public class RedisClient {

    private static final Logger log = LoggerFactory.getLogger(RedisClient.class);

    private final String host;
    private final int port;
    private final int timeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public RedisClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public synchronized boolean ping() {
        try {
            Object r = command("PING");
            return r instanceof String s && "PONG".equalsIgnoreCase(s);
        } catch (Exception e) {
            return false;
        }
    }

    /** SET key value EX ttl */
    public synchronized void setEx(String key, String value, int ttlSeconds) {
        command("SET", key, value, "EX", String.valueOf(Math.max(1, ttlSeconds)));
    }

    /** GET key，不存在返回 null */
    public synchronized String get(String key) {
        Object r = command("GET", key);
        return r == null ? null : String.valueOf(r);
    }

    public synchronized long del(String... keys) {
        if (keys.length == 0) {
            return 0;
        }
        List<String> args = new ArrayList<>(keys.length + 1);
        args.add("DEL");
        for (String k : keys) {
            args.add(k);
        }
        Object r = command(args.toArray(new String[0]));
        return r instanceof Long n ? n : 0;
    }

    public synchronized long dbSize() {
        Object r = command("DBSIZE");
        return r instanceof Long n ? n : 0;
    }

    /**
     * 按前缀扫 key。SCAN 是游标式遍历，不会像 KEYS 那样阻塞 Redis。
     *
     * @param maxKeys 上限，防止缓存爆炸时一次拉太多
     */
    public synchronized List<String> scan(String pattern, int maxKeys) {
        List<String> out = new ArrayList<>();
        String cursor = "0";
        do {
            Object r = command("SCAN", cursor, "MATCH", pattern, "COUNT", "200");
            if (!(r instanceof List<?> arr) || arr.size() != 2) {
                break;
            }
            cursor = String.valueOf(arr.get(0));
            if (arr.get(1) instanceof List<?> keys) {
                for (Object k : keys) {
                    out.add(String.valueOf(k));
                    if (out.size() >= maxKeys) {
                        return out;
                    }
                }
            }
        } while (!"0".equals(cursor));
        return out;
    }

    /** 按前缀批量删除 */
    public synchronized int deleteByPrefix(String prefix, int maxKeys) {
        List<String> keys = scan(prefix + "*", maxKeys);
        if (keys.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        List<String> batch = new ArrayList<>();
        for (String k : keys) {
            batch.add(k);
            if (batch.size() >= 100) {
                deleted += (int) del(batch.toArray(new String[0]));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            deleted += (int) del(batch.toArray(new String[0]));
        }
        return deleted;
    }

    // ------------------------------------------------------------------
    // 协议实现
    // ------------------------------------------------------------------

    private Object command(String... args) {
        try {
            ensureConnected();
            writeCommand(args);
            return readReply();
        } catch (IOException e) {
            // 连接可能被服务端断开（超时 / 重启），重连一次再试
            closeQuietly();
            try {
                ensureConnected();
                writeCommand(args);
                return readReply();
            } catch (IOException e2) {
                throw new IllegalStateException("Redis 命令失败：" + e2.getMessage(), e2);
            }
        }
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        log.info("[cache] 已连接 Redis {}:{}", host, port);
    }

    private void writeCommand(String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(a).append("\r\n");
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private Object readReply() throws IOException {
        int type = in.read();
        if (type < 0) {
            throw new IOException("Redis 连接已关闭");
        }
        switch (type) {
            case '+':
                return readLine();
            case '-':
                throw new IllegalStateException("Redis 返回错误：" + readLine());
            case ':':
                return Long.parseLong(readLine());
            case '$': {
                int len = Integer.parseInt(readLine());
                if (len < 0) {
                    return null;
                }
                byte[] buf = readN(len);
                readLine(); // 末尾 CRLF
                return new String(buf, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(readLine());
                if (n < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(readReply());
                }
                return list;
            }
            default:
                throw new IOException("未知的 RESP 类型：" + (char) type);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') {
                byte[] arr = buf.toByteArray();
                return new String(arr, 0, Math.max(0, arr.length - 1), StandardCharsets.UTF_8);
            }
            buf.write(b);
            prev = b;
        }
        throw new IOException("读取 Redis 响应行失败");
    }

    private byte[] readN(int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) {
                throw new IOException("读取 Redis 数据提前结束");
            }
            read += n;
        }
        return buf;
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }
}
