// Stand-in for a Spring Boot service in tests: Micrometer-style /actuator/prometheus (with histogram buckets),
// /actuator/health, /v3/api-docs and a CPU-heavy /api/orders/{id}, so JFR has real hot spots.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

public class Demo {
    static final double[] LE = {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1};
    static final AtomicLong[] BUCKETS = new AtomicLong[LE.length];
    static final AtomicLong COUNT = new AtomicLong(), ERRORS = new AtomicLong();
    static final DoubleAdder SUM = new DoubleAdder();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < LE.length; i++) BUCKETS[i] = new AtomicLong();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        s.setExecutor(Executors.newFixedThreadPool(8));
        s.createContext("/actuator/health", x -> send(x, 200, "{\"status\":\"UP\"}"));
        s.createContext("/actuator/prometheus", x -> send(x, 200, prometheus()));
        s.createContext("/v3/api-docs", x -> send(x, 200, "{\"openapi\":\"3.0.1\",\"paths\":{\"/api/orders/{id}\":{\"get\":{\"operationId\":\"getOrder\","
                + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\"}]}},\"/api/orders\":{\"post\":{\"operationId\":\"createOrder\"}},"
                + "\"/actuator/health\":{\"get\":{}}}}"));
        s.createContext("/api/orders/", x -> {
            long t0 = System.nanoTime();
            int status = x.getRequestURI().getPath().endsWith("/0") ? 500 : 200;
            String body = OrderService.price(x.getRequestURI().getPath());
            send(x, status, body);
            double secs = (System.nanoTime() - t0) / 1e9;
            COUNT.incrementAndGet(); SUM.add(secs);
            if (status >= 500) ERRORS.incrementAndGet();
            for (int i = 0; i < LE.length; i++) if (secs <= LE[i]) BUCKETS[i].incrementAndGet();
        });
        s.start();
    }

    static String prometheus() {
        StringBuilder b = new StringBuilder("# TYPE http_server_requests_seconds histogram\n");
        long ok = COUNT.get() - ERRORS.get();
        b.append("http_server_requests_seconds_count{method=\"GET\",status=\"200\",uri=\"/api/orders/{id}\",outcome=\"SUCCESS\"} ").append(ok).append('\n');
        b.append("http_server_requests_seconds_count{method=\"GET\",status=\"500\",uri=\"/api/orders/{id}\",outcome=\"SERVER_ERROR\"} ").append(ERRORS.get()).append('\n');
        b.append("http_server_requests_seconds_sum{method=\"GET\",status=\"200\",uri=\"/api/orders/{id}\",outcome=\"SUCCESS\"} ").append(SUM.sum()).append('\n');
        b.append("http_server_requests_seconds_count{method=\"GET\",status=\"200\",uri=\"/actuator/prometheus\",outcome=\"SUCCESS\"} 999\n");
        for (int i = 0; i < LE.length; i++)
            b.append("http_server_requests_seconds_bucket{method=\"GET\",status=\"200\",uri=\"/api/orders/{id}\",le=\"").append(LE[i]).append("\"} ").append(BUCKETS[i].get()).append('\n');
        b.append("http_server_requests_seconds_bucket{method=\"GET\",status=\"200\",uri=\"/api/orders/{id}\",le=\"+Inf\"} ").append(COUNT.get()).append('\n');
        Runtime rt = Runtime.getRuntime();
        b.append("jvm_memory_used_bytes{area=\"heap\",id=\"G1 Eden Space\"} ").append(rt.totalMemory() - rt.freeMemory()).append('\n');
        b.append("jvm_memory_max_bytes{area=\"heap\",id=\"G1 Eden Space\"} ").append(rt.maxMemory()).append('\n');
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        b.append("process_cpu_usage ").append(Math.max(0, os.getProcessCpuLoad())).append('\n');
        b.append("jvm_threads_live_threads ").append(Thread.activeCount()).append('\n');
        b.append("logback_events_total{level=\"error\"} ").append(ERRORS.get()).append('\n');
        return b.toString();
    }

    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, bytes.length);
        try (var o = x.getResponseBody()) { o.write(bytes); }
    }

    static final class OrderService {
        static String price(String path) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = path.getBytes(StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4000; i++) { d = md.digest(d); sb.append(d[0]); }
                return "{\"price\":" + sb.length() + "}";
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
}
