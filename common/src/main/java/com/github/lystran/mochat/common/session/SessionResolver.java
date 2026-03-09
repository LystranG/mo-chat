package com.github.lystran.mochat.common.session;

import java.util.Optional;

public interface SessionResolver {
    Optional<Long> resolveUserId(String sessionId);
}
