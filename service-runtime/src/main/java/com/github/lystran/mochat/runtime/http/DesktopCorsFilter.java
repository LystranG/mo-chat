package com.github.lystran.mochat.runtime.http;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

/** Allows the Electron desktop renderer to call local service HTTP APIs. */
@Filter("/**")
public final class DesktopCorsFilter implements HttpServerFilter {
    private static final String ALLOWED_METHODS = "GET,POST,PUT,DELETE,OPTIONS";
    private static final String ALLOWED_HEADERS = "Content-Type,Accept,Origin";

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return Publishers.just(withCorsHeaders(HttpResponse.noContent()));
        }
        return Publishers.map(chain.proceed(request), response -> withCorsHeaders(response));
    }

    private MutableHttpResponse<?> withCorsHeaders(MutableHttpResponse<?> response) {
        return response
            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ALLOWED_METHODS)
            .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOWED_HEADERS)
            .header(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }
}
