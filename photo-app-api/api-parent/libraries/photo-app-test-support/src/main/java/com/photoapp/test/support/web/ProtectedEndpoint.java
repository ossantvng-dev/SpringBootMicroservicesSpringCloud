package com.photoapp.test.support.web;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

/*
    One row of the Phase 3 authorization matrix: a single protected endpoint, the roles that may
    reach it, and everything needed to issue a syntactically valid request to it.

    Lives in test-support rather than in each service because all five suites assert the same
    three states against the same chain, and five copies of this record would drift. The
    handlerMethod field is what lets each suite prove its table is COMPLETE - see
    ControllerEndpoints#preAuthorizeAnnotatedMethods.
 */
public record ProtectedEndpoint(
        String handlerMethod,
        HttpMethod method,
        String path,
        Access access,
        @Nullable String jsonBody) {

    /**
     * Which roles the endpoint's own {@code @PreAuthorize} admits.
     *
     * <p>Deliberately only two values. Every {@code @PreAuthorize} in this codebase is either
     * {@code hasRole('ADMIN')} or {@code hasRole('ADMIN') or hasRole('USER')}; a third shape
     * appearing in production is exactly the kind of change that should force someone to open
     * this file rather than quietly reuse an approximate row.
     */
    public enum Access {
        /** Method security narrower than the path rule - a USER token gets a 403 here. */
        ADMIN_ONLY,
        /** Method security matching the path rule - both roles pass. */
        ADMIN_OR_USER
    }

    public static ProtectedEndpoint adminOnly(String handlerMethod, HttpMethod method, String path) {
        return new ProtectedEndpoint(handlerMethod, method, path, Access.ADMIN_ONLY, null);
    }

    public static ProtectedEndpoint adminOrUser(String handlerMethod, HttpMethod method, String path) {
        return new ProtectedEndpoint(handlerMethod, method, path, Access.ADMIN_OR_USER, null);
    }

    /** Attaches a request body, for the endpoints that take one. */
    public ProtectedEndpoint withBody(String json) {
        return new ProtectedEndpoint(handlerMethod, method, path, access, json);
    }

    /**
     * Roles that must be rejected by METHOD security with a 403 carrying the project's
     * {@code ApiErrorDTO} - the Step 8 shape. Empty for endpoints open to both roles, because
     * there no in-scope role is denied at the method level; those are covered instead by the
     * unknown-role case, which is denied one layer earlier in the filter chain.
     */
    public List<String> rolesDeniedByMethodSecurity() {
        return access == Access.ADMIN_ONLY ? List.of("ROLE_USER") : List.of();
    }

    /** Roles that must reach the controller. */
    public List<String> rolesAllowed() {
        return access == Access.ADMIN_ONLY ? List.of("ROLE_ADMIN") : List.of("ROLE_ADMIN", "ROLE_USER");
    }

    /**
     * A request builder for this endpoint, with a body and content type when the endpoint takes
     * one. No Authorization header - each test adds the header appropriate to the case it is
     * asserting, which is the whole point of the matrix.
     */
    public MockHttpServletRequestBuilder request() {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path);
        if (jsonBody != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        return builder;
    }

    /** Renders as "GET /users/1" in parameterized test names. */
    @Override
    public String toString() {
        return method.name() + " " + path;
    }
}
