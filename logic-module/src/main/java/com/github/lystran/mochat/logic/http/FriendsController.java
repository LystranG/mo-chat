package com.github.lystran.mochat.logic.http;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.List;

@Controller("/friends")
public final class FriendsController {
    @Get
    public FriendsScaffoldResponse listFriends() {
        return new FriendsScaffoldResponse(List.of(), "social graph logic is implemented in a later task");
    }

    public record FriendsScaffoldResponse(List<String> friends, String message) {
    }
}
