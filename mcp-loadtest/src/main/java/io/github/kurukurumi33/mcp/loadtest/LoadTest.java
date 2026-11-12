package io.github.kurukurumi33.mcp.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Closed-loop load generator for the gateway.
 *
 * <p>One virtual thread per simulated client; each sends a {@code tools/call} and waits for the
 * answer before sending the next. That models real agents better than a fixed-rate open-loop
 * sender: a slow backend slows the clients down too, which is exactly what the bulkhead and the
 * shared bucket are supposed to constrain.
 *
 * <p>No dependencies on purpose. It measures the gateway, so it must not share its thread pools,
 * serializers or framework with it.
 *
 * <pre>
 * java -jar mcp-loadtest/target/mcp-loadtest-0.1.0-SNAPSHOT.jar \
 *   --url http://127.0.0.1:8931/mcp --concurrency 32 --duration 15
 * </pre>
 */
public final class LoadTest {

    private static final String PROTOCOL_VERSION = "2026-07-28";

    private static final String META = "{\"io.modelcontextprotocol/protocolVersion\":\""
            + PROTOCOL_VERSION + "\",\"io.modelcontextprotocol/clientCapabilities\":{}}";

    private final Config config;
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong started = new AtomicLong();

    private LoadTest(Config config) {
        this.config = config;
    }

    public static void main(String[] argv) throws Exception {
        Config config;
        try {
            config = Config.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            Config.printUsage();
            System.exit(2);
            return;
        }
        new LoadTest(config).run();
    }

    private void run() throws Exception {
        System.out.println("targets     : " + config.targets().stream()
                .map(URI::toString).reduce((a, b) -> a + ", " + b).orElse(""));
        System.out.println("tool        : " + config.tool());
        System.out.println("arguments   : " + config.args());
        System.out.println("clients     : " + config.concurrency() + " virtual threads");
        System.out.println("think time  : " + config.thinkMs() + " ms per client between calls");
        System.out.println("length      : " + (config.requests() > 0
                ? config.requests() + " requests"
                : config.duration().toSeconds() + " s"));
        System.out.println();

        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .executor(clients)
                    .build();

            warmUp(http);

            long deadline = System.nanoTime() + config.duration().toNanos();
            long measuredFrom = System.nanoTime();

            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < config.concurrency(); i++) {
                futures.add(clients.submit(() -> work(http, deadline)));
            }

            Result total = Result.empty();
            for (Future<Result> future : futures) {
                total = total.plus(future.get());
            }
            long elapsedNanos = System.nanoTime() - measuredFrom;
            report(total, elapsedNanos);
        }
    }

    private void warmUp(HttpClient http) throws Exception {
        for (int i = 0; i < config.warmup(); i++) {
            try {
                http.send(request(config.targets().get(i % config.targets().size())),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                System.out.println("warmup request failed: " + e);
            }
        }
    }

    private Result work(HttpClient http, long deadline) {
        List<Long> okLatencies = new ArrayList<>();
        List<Long> rejectedLatencies = new ArrayList<>();
        long ok = 0;
        long concurrencyRejected = 0;
        long rateRejected = 0;
        long deadlineExceeded = 0;
        long other = 0;
        long transportErrors = 0;

        while (true) {
            if (config.requests() > 0) {
                if (started.getAndIncrement() >= config.requests()) {
                    break;
                }
            } else if (System.nanoTime() >= deadline) {
                break;
            }

            long id = ids.incrementAndGet();
            URI target = config.targets().get((int) (id % config.targets().size()));
            long startedAt = System.nanoTime();
            try {
                HttpResponse<String> response =
                        http.send(request(target), HttpResponse.BodyHandlers.ofString());
                long latencyUs = (System.nanoTime() - startedAt) / 1_000;

                int status = response.statusCode();
                if (status == 200) {
                    ok++;
                    okLatencies.add(latencyUs);
                } else if (status == 429) {
                    rejectedLatencies.add(latencyUs);
                    if (response.body().contains("\"rate_limit\"")) {
                        rateRejected++;
                    } else {
                        concurrencyRejected++;
                    }
                } else if (status == 504) {
                    deadlineExceeded++;
                } else {
                    other++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                transportErrors++;
            }

            if (config.thinkMs() > 0) {
                try {
                    Thread.sleep(config.thinkMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new Result(ok, concurrencyRejected, rateRejected, deadlineExceeded, other,
                transportErrors,
                okLatencies.stream().mapToLong(Long::longValue).toArray(),
                rejectedLatencies.stream().mapToLong(Long::longValue).toArray());
    }

    private HttpRequest request(URI target) {
        long id = ids.incrementAndGet();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + config.tool()
                + "\",\"arguments\":" + config.args()
                + ",\"_meta\":" + META + "}}";
        return HttpRequest.newBuilder(target)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", config.tool())
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private void report(Result total, long elapsedNanos) {
        long sent = total.sent();
        double seconds = elapsedNanos / 1_000_000_000.0;
        double throughput = seconds > 0 ? sent / seconds : 0;

        System.out.println("========== result ==========");
        System.out.printf(Locale.ROOT, "attempts        %d  (%.1f/s)%n", sent, throughput);
        System.out.printf(Locale.ROOT, "ok              %d  (%.1f%%, %.1f/s goodput)%n",
                total.ok(), percent(total.ok(), sent), seconds > 0 ? total.ok() / seconds : 0);
        System.out.printf(Locale.ROOT, "429 concurrency %d%n", total.concurrencyRejected());
        System.out.printf(Locale.ROOT, "429 rate limit  %d%n", total.rateRejected());
        System.out.printf(Locale.ROOT, "504 deadline    %d%n", total.deadlineExceeded());
        System.out.printf(Locale.ROOT, "other status    %d%n", total.other());
        System.out.printf(Locale.ROOT, "transport error %d%n", total.transportErrors());
        System.out.printf(Locale.ROOT, "elapsed         %.2f s%n", seconds);

        long[] samples = total.okLatencies();
        if (samples.length > 0) {
            Arrays.sort(samples);
            System.out.printf(Locale.ROOT,
                    "latency ok      p50 %.1f ms | p95 %.1f ms | p99 %.1f ms | max %.1f ms%n",
                    percentile(samples, 50) / 1000.0,
                    percentile(samples, 95) / 1000.0,
                    percentile(samples, 99) / 1000.0,
                    samples[samples.length - 1] / 1000.0);
        }
        long[] rejected = total.rejectedLatencies();
        if (rejected.length > 0) {
            Arrays.sort(rejected);
            System.out.printf(Locale.ROOT, "latency 429     p50 %.1f ms | p99 %.1f ms%n",
                    percentile(rejected, 50) / 1000.0,
                    percentile(rejected, 99) / 1000.0);
        }
        System.out.println("(latency is client-side, so it includes network and client overhead)");
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0 : 100.0 * part / total;
    }

    private static long percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private record Result(long ok, long concurrencyRejected, long rateRejected,
                          long deadlineExceeded, long other, long transportErrors,
                          long[] okLatencies, long[] rejectedLatencies) {

        static Result empty() {
            return new Result(0, 0, 0, 0, 0, 0, new long[0], new long[0]);
        }

        long sent() {
            return ok + concurrencyRejected + rateRejected + deadlineExceeded + other
                    + transportErrors;
        }

        Result plus(Result other) {
            return new Result(ok + other.ok,
                    concurrencyRejected + other.concurrencyRejected,
                    rateRejected + other.rateRejected,
                    deadlineExceeded + other.deadlineExceeded,
                    this.other + other.other,
                    transportErrors + other.transportErrors,
                    merge(okLatencies, other.okLatencies),
                    merge(rejectedLatencies, other.rejectedLatencies));
        }

        private static long[] merge(long[] left, long[] right) {
            long[] merged = new long[left.length + right.length];
            System.arraycopy(left, 0, merged, 0, left.length);
            System.arraycopy(right, 0, merged, left.length, right.length);
            return merged;
        }
    }

    private record Config(List<URI> targets, int concurrency, Duration duration, long requests,
                          String tool, String args, int warmup, long thinkMs) {

        static Config parse(String[] argv) {
            List<URI> targets = new ArrayList<>();
            int concurrency = 16;
            long durationSeconds = 10;
            long requests = 0;
            String tool = "analytics_run_sql";
            String args = "{\"sql\":\"SELECT 1 AS n\"}";
            int warmup = 3;
            long thinkMs = 0;

            for (int i = 0; i < argv.length; i++) {
                String option = argv[i];
                switch (option) {
                    case "--url" -> targets.add(URI.create(value(argv, ++i, option)));
                    case "--concurrency" -> concurrency = Integer.parseInt(value(argv, ++i, option));
                    case "--duration" -> durationSeconds = Long.parseLong(value(argv, ++i, option));
                    case "--requests" -> requests = Long.parseLong(value(argv, ++i, option));
                    case "--tool" -> tool = value(argv, ++i, option);
                    case "--args" -> args = value(argv, ++i, option);
                    case "--warmup" -> warmup = Integer.parseInt(value(argv, ++i, option));
                    case "--think-ms" -> thinkMs = Long.parseLong(value(argv, ++i, option));
                    case "--help", "-h" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }

            if (targets.isEmpty()) {
                targets.add(URI.create("http://127.0.0.1:8931/mcp"));
            }
            if (concurrency < 1) {
                throw new IllegalArgumentException("--concurrency must be at least 1");
            }
            if (requests <= 0 && durationSeconds < 1) {
                throw new IllegalArgumentException("--duration must be at least 1 second");
            }
            if (!tool.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("--tool must be a plain tool name");
            }
            return new Config(List.copyOf(targets), concurrency, Duration.ofSeconds(durationSeconds),
                    requests, tool, args, Math.max(warmup, 0), Math.max(thinkMs, 0));
        }

        private static String value(String[] argv, int index, String option) {
            if (index >= argv.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return argv[index];
        }

        static void printUsage() {
            System.out.print("""
                    usage: java -jar mcp-loadtest.jar [options]

                      --url URI           target endpoint, repeatable; requests are spread round-robin
                                          (default http://127.0.0.1:8931/mcp)
                      --concurrency N     simulated clients, one virtual thread each (default 16)
                      --duration S        seconds to keep sending (default 10)
                      --requests N        fixed number of requests instead of a duration
                      --tool NAME         tool to call (default analytics_run_sql)
                      --args JSON         arguments object (default {"sql":"SELECT 1 AS n"})
                      --warmup N          unmeasured requests before the run (default 3)
                      --think-ms N        pause each client between calls, to model an agent
                                          thinking rather than hammering (default 0)
                      -h, --help          this text
                    """);
        }
    }
}