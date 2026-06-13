package com.github.lystran.mochat.call.dto;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析和生成 LiveKit 房间名。 */
public record CallRoomName(
    CallRoomType type,
    String value,
    Long firstUserId,
    Long secondUserId,
    Long groupId,
    String callId
) {
    private static final Pattern PRIVATE_PATTERN = Pattern.compile("^call-private-(\\d+)-(\\d+)-([a-zA-Z0-9_-]+)$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("^call-group-(\\d+)-([a-zA-Z0-9_-]+)$");

    public static String privateRoomName(long fromUserId, long toUserId, String callId) {
        requirePositive(fromUserId, "fromUserId");
        requirePositive(toUserId, "toUserId");
        if (fromUserId == toUserId) {
            throw new IllegalArgumentException("private call requires two different users");
        }
        requireCallId(callId);
        long first = Math.min(fromUserId, toUserId);
        long second = Math.max(fromUserId, toUserId);
        return "call-private-" + first + "-" + second + "-" + callId;
    }

    public static String groupRoomName(long groupId, String callId) {
        requirePositive(groupId, "groupId");
        requireCallId(callId);
        return "call-group-" + groupId + "-" + callId;
    }

    public static CallRoomName parse(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            throw new IllegalArgumentException("roomName is required");
        }
        String normalized = roomName.trim();
        Matcher privateMatcher = PRIVATE_PATTERN.matcher(normalized);
        if (privateMatcher.matches()) {
            long first = parsePositiveLong(privateMatcher.group(1), "firstUserId");
            long second = parsePositiveLong(privateMatcher.group(2), "secondUserId");
            if (first >= second) {
                throw new IllegalArgumentException("private room user ids must be ordered");
            }
            return new CallRoomName(CallRoomType.PRIVATE, normalized, first, second, null, privateMatcher.group(3));
        }

        Matcher groupMatcher = GROUP_PATTERN.matcher(normalized);
        if (groupMatcher.matches()) {
            long groupId = parsePositiveLong(groupMatcher.group(1), "groupId");
            return new CallRoomName(CallRoomType.GROUP, normalized, null, null, groupId, groupMatcher.group(2));
        }

        throw new IllegalArgumentException("invalid call room name");
    }

    public boolean allowsPrivateUser(long userId) {
        return type == CallRoomType.PRIVATE && (userId == firstUserId || userId == secondUserId);
    }

    public long privatePeerOf(long userId) {
        if (!allowsPrivateUser(userId)) {
            throw new IllegalArgumentException("user is not a participant of this private call");
        }
        return userId == firstUserId ? secondUserId : firstUserId;
    }

    public static void requirePositive(long value, String fieldName) {
        if (value <= 0L) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static long parsePositiveLong(String value, String fieldName) {
        try {
            long parsed = Long.parseLong(value);
            requirePositive(parsed, fieldName);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be a positive decimal number", exception);
        }
    }

    private static void requireCallId(String callId) {
        if (callId == null || callId.isBlank() || !callId.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("callId must only contain letters, digits, '_' or '-'");
        }
    }
}
