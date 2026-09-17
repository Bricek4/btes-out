package studio.agent.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SourceReadAnalyzerTest {
  @Test
  void sends_every_in_scope_chunk_to_the_model_and_returns_coverage() throws Exception {
    String source = "class Main {\n" + "  void run() {}\n".repeat(2_000) + "}\n";
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of(
        "src/Main.java", source,
        "node_modules/pkg/index.js", "module.exports = {};\n")));
    List<String> prompts = new ArrayList<>();
    var analyzer = new SourceReadAnalyzer(prompt -> {
      prompts.add(prompt);
      return "{\"facts\":[\"observed\"],\"evidence\":[{\"path\":\"src/Main.java\"}]}";
    }, SourceReadCheckpoint.inMemory());

    SourceReadResult result = analyzer.analyze(plan);

    assertEquals(plan.totalChunks(), result.analyzedChunks());
    assertEquals(0, result.failedChunks());
    assertEquals(plan.totalChunks(), prompts.size());
    assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("src/Main.java") && prompt.contains("chunk 0")));
    assertFalse(prompts.stream().anyMatch(prompt -> prompt.contains("node_modules/pkg/index.js")));
    assertTrue(result.coverage().stream().anyMatch(entry -> entry.status() == SourceReadStatus.SKIPPED
        && entry.filePath().equals("node_modules/pkg/index.js")));
    assertTrue(result.synthesisContext().contains("src/Main.java"));
  }

  @Test
  void resumes_from_checkpoint_without_reanalyzing_completed_chunks() throws Exception {
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of("src/Main.java", "class Main {}\n")));
    SourceReadChunk chunk = plan.file("src/Main.java").chunks().getFirst();
    var checkpoint = SourceReadCheckpoint.inMemory();
    checkpoint.complete(chunk, "{\"facts\":[\"already read\"]}");
    var calls = new AtomicInteger();

    SourceReadResult result = new SourceReadAnalyzer(prompt -> {
      calls.incrementAndGet();
      return "{\"facts\":[\"new\"]}";
    }, checkpoint).analyze(plan);

    assertEquals(0, calls.get());
    assertEquals(1, result.analyzedChunks());
    assertEquals(0, result.failedChunks());
  }

  @Test
  void records_a_failed_chunk_instead_of_claiming_complete_coverage() throws Exception {
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of("src/Main.java", "class Main {}\n")));
    var analyzer = new SourceReadAnalyzer(prompt -> { throw new IllegalStateException("provider unavailable"); },
        SourceReadCheckpoint.inMemory());

    SourceReadResult result = analyzer.analyze(plan);

    assertEquals(0, result.analyzedChunks());
    assertEquals(1, result.failedChunks());
    assertFalse(result.complete());
    assertTrue(result.coverage().stream().anyMatch(entry -> entry.status() == SourceReadStatus.FAILED));
  }

  @Test
  void analyzes_chunks_with_bounded_parallelism() throws Exception {
    String source = "class Main {}\n".repeat(4_000);
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of("src/Main.java", source)));
    var inFlight = new AtomicInteger();
    var maximum = new AtomicInteger();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var analyzer = new SourceReadAnalyzer(prompt -> {
      int active = inFlight.incrementAndGet();
      maximum.accumulateAndGet(active, Math::max);
      entered.countDown();
      try {
        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("parallelism test timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("parallelism test interrupted");
      } finally {
        inFlight.decrementAndGet();
      }
      return "{\"facts\":[]}";
    }, SourceReadCheckpoint.inMemory(), ignored -> { }, 2);

    var execution = java.util.concurrent.CompletableFuture.supplyAsync(() -> analyzer.analyze(plan));
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    release.countDown();
    SourceReadResult result = execution.get(5, TimeUnit.SECONDS);

    assertTrue(maximum.get() >= 2);
    assertTrue(result.complete());
  }

  @Test
  void submits_single_chunk_files_concurrently_instead_of_waiting_file_by_file() throws Exception {
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of(
        "src/One.java", "class One {}\n",
        "src/Two.java", "class Two {}\n",
        "src/Three.java", "class Three {}\n",
        "src/Four.java", "class Four {}\n")));
    var inFlight = new AtomicInteger();
    var maximum = new AtomicInteger();
    var entered = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var analyzer = new SourceReadAnalyzer(prompt -> {
      int active = inFlight.incrementAndGet();
      maximum.accumulateAndGet(active, Math::max);
      entered.countDown();
      try {
        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("cross-file parallelism timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("cross-file parallelism interrupted");
      } finally {
        inFlight.decrementAndGet();
      }
      return "{\"facts\":[]}";
    }, SourceReadCheckpoint.inMemory(), ignored -> { }, 2,
        new SourceReadLimiter(2));

    var execution = java.util.concurrent.CompletableFuture.supplyAsync(() -> analyzer.analyze(plan));
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    release.countDown();
    assertTrue(execution.get(5, TimeUnit.SECONDS).complete());
    assertEquals(2, maximum.get());
  }

  @Test
  void shares_a_global_provider_permit_across_analyzers() throws Exception {
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of(
        "src/One.java", "class One {}\n",
        "src/Two.java", "class Two {}\n")));
    var limiter = new SourceReadLimiter(1);
    var inFlight = new AtomicInteger();
    var maximum = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ArtifactGenerationService.ModelGateway model = prompt -> {
      int active = inFlight.incrementAndGet();
      maximum.accumulateAndGet(active, Math::max);
      entered.countDown();
      try {
        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("permit test timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("permit test interrupted");
      } finally {
        inFlight.decrementAndGet();
      }
      return "{\"facts\":[]}";
    };
    var first = new SourceReadAnalyzer(model, SourceReadCheckpoint.inMemory(), ignored -> { }, 2, limiter);
    var second = new SourceReadAnalyzer(model, SourceReadCheckpoint.inMemory(), ignored -> { }, 2, limiter);

    var one = java.util.concurrent.CompletableFuture.supplyAsync(() -> first.analyze(plan));
    var two = java.util.concurrent.CompletableFuture.supplyAsync(() -> second.analyze(plan));
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    Thread.sleep(100);
    assertEquals(1, maximum.get());
    release.countDown();
    assertTrue(one.get(5, TimeUnit.SECONDS).complete());
    assertTrue(two.get(5, TimeUnit.SECONDS).complete());
  }

  @Test
  void retries_only_the_current_chunk_after_a_transient_provider_failure() throws Exception {
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of("src/Main.java", "class Main {}\n")));
    var checkpoint = new InMemorySourceReadCheckpoint();
    var calls = new AtomicInteger();
    var analyzer = new SourceReadAnalyzer(prompt -> {
      if (calls.incrementAndGet() < 3) throw new SpringAiModelGateway.ModelCallFailure("MODEL_REQUEST_FAILED");
      return "{\"facts\":[\"recovered\"]}";
    }, checkpoint);

    SourceReadResult result = analyzer.analyze(plan);

    assertTrue(result.complete());
    assertEquals(3, calls.get());
    assertEquals(3, checkpoint.attempts(plan.file("src/Main.java").chunks().getFirst().chunkId()));
  }

  @Test
  void keeps_utf8_summaries_within_the_checkpoint_byte_limit() throws Exception {
    SourceReadPlan plan = SourceReadPlanner.plan(zip(Map.of("docs/中文.md", "内容\n")));
    String oversized = "{\"facts\":[\"" + "中文事实".repeat(4_000) + "\"]}";
    var analyzer = new SourceReadAnalyzer(prompt -> oversized, SourceReadCheckpoint.inMemory());

    SourceReadResult result = analyzer.analyze(plan);

    assertTrue(result.complete());
    String summary = result.summaries().values().iterator().next();
    assertTrue(summary.getBytes(StandardCharsets.UTF_8).length <= 12_000);
  }

  private static byte[] zip(Map<String, String> entries) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }
}
