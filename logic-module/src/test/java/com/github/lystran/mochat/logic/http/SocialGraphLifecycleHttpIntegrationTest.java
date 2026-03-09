package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.common.id.IdGenerator;
import com.github.lystran.mochat.logic.chat.InboundMessageConsumer;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialGraphLifecycleHttpIntegrationTest {
    static final String SPEC_NAME = "social-graph-lifecycle-http";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @BeforeEach
    void setUp() throws SQLException {
        Flyway.configure()
            .cleanDisabled(false)
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .clean();

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        seedUser(11L, "alice", (byte) 1);
        seedUser(22L, "bob", (byte) 2);
    }

    @AfterAll
    static void tearDownContainer() {
        POSTGRES.stop();
    }

    @Test
    void friendRequestLifecycleAndBlockUnblockFlow() throws SQLException {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, serverProperties());
             HttpClient httpClient = HttpClient.create(server.getURL())) {
            seedSession(server, "alice-session", 11L);
            seedSession(server, "bob-session", 22L);

            HttpResponse<Map> sendResponse = exchange(
                httpClient,
                HttpRequest.POST("/friends/requests", Map.of(
                    "sessionId", "alice-session",
                    "toUserId", 22L,
                    "sign", "opaque-base64-sign"
                ))
            );
            assertEquals(HttpStatus.OK, sendResponse.getStatus());
            Map<?, ?> sendBody = sendResponse.body();
            assertNotNull(sendBody);
            Map<?, ?> requestPayload = asMap(sendBody.get("request"));
            long requestId = longValue(requestPayload.get("requestId"));
            assertEquals("opaque-base64-sign", requestPayload.get("sign"));
            assertEquals("pending", requestPayload.get("status"));

            Map<?, ?> sentBody = responseBody(exchange(
                httpClient,
                HttpRequest.GET("/friends/requests/sent?sessionId=alice-session")
            ));
            List<?> sentRequests = asList(sentBody.get("requests"));
            assertEquals(1, sentRequests.size());
            assertEquals(requestId, longValue(asMap(sentRequests.get(0)).get("requestId")));
            assertEquals("opaque-base64-sign", asMap(sentRequests.get(0)).get("sign"));
            assertEquals("pending", asMap(sentRequests.get(0)).get("status"));

            Map<?, ?> receivedBody = responseBody(exchange(
                httpClient,
                HttpRequest.GET("/friends/requests/received?sessionId=bob-session")
            ));
            List<?> receivedRequests = asList(receivedBody.get("requests"));
            assertEquals(1, receivedRequests.size());
            assertEquals(requestId, longValue(asMap(receivedRequests.get(0)).get("requestId")));
            assertEquals("opaque-base64-sign", asMap(receivedRequests.get(0)).get("sign"));
            assertEquals("pending", asMap(receivedRequests.get(0)).get("status"));

            Map<?, ?> handledBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/friends/requests/" + requestId + "/handle", Map.of(
                    "sessionId", "bob-session",
                    "action", "accept"
                ))
            ));
            assertEquals("accepted", asMap(handledBody.get("request")).get("status"));

            assertFriendListContainsSingleFriend(httpClient, "alice-session", 22L, "bob");
            assertFriendListContainsSingleFriend(httpClient, "bob-session", 11L, "alice");

            Map<?, ?> blockedBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/friends/11/block?sessionId=bob-session", Map.of())
            ));
            assertEquals("blocked", blockedBody.get("status"));
            assertFriendListDoesNotContainFriend(httpClient, "alice-session", 22L);
            assertFriendListDoesNotContainFriend(httpClient, "bob-session", 11L);

            Map<?, ?> unblockedBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/friends/11/unblock?sessionId=bob-session", Map.of())
            ));
            assertEquals("ok", unblockedBody.get("status"));
            assertFriendListContainsSingleFriend(httpClient, "alice-session", 22L, "bob");
            assertFriendListContainsSingleFriend(httpClient, "bob-session", 11L, "alice");
        }
    }

    @Test
    void rejectingFriendRequestDoesNotCreateFriendship() throws SQLException {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, serverProperties());
             HttpClient httpClient = HttpClient.create(server.getURL())) {
            seedSession(server, "alice-session", 11L);
            seedSession(server, "bob-session", 22L);

            long requestId = longValue(asMap(responseBody(exchange(
                httpClient,
                HttpRequest.POST("/friends/requests", Map.of(
                    "sessionId", "alice-session",
                    "toUserId", 22L,
                    "sign", "opaque-base64-sign"
                ))
            )).get("request")).get("requestId"));

            Map<?, ?> handledBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/friends/requests/" + requestId + "/handle", Map.of(
                    "sessionId", "bob-session",
                    "action", "reject"
                ))
            ));
            assertEquals("rejected", asMap(handledBody.get("request")).get("status"));
            assertTrue(friendshipStatusOptional(11L, 22L).isEmpty());
        }
    }

    @Test
    void groupJoinAndAdminLifecycleFlow() throws SQLException {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, serverProperties());
             HttpClient httpClient = HttpClient.create(server.getURL())) {
            seedSession(server, "alice-session", 11L);
            seedSession(server, "bob-session", 22L);

            Map<?, ?> createGroupBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups", Map.of(
                    "sessionId", "alice-session",
                    "name", "dev-group"
                ))
            ));
            Map<?, ?> groupPayload = asMap(createGroupBody.get("group"));
            long groupId = longValue(groupPayload.get("groupId"));
            assertEquals("dev-group", groupPayload.get("name"));
            assertEquals(11L, longValue(groupPayload.get("ownerUserId")));
            assertGroupListContainsSingleGroup(httpClient, "alice-session", groupId, "dev-group", 11L);

            Map<?, ?> sendJoinRequestBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups/" + groupId + "/join-requests", Map.of(
                    "sessionId", "bob-session",
                    "sign", "opaque-join-sign"
                ))
            ));
            Map<?, ?> joinRequestPayload = asMap(sendJoinRequestBody.get("request"));
            long requestId = longValue(joinRequestPayload.get("requestId"));
            assertEquals(groupId, longValue(joinRequestPayload.get("groupId")));
            assertEquals(22L, longValue(joinRequestPayload.get("fromUserId")));
            assertEquals("opaque-join-sign", joinRequestPayload.get("sign"));
            assertEquals("pending", joinRequestPayload.get("status"));

            Map<?, ?> joinRequestsBody = responseBody(exchange(
                httpClient,
                HttpRequest.GET("/groups/" + groupId + "/join-requests?sessionId=alice-session")
            ));
            List<?> joinRequests = asList(joinRequestsBody.get("requests"));
            assertEquals(1, joinRequests.size());
            assertEquals(requestId, longValue(asMap(joinRequests.get(0)).get("requestId")));
            assertEquals("opaque-join-sign", asMap(joinRequests.get(0)).get("sign"));
            assertEquals("pending", asMap(joinRequests.get(0)).get("status"));

            Map<?, ?> acceptJoinRequestBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups/" + groupId + "/join-requests/" + requestId + "/handle", Map.of(
                    "sessionId", "alice-session",
                    "action", "accept"
                ))
            ));
            Map<?, ?> handledJoinRequestPayload = asMap(acceptJoinRequestBody.get("request"));
            assertEquals("accepted", handledJoinRequestPayload.get("status"));
            assertEquals(11L, longValue(handledJoinRequestPayload.get("handledByUserId")));

            assertGroupListContainsSingleGroup(httpClient, "bob-session", groupId, "dev-group", 11L);

            Map<?, ?> kickBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups/" + groupId + "/members/22/kick?sessionId=alice-session", Map.of())
            ));
            assertEquals("kicked", kickBody.get("status"));
            assertEquals(22L, longValue(kickBody.get("userId")));
            assertGroupListDoesNotContainGroup(httpClient, "bob-session", groupId);

            Map<?, ?> dissolveBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups/" + groupId + "/dissolve?sessionId=alice-session", Map.of())
            ));
            assertEquals("dissolved", dissolveBody.get("status"));
            assertGroupListDoesNotContainGroup(httpClient, "alice-session", groupId);
        }
    }

    @Test
    void rejectingJoinRequestDoesNotCreateMembership() throws SQLException {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, serverProperties());
             HttpClient httpClient = HttpClient.create(server.getURL())) {
            seedSession(server, "alice-session", 11L);
            seedSession(server, "bob-session", 22L);

            long groupId = longValue(asMap(responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups", Map.of(
                    "sessionId", "alice-session",
                    "name", "ops-group"
                ))
            )).get("group")).get("groupId"));

            long requestId = longValue(asMap(responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups/" + groupId + "/join-requests", Map.of(
                    "sessionId", "bob-session",
                    "sign", "opaque-reject-sign"
                ))
            )).get("request")).get("requestId"));

            Map<?, ?> rejectJoinRequestBody = responseBody(exchange(
                httpClient,
                HttpRequest.POST("/groups/" + groupId + "/join-requests/" + requestId + "/handle", Map.of(
                    "sessionId", "alice-session",
                    "action", "reject"
                ))
            ));
            assertEquals("rejected", asMap(rejectJoinRequestBody.get("request")).get("status"));

            Map<?, ?> joinRequestsBody = responseBody(exchange(
                httpClient,
                HttpRequest.GET("/groups/" + groupId + "/join-requests?sessionId=alice-session")
            ));
            assertJoinRequestListEmpty(joinRequestsBody);
            assertGroupListDoesNotContainGroup(httpClient, "bob-session", groupId);
        }
    }

    private static Map<String, Object> serverProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spec.name", SPEC_NAME);
        properties.put("micronaut.server.port", -1);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Map> exchange(HttpClient httpClient, HttpRequest<?> request) {
        return httpClient.toBlocking().exchange(request, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> responseBody(HttpResponse<Map> response) {
        Map<?, ?> body = response.body();
        assertNotNull(body);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object value) {
        assertTrue(value instanceof Map<?, ?>);
        return (Map<?, ?>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<?> asList(Object value) {
        assertTrue(value instanceof List<?>);
        return (List<?>) value;
    }

    private static long longValue(Object value) {
        assertTrue(value instanceof Number);
        return ((Number) value).longValue();
    }

    private static void assertFriendListContainsSingleFriend(
        HttpClient httpClient,
        String sessionId,
        long expectedFriendUserId,
        String expectedFriendUsername
    ) {
        Map<?, ?> friendsBody = responseBody(exchange(
            httpClient,
            HttpRequest.GET("/friends?sessionId=" + sessionId)
        ));
        List<?> friends = asList(friendsBody.get("friends"));
        assertEquals(1, friends.size());
        assertEquals(expectedFriendUserId, longValue(asMap(friends.get(0)).get("userId")));
        assertEquals(expectedFriendUsername, asMap(friends.get(0)).get("username"));
    }

    private static void assertFriendListDoesNotContainFriend(HttpClient httpClient, String sessionId, long friendUserId) {
        Map<?, ?> friendsBody = responseBody(exchange(
            httpClient,
            HttpRequest.GET("/friends?sessionId=" + sessionId)
        ));
        Object friendsValue = friendsBody.get("friends");
        if (friendsValue == null) {
            return;
        }

        List<?> friends = asList(friendsValue);
        assertTrue(friends.stream().noneMatch(friend -> longValue(asMap(friend).get("userId")) == friendUserId));
    }

    private static void assertGroupListContainsSingleGroup(
        HttpClient httpClient,
        String sessionId,
        long expectedGroupId,
        String expectedGroupName,
        long expectedOwnerUserId
    ) {
        Map<?, ?> groupsBody = responseBody(exchange(
            httpClient,
            HttpRequest.GET("/groups?sessionId=" + sessionId)
        ));
        List<?> groups = asList(groupsBody.get("groups"));
        assertEquals(1, groups.size());
        assertEquals(expectedGroupId, longValue(asMap(groups.get(0)).get("groupId")));
        assertEquals(expectedGroupName, asMap(groups.get(0)).get("name"));
        assertEquals(expectedOwnerUserId, longValue(asMap(groups.get(0)).get("ownerUserId")));
    }

    private static void assertGroupListDoesNotContainGroup(HttpClient httpClient, String sessionId, long groupId) {
        Map<?, ?> groupsBody = responseBody(exchange(
            httpClient,
            HttpRequest.GET("/groups?sessionId=" + sessionId)
        ));
        Object groupsValue = groupsBody.get("groups");
        if (groupsValue == null) {
            return;
        }

        List<?> groups = asList(groupsValue);
        assertTrue(groups.stream().noneMatch(group -> longValue(asMap(group).get("groupId")) == groupId));
    }

    private static void assertJoinRequestListEmpty(Map<?, ?> joinRequestsBody) {
        Object requestsValue = joinRequestsBody.get("requests");
        if (requestsValue == null) {
            return;
        }

        assertTrue(asList(requestsValue).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static void seedSession(EmbeddedServer server, String sessionId, long userId) {
        RedisCommands<String, String> redisCommands = (RedisCommands<String, String>) server.getApplicationContext()
            .getBean(RedisCommands.class);
        redisCommands.set("mochat:session:" + sessionId, Long.toString(userId));
    }

    private static void seedUser(long userId, String username, byte publicKeyFill) throws SQLException {
        byte[] publicKey = new byte[32];
        java.util.Arrays.fill(publicKey, publicKeyFill);
        String sql = "INSERT INTO users (id, username, public_key) VALUES (?, ?, ?)";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, username);
            statement.setBytes(3, publicKey);
            statement.executeUpdate();
        }
    }

    private static java.util.Optional<String> friendshipStatusOptional(long userId, long friendUserId) throws SQLException {
        String sql = "SELECT status FROM user_friendships WHERE uid_1 = ? AND uid_2 = ?";
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Math.min(userId, friendUserId));
            statement.setLong(2, Math.max(userId, friendUserId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(resultSet.getString(1));
            }
        }
    }

    static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}

@Factory
@Requires(property = "spec.name", value = SocialGraphLifecycleHttpIntegrationTest.SPEC_NAME)
final class SocialGraphLifecycleHttpIntegrationTestFactory {
    @Singleton
    DataSource dataSource() {
        return SocialGraphLifecycleHttpIntegrationTest.dataSource();
    }

    @Singleton
    IdGenerator idGenerator() {
        AtomicLong ids = new AtomicLong(9_000L);
        return ids::getAndIncrement;
    }

    @Singleton
    @Replaces(InboundMessageConsumer.class)
    InboundMessageConsumer inboundMessageConsumer() {
        return Mockito.mock(InboundMessageConsumer.class);
    }

    @Singleton
    @SuppressWarnings("unchecked")
    RedisCommands<String, String> redisCommands() {
        Map<String, String> values = new ConcurrentHashMap<>();
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "set" -> {
                    values.put((String) args[0], (String) args[1]);
                    yield "OK";
                }
                case "get" -> values.get((String) args[0]);
                case "del" -> values.remove((String) args[0]) == null ? 0L : 1L;
                case "toString" -> "SocialGraphLifecycleHttpIntegrationTestRedisCommands";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                    "Unsupported RedisCommands method: " + method.getName()
                );
            }
        );
    }
}
