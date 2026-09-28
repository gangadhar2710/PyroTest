package com.telecom.loadtest;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public final class LoadTest {

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/api/v1/charge";
        int tps = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int total = args.length > 2 ? Integer.parseInt(args[2]) : 500_000;

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        long[] latenciesNanos = new long[total];
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong windowLatency = new AtomicLong();
        AtomicInteger windowCount = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        long periodNanos = 1_000_000_000L / tps;
        long startWall = System.nanoTime();

        scheduler.scheduleAtFixedRate(() -> {
            int i = sent.getAndIncrement();
            if (i >= total) {
                return;
            }
            String body = String.format("{\"msisdn\":\"91%010d\",\"requestedUnits\":1048576}", 9_000_000_000L + i);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            long t0 = System.nanoTime();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, err) -> {
                long lat = System.nanoTime() - t0;
                latenciesNanos[i] = lat;
                if (err == null && resp.statusCode() == 200) {
                    ok.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                windowLatency.addAndGet(lat);
                windowCount.incrementAndGet();
                completed.incrementAndGet();
                done.countDown();
            });
        }, 0, periodNanos, TimeUnit.NANOSECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            int c = windowCount.getAndSet(0);
            long l = windowLatency.getAndSet(0);
            double heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0;
            System.out.printf("t=%4ds sent=%d done=%d ok=%d failed=%d avgLat(10s)=%.1fms loadgenHeap=%.0fMB%n",
                    (System.nanoTime() - startWall) / 1_000_000_000L, sent.get(), completed.get(), ok.get(),
                    failed.get(), c == 0 ? 0.0 : l / (double) c / 1e6, heapMb);
        }, 10, 10, TimeUnit.SECONDS);

        done.await();
        scheduler.shutdownNow();

        double elapsedSec = (System.nanoTime() - startWall) / 1e9;
        long[] sorted = latenciesNanos.clone();
        Arrays.sort(sorted);
        System.out.println("================ LOAD TEST REPORT ================");
        System.out.printf("Target        : %d TPS, %d transactions%n", tps, total);
        System.out.printf("Elapsed       : %.1f s  (actual %.1f TPS)%n", elapsedSec, total / elapsedSec);
        System.out.printf("Success/Fail  : %d / %d%n", ok.get(), failed.get());
        System.out.printf("Latency p50   : %.1f ms%n", pct(sorted, 50));
        System.out.printf("Latency p95   : %.1f ms  (requirement: < 100 ms)%n", pct(sorted, 95));
        System.out.printf("Latency p99   : %.1f ms%n", pct(sorted, 99));
        System.out.printf("Latency max   : %.1f ms%n", sorted[sorted.length - 1] / 1e6);
        System.out.println("==================================================");

        try (PrintWriter w = new PrintWriter("latencies.csv")) {
            w.println("seq,latency_ms");
            for (int i = 0; i < latenciesNanos.length; i++) {
                w.println(i + "," + latenciesNanos[i] / 1e6);
            }
        }
    }

    private static double pct(long[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] / 1e6;
    }
}
