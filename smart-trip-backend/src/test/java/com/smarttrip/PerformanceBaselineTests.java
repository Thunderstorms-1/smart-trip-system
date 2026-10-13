package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarttrip.common.*;
import com.smarttrip.entity.*;
import com.smarttrip.service.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Opt-in benchmark: real loopback HTTP and MySQL; no production SQL rewrites or global DB settings.
 */
@EnabledIfEnvironmentVariable(named = "SMARTTRIP_PERFORMANCE", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.address=127.0.0.1",
      "spring.datasource.url=jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
      "security.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
      "ai.llm.enabled=false",
      "geo.enabled=false",
      "logging.level.com.smarttrip.config.TraceIdFilter=WARN"
    })
@Import(PerformanceBaselineTests.FixtureConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceBaselineTests {
  record Query(String sql, List<Object> args, double durationMs, int rows) {}

  static class CapturingJdbc extends JdbcTemplate {
    volatile List<Query> active;

    CapturingJdbc(DataSource source) {
      super(source);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      List<Query> capture = active;
      long start = System.nanoTime();
      var result = super.query(sql, mapper, args);
      if (capture != null)
        capture.add(
            new Query(
                sql,
                new ArrayList<>(Arrays.asList(args)),
                (System.nanoTime() - start) / 1e6,
                result.size()));
      return result;
    }
  }

  @TestConfiguration
  static class FixtureConfig {
    @Bean
    @Primary
    CapturingJdbc benchmarkJdbc(DataSource source) {
      return new CapturingJdbc(source);
    }
  }

  record Sample(
      double clientMs,
      double jdbcMs,
      int jdbcQueries,
      int responseBytes,
      int rows,
      String cursor,
      boolean more,
      String traceId,
      List<Query> queries) {}

  @Autowired CapturingJdbc db;
  @Autowired UserService users;
  @Autowired WorkbenchService trips;
  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .version(HttpClient.Version.HTTP_1_1)
          .build();
  final List<Long> accounts = new ArrayList<>();
  final List<Map<String, Object>> cases = new ArrayList<>();
  final String marker = "perf_" + Long.toString(System.nanoTime(), 36);
  Long owner, other, viewer;
  Map<Long, String> tokens = new HashMap<>();
  int seeded;

  Long account(int n) {
    var r = new RegisterDTO();
    r.setUsername(marker + "_" + n);
    r.setPassword("PerformanceTest2026!");
    r.setNickname("性能基线测试用户");
    Long id = users.register(r);
    accounts.add(id);
    tokens.put(id, JwtUtil.generateToken(id, r.getUsername(), 0));
    return id;
  }

  void seedTo(int count) {
    int start = seeded;
    List<Object[]> plans = new ArrayList<>();
    for (int n = start; n < count; n++)
      plans.add(
          new Object[] {n % 2 == 0 ? owner : other, marker + String.format("_%06d", n), "成都"});
    db.batchUpdate(
        "INSERT INTO"
            + " trip_plan(user_id,title,destination,total_days,total_budget,is_ai_generated,is_public)"
            + " VALUES(?,?,?,1,0,0,1)",
        plans);
    var rows =
        db.queryForList(
                "SELECT id,user_id,title FROM trip_plan WHERE user_id IN (?,?) ORDER BY id",
                owner,
                other)
            .subList(start, count);
    List<Object[]> days = new ArrayList<>(), posts = new ArrayList<>(), members = new ArrayList<>();
    for (var row : rows) {
      Long id = ((Number) row.get("id")).longValue(),
          author = ((Number) row.get("user_id")).longValue();
      String title = (String) row.get("title");
      days.add(new Object[] {id});
      var snapshot =
          Map.of(
              "id",
              id,
              "title",
              title,
              "destination",
              "成都",
              "totalDays",
              1,
              "totalBudget",
              0,
              "isPublic",
              1,
              "days",
              List.of(Map.of("dayIndex", 1, "items", List.of())));
      try {
        posts.add(new Object[] {id, author, title, json.writeValueAsString(snapshot)});
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      if (author.equals(other)) {
        members.add(new Object[] {id, owner});
        members.add(new Object[] {id, viewer});
      }
    }
    db.batchUpdate("INSERT INTO trip_day(trip_id,day_index) VALUES(?,1)", days);
    db.batchUpdate(
        "INSERT INTO square_post(trip_id,user_id,title,destination,snapshot_json)"
            + " VALUES(?,?,?,'成都',?)",
        posts);
    db.batchUpdate("INSERT INTO trip_member(trip_id,user_id,role) VALUES(?,?,'VIEWER')", members);
    seeded = count;
  }

  Sample request(String path, Long actor) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + tokens.get(actor))
            .GET()
            .build();
    long start = System.nanoTime();
    var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    double elapsed = (System.nanoTime() - start) / 1e6;
    assertEquals(
        200,
        response.statusCode(),
        "Benchmark must measure a successful business response: " + path);
    var body = json.readTree(response.body());
    assertEquals(200, body.path("code").asInt());
    var data = body.path("data");
    int rows = data.isArray() ? data.size() : data.path("items").size();
    String cursor = data.path("nextCursor").isTextual() ? data.path("nextCursor").asText() : null;
    return new Sample(
        elapsed,
        0,
        0,
        response.body().length,
        rows,
        cursor,
        data.path("hasMore").asBoolean(),
        response.headers().firstValue("X-Trace-Id").orElseThrow(),
        List.of());
  }

  Sample captured(String path, Long actor) throws Exception {
    var queries = new CopyOnWriteArrayList<Query>();
    db.active = queries;
    try {
      var sample = request(path, actor);
      return new Sample(
          sample.clientMs(),
          queries.stream().mapToDouble(Query::durationMs).sum(),
          queries.size(),
          sample.responseBytes(),
          sample.rows(),
          sample.cursor(),
          sample.more(),
          sample.traceId(),
          List.copyOf(queries));
    } finally {
      db.active = null;
    }
  }

  double percentile(List<Double> values, double p) {
    var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * p) - 1));
  }

  String feed(String q) {
    return "/community/posts?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
  }

  Map<String, Object> measure(
      String name,
      int scale,
      String path,
      Long actor,
      int expectedRows,
      int expectedJdbcQueries,
      int repetitions)
      throws Exception {
    var first = captured(path, actor);
    assertEquals(expectedRows, first.rows());
    assertEquals(expectedJdbcQueries, first.jdbcQueries());
    for (int n = 0; n < 3; n++) request(path, actor);
    List<Sample> measured = new ArrayList<>();
    for (int n = 0; n < repetitions; n++) {
      var sample = captured(path, actor);
      assertEquals(expectedRows, sample.rows());
      assertEquals(expectedJdbcQueries, sample.jdbcQueries());
      assertTrue(TraceContext.valid(sample.traceId()));
      measured.add(sample);
    }
    var clientTimes = measured.stream().map(Sample::clientMs).toList();
    var jdbcTimes = measured.stream().map(Sample::jdbcMs).toList();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("fixturePlans", scale);
    result.put("samples", repetitions);
    result.put("warmupRequests", 3);
    result.put("firstExecutionMs", first.clientMs());
    result.put("httpP50Ms", percentile(clientTimes, .5));
    result.put("httpP95Ms", percentile(clientTimes, .95));
    result.put("jdbcP50Ms", percentile(jdbcTimes, .5));
    result.put("jdbcP95Ms", percentile(jdbcTimes, .95));
    result.put("responseBytes", measured.get(0).responseBytes());
    result.put("resultRows", expectedRows);
    result.put("jdbcSelectsPerRequest", expectedJdbcQueries);
    result.put("httpSamplesMs", clientTimes);
    result.put("jdbcSamplesMs", jdbcTimes);
    result.put("exampleTraceId", first.traceId());
    List<Map<String, Object>> explains = new ArrayList<>();
    for (var query : first.queries()) {
      assertTrue(query.sql().stripLeading().startsWith("SELECT"));
      var plan = db.queryForList("EXPLAIN ANALYZE " + query.sql(), query.args().toArray());
      explains.add(
          Map.of(
              "sql",
              query.sql(),
              "fixtureBindings",
              query.args(),
              "sqlFingerprint",
              CommunityService.hash(query.sql()),
              "returnedRows",
              query.rows(),
              "executionPlan",
              plan));
    }
    result.put("plans", explains);
    cases.add(result);
    return result;
  }

  void interactions(int count, List<Long> posts) {
    while (accounts.size() < Math.max(3, count)) account(accounts.size());
    List<Object[]> interactions = new ArrayList<>(), comments = new ArrayList<>();
    for (Long post : posts)
      for (int n = 0; n < count; n++) {
        interactions.add(new Object[] {accounts.get(n), post, "LIKE"});
        interactions.add(new Object[] {accounts.get(n), post, "FAVORITE"});
        comments.add(new Object[] {post, accounts.get(n), "性能基线测试评论"});
      }
    db.batchUpdate(
        "INSERT IGNORE INTO post_interaction(user_id,post_id,action_type) VALUES(?,?,?)",
        interactions);
    for (Long post : posts) db.update("DELETE FROM post_comment WHERE post_id=?", post);
    db.batchUpdate("INSERT INTO post_comment(post_id,user_id,content) VALUES(?,?,?)", comments);
  }

  Long detail(int days, int items) {
    var draft = new TripDetailVO();
    draft.setTitle(marker + "_detail_" + days);
    draft.setDestination("成都");
    draft.setDays(new ArrayList<>());
    for (int n = 0; n < days; n++) {
      var day = new TripDayVO();
      day.setItems(new ArrayList<>());
      for (int k = 0; k < items; k++) {
        var item = new TripItem();
        item.setItemName("节点" + k);
        item.setItemType("SCENIC");
        item.setCostEstimate(BigDecimal.ZERO);
        day.getItems().add(item);
      }
      draft.getDays().add(day);
    }
    UserContext.setUserId(owner);
    try {
      return trips.create(draft).getId();
    } finally {
      UserContext.clear();
    }
  }

  void cleanup() {
    db.active = null;
    UserContext.clear();
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    if (accounts.isEmpty()) return;
    String slots = String.join(",", Collections.nCopies(accounts.size(), "?"));
    Object[] ids = accounts.toArray();
    // Every deletion is bounded to user IDs created by this run; never truncate shared test data.
    String plans = "SELECT id FROM trip_plan WHERE user_id IN (" + slots + ")";
    String posts = "SELECT id FROM square_post WHERE user_id IN (" + slots + ")";
    db.update("DELETE FROM post_comment WHERE post_id IN (" + posts + ")", ids);
    db.update("DELETE FROM post_interaction WHERE post_id IN (" + posts + ")", ids);
    db.update("DELETE FROM square_post WHERE user_id IN (" + slots + ")", ids);
    for (String table :
        List.of(
            "trip_item",
            "trip_day",
            "trip_member",
            "trip_revision",
            "trip_expense",
            "trip_packing_item",
            "trip_share",
            "trip_invite"))
      db.update("DELETE FROM " + table + " WHERE trip_id IN (" + plans + ")", ids);
    db.update("DELETE FROM trip_plan WHERE user_id IN (" + slots + ")", ids);
    db.update("DELETE FROM sys_user WHERE id IN (" + slots + ")", ids);
  }

  @Test
  void recordRealHttpAndDatabaseBaseline() throws Exception {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    int repetitions = Integer.getInteger("smarttrip.baseline.samples", 20);
    assertTrue(repetitions >= 5 && repetitions <= 100);
    int[] scales =
        Arrays.stream(System.getProperty("smarttrip.baseline.sizes", "100,1000,10000").split(","))
            .mapToInt(Integer::parseInt)
            .toArray();
    assertTrue(scales.length > 0);
    for (int n = 0; n < scales.length; n++)
      assertTrue(scales[n] >= 40 && scales[n] <= 10000 && (n == 0 || scales[n] > scales[n - 1]));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("schemaVersion", 1);
    report.put("capturedAtUtc", Instant.now().toString());
    report.put("productionRevision", System.getProperty("smarttrip.baseline.revision", "unknown"));
    report.put(
        "environment",
        Map.of(
            "database",
            "smart_trip_test",
            "mysqlVersion",
            db.queryForObject("SELECT VERSION()", String.class),
            "javaVersion",
            System.getProperty("java.version"),
            "os",
            System.getProperty("os.name"),
            "architecture",
            System.getProperty("os.arch"),
            "processors",
            Runtime.getRuntime().availableProcessors(),
            "maxHeapBytes",
            Runtime.getRuntime().maxMemory(),
            "transport",
            "real loopback HTTP/1.1"));
    report.put(
        "limitations",
        List.of(
            "Local baseline, not a production SLA or a before/after optimization claim",
            "HTTP includes JWT, serialization and loopback transport; JDBC timings exclude MyBatis"
                + " authentication lookups",
            "First execution is not a guaranteed cold MySQL buffer pool; no global DB caches or"
                + " settings were changed",
            "Sampling is sequential; no browser rendering or high-concurrency load is measured"));
    report.put(
        "backgroundVisiblePosts",
        db.queryForObject(
            "SELECT COUNT(*) FROM square_post p JOIN sys_user u ON u.id=p.user_id WHERE"
                + " p.is_deleted=0 AND u.is_deleted=0 AND u.status=1",
            Integer.class));
    try {
      owner = account(0);
      other = account(1);
      viewer = account(2);
      for (int scale : scales) {
        seedTo(scale);
        measure("mine_owned_only", scale, "/workbench", other, scale / 2, 1, repetitions);
        measure("mine_shared_only", scale, "/workbench", viewer, scale / 2, 1, repetitions);
        measure("mine_owned_and_shared", scale, "/workbench", owner, scale, 1, repetitions);
        measure("feed_no_keyword", scale, feed(""), owner, 20, 1, repetitions);
        measure("feed_broad_match", scale, feed(marker), owner, 20, 1, repetitions);
        measure("feed_single_match", scale, feed(marker + "_000001"), owner, 1, 1, repetitions);
        measure("feed_no_match", scale, feed(marker + "_none"), owner, 0, 1, repetitions);
        var page = request(feed(marker), owner);
        assertTrue(page.more());
        assertNotNull(page.cursor());
        measure(
            "feed_next_page",
            scale,
            feed(marker) + "&cursor=" + URLEncoder.encode(page.cursor(), StandardCharsets.UTF_8),
            owner,
            20,
            1,
            repetitions);
      }
      List<Long> latest =
          db.queryForList(
              "SELECT id FROM square_post WHERE user_id IN (?,?) ORDER BY update_time DESC,id DESC"
                  + " LIMIT 21",
              Long.class,
              owner,
              other);
      for (int density : List.of(10, 100)) {
        interactions(density, latest);
        measure("feed_interactions_" + density, seeded, feed(marker), owner, 20, 1, repetitions);
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api" + feed(marker)))
                    .header("Authorization", "Bearer " + tokens.get(owner))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(
            density,
            json.readTree(response.body())
                .path("data")
                .path("items")
                .get(0)
                .path("likeCount")
                .asInt());
      }
      for (int[] shape : List.of(new int[] {1, 1}, new int[] {10, 40})) {
        Long id = detail(shape[0], shape[1]);
        var result =
            measure(
                "detail_" + shape[0] + "days_" + shape[1] + "nodes",
                seeded,
                "/workbench/" + id,
                owner,
                0,
                3,
                repetitions);
        result.put("itineraryDays", shape[0]);
        result.put("itineraryNodes", shape[0] * shape[1]);
      }
      report.put("cases", cases);
      Path output =
          Path.of(
                  System.getProperty(
                      "smarttrip.baseline.output", "target/performance-baseline.json"))
              .toAbsolutePath();
      Files.createDirectories(output.getParent());
      json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
      System.out.println("PERFORMANCE_BASELINE cases=" + cases.size() + " output=" + output);
    } finally {
      cleanup();
    }
  }
}
