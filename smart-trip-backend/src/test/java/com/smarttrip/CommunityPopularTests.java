package com.smarttrip;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smarttrip.common.GlobalExceptionHandler;
import com.smarttrip.config.JwtAuthInterceptor;
import com.smarttrip.controller.CommunityController;
import com.smarttrip.service.CommunityService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "SMARTTRIP_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:mysql://localhost:3306/smart_trip_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
  "security.jwt-secret=test-only-jwt-secret-at-least-32-bytes",
  "ai.llm.enabled=false", "geo.enabled=false"
})
@Transactional
class CommunityPopularTests {
  @Autowired JdbcTemplate db;
  @Autowired CommunityService community;
  @Autowired JwtAuthInterceptor auth;
  String marker;
  long owner;
  int serial;
  LocalDateTime now;
  final List<Long> likers = new ArrayList<>();

  @BeforeEach
  void setup() {
    assertEquals("smart_trip_test", db.queryForObject("SELECT DATABASE()", String.class));
    marker = "hot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
    owner = account();
    now = db.queryForObject("SELECT NOW()", LocalDateTime.class);
  }

  long account() {
    String username = marker + "_u" + serial++;
    db.update("INSERT INTO sys_user(username,password,nickname) VALUES(?,'fixture',?)", username, username);
    return db.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
  }

  long post(long user, LocalDateTime created) {
    String title = marker + "_p" + serial++;
    db.update("INSERT INTO trip_plan(user_id,title,destination) VALUES(?,?,?)", user, title, marker);
    long trip = db.queryForObject("SELECT id FROM trip_plan WHERE title=?", Long.class, title);
    db.update("INSERT INTO square_post(trip_id,user_id,title,destination,snapshot_json,create_time,update_time) VALUES(?,?,?,?, '{}',?,?)",
        trip, user, title, marker, created, created);
    return db.queryForObject("SELECT id FROM square_post WHERE trip_id=?", Long.class, trip);
  }

  void likes(long post, int count) {
    while (likers.size() < count) likers.add(account());
    for (int i = 0; i < count; i++)
      db.update("INSERT INTO post_interaction(user_id,post_id,action_type) VALUES(?,?,'LIKE')", likers.get(i), post);
  }

  List<Long> popularIds() {
    return community.popular(marker, null, null).items().stream().map(p -> p.getId()).toList();
  }

  @Test
  void ranksAcrossAllPostsRatherThanOnlyTheLatestPage() {
    long winner = 0;
    for (int i = 0; i < 25; i++) winner = post(owner, now.minusMinutes(i));
    likes(winner, 9);
    assertEquals(20, community.feed(marker, null, null).items().size());
    long expectedWinner = winner;
    assertTrue(community.feed(marker, null, null).items().stream().noneMatch(p -> p.getId() == expectedWinner));
    var hot = community.popular(marker, null, null);
    assertEquals(4, hot.items().size());
    assertEquals(winner, hot.items().get(0).getId());
    assertEquals(9L, hot.items().get(0).getLikeCount());
    assertFalse(hot.hasMore());
    assertNull(hot.nextCursor());
  }

  @Test
  void usesActualLikeCountsThenFirstPublicationAndIdForTies() {
    long older = post(owner, now.minusDays(2));
    long newer = post(owner, now.minusDays(1));
    long sameTimeHigherId = post(owner, now.minusDays(1));
    long fewerLikes = post(owner, now.minusHours(1));
    long legacy = post(owner, now);
    likes(older, 5); likes(newer, 5); likes(sameTimeHigherId, 5); likes(fewerLikes, 2);
    db.update("UPDATE square_post SET like_count=999 WHERE id=?", legacy);
    db.update("INSERT INTO post_interaction(user_id,post_id,action_type) VALUES(?,?,'FAVORITE')", owner, legacy);
    assertEquals(List.of(sameTimeHigherId, newer, older, fewerLikes), popularIds());
    db.update("DELETE FROM post_interaction WHERE post_id=? AND action_type='LIKE'", sameTimeHigherId);
    assertEquals(newer, popularIds().get(0));
    assertEquals(0L, community.feed(marker, owner, null).items().stream().filter(p -> p.getId() == legacy).findFirst().orElseThrow().getLikeCount());
  }

  @Test
  void filtersFirstPublicationWindowAndInactiveOrHiddenAuthors() {
    long inside = post(owner, now.minusDays(30).plusHours(1));
    long outside = post(owner, now.minusDays(30).minusHours(1));
    long future = post(owner, now.plusDays(1));
    long hidden = post(owner, now.minusDays(1));
    long disabledUser = account(), deletedUser = account();
    long disabled = post(disabledUser, now.minusDays(1));
    long deleted = post(deletedUser, now.minusDays(1));
    for (long id : List.of(outside, future, hidden, disabled, deleted)) likes(id, 8);
    // Auto-updating update_time (including clone_count changes) must not rejuvenate an old post.
    db.update("UPDATE square_post SET clone_count=clone_count+1 WHERE id=?", outside);
    db.update("UPDATE square_post SET is_deleted=1 WHERE id=?", hidden);
    db.update("UPDATE sys_user SET status=0 WHERE id=?", disabledUser);
    db.update("UPDATE sys_user SET is_deleted=1 WHERE id=?", deletedUser);
    assertEquals(List.of(inside), popularIds());
    assertTrue(community.popular(marker, disabledUser, null).items().isEmpty());
    assertTrue(community.popular("no-match-" + marker, null, null).items().isEmpty());
  }

  @Test
  void returnsRealZeroAndFewerThanFourWithoutPadding() {
    long first = post(owner, now.minusHours(1));
    long second = post(owner, now);
    var hot = community.popular(marker, owner, null);
    assertEquals(List.of(second, first), popularIds());
    assertEquals(2, hot.items().size());
    assertTrue(hot.items().stream().allMatch(p -> p.getLikeCount() == 0));
    assertTrue(community.popular(marker, -1L, null).items().isEmpty());
  }

  @Test
  void preservesGuestAccessDefaultFeedAndRejectsUnsupportedSortingOrCursor() throws Exception {
    post(owner, now);
    var mvc = MockMvcBuilders.standaloneSetup(new CommunityController(community))
        .addInterceptors(auth).setControllerAdvice(new GlobalExceptionHandler()).build();
    mvc.perform(get("/api/community/posts").param("q", marker).param("sort", "popular"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
        .andExpect(jsonPath("$.data.hasMore").value(false));
    mvc.perform(get("/api/community/posts").param("q", marker))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
    mvc.perform(get("/api/community/posts").param("sort", "unknown"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/community/posts").param("sort", "popular").param("cursor", "old-cursor"))
        .andExpect(status().isBadRequest());
  }
}
