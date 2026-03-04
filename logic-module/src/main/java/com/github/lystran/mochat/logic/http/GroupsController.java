package com.github.lystran.mochat.logic.http;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.List;

@Controller("/groups")
public final class GroupsController {
    @Get
    public GroupsScaffoldResponse listGroups() {
        return new GroupsScaffoldResponse(List.of(), "group management logic is implemented in a later task");
    }

    public record GroupsScaffoldResponse(List<String> groups, String message) {
    }
}
