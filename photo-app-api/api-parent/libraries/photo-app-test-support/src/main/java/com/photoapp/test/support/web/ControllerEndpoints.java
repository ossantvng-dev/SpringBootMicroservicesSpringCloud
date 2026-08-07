package com.photoapp.test.support.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/*
    Reflection over a controller, so an authorization suite can prove its table describes the
    WHOLE controller and not just the endpoints someone remembered.

    Phase 2 shipped the same guard for GlobalExceptionHandler and it earned its place on the
    first run, catching a handler that had been left out of the resolution table. The failure
    mode it prevents is the quiet one: a new protected endpoint ships with no authorization
    test at all, and the suite stays green because nothing knows to look for it.
 */
public final class ControllerEndpoints {

    private ControllerEndpoints() {
    }

    /**
     * Names of every method on {@code controller} carrying {@code @PreAuthorize}.
     *
     * <p>Method NAMES rather than {@link Method} objects: a suite's table is written by hand and
     * naming a method is the only part of it a human can reasonably keep in sync. Overloaded
     * handler methods would collapse into one name here - there are none in this codebase, and
     * {@link #handlerMethodsMissingAuthorization} would flag the ambiguity if one appeared.
     */
    public static Set<String> preAuthorizeAnnotatedMethods(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PreAuthorize.class))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    /**
     * Names of every request-mapped method on {@code controller} that has NO {@code @PreAuthorize}.
     *
     * <p>These are not necessarily defects - {@code POST /users} and the three {@code /auth}
     * endpoints are deliberately anonymous, and the filter chain permits them explicitly. But an
     * endpoint landing here by accident is a hole, so each suite asserts this set matches the
     * public endpoints it knows about by name, making a new unprotected endpoint a build failure
     * rather than a discovery.
     */
    public static Set<String> handlerMethodsMissingAuthorization(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(RequestMapping.class)
                        || Arrays.stream(m.getAnnotations())
                        .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)))
                .filter(m -> !m.isAnnotationPresent(PreAuthorize.class))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }
}
