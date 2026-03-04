package com.github.lystran.mochat.logic.http;

import com.github.lystran.mochat.logic.chat.OfflineReplayService;
import com.github.lystran.mochat.logic.service.SessionService;
import com.github.lystran.mochat.logic.service.UserProfile;
import com.github.lystran.mochat.logic.service.UserService;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    @Test
    void successfulLoginTriggersOfflineReplay() {
        UserService userService = mock(UserService.class);
        SessionService sessionService = mock(SessionService.class);
        OfflineReplayService offlineReplayService = mock(OfflineReplayService.class);
        when(userService.loginOrRegister("alice", "public-key")).thenReturn(new UserProfile(42L, "alice", new byte[32]));
        when(sessionService.issueSession(42L)).thenReturn("session-42");

        var controller = new AuthController(userService, sessionService, offlineReplayService);
        var response = controller.login(new AuthController.LoginRequest("alice", "public-key"));

        assertEquals(HttpStatus.OK, response.getStatus());
        verify(offlineReplayService).replayOnLogin(42L);
    }

    @Test
    void loginStillReturnsSuccessWhenReplayThrowsAfterSessionIssuance() {
        UserService userService = mock(UserService.class);
        SessionService sessionService = mock(SessionService.class);
        OfflineReplayService offlineReplayService = mock(OfflineReplayService.class);
        when(userService.loginOrRegister("alice", "public-key")).thenReturn(new UserProfile(42L, "alice", new byte[32]));
        when(sessionService.issueSession(42L)).thenReturn("session-42");
        doThrow(new RuntimeException("replay failed")).when(offlineReplayService).replayOnLogin(42L);

        var controller = new AuthController(userService, sessionService, offlineReplayService);
        var response = controller.login(new AuthController.LoginRequest("alice", "public-key"));

        assertEquals(HttpStatus.OK, response.getStatus());
        verify(sessionService).issueSession(42L);
        verify(offlineReplayService).replayOnLogin(42L);
    }
}
