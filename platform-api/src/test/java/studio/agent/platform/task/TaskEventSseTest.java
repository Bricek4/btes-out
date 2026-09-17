package studio.agent.platform.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TaskEventSseTest {
  @Test
  void writes_a_complete_sse_frame_that_can_be_parsed_without_async_dispatch() throws Exception {
    var event = new LinkedHashMap<String, Object>();
    event.put("sequence", 7L);
    event.put("status", "QUEUED");
    event.put("type", "STATUS");
    event.put("message", "Task queued");
    event.put("details", "{}");
    event.put("occurredAt", "2026-09-17T03:36:04Z");

    String frame = TaskController.formatSseEvent(event);

    assertTrue(frame.startsWith("id: 7\n"));
    assertTrue(frame.contains("event: task-event\n"));
    assertTrue(frame.endsWith("\n\n"));
    String payload = frame.lines()
        .filter(line -> line.startsWith("data: "))
        .findFirst()
        .orElseThrow()
        .substring("data: ".length());
    assertEquals("QUEUED", new ObjectMapper().readTree(payload).path("status").asString());
  }
}
