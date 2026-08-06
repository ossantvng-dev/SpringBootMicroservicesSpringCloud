package com.photoapp.commons.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.photoapp.commons.support.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives real HTTP requests through a real DispatcherServlet so the framework, not the test,
 * decides which exception a malformed request produces.
 *
 * <p>{@link GlobalExceptionHandlerTest} constructs each exception by hand, which proves what a
 * handler does with it but assumes the request shape produces that exception in the first
 * place. That assumption is exactly what was wrong before 2026-08-06: {@code GET /users/abc}
 * was believed to be handled and in fact produced a 500. Here the assertion starts from the
 * request.
 *
 * <p>Standalone setup, not {@code @WebMvcTest}: the slice annotation lives in
 * {@code photo-app-test-support}, which this module cannot depend on. A probe controller
 * stands in for the real ones because {@code photo-app-commons} has none of its own.
 *
 * <p>{@code NoResourceFoundException} is absent here deliberately - it is thrown by the static
 * resource handler, which standalone setup does not register. It is covered by direct
 * invocation, by {@link GlobalExceptionHandlerResolutionTest}, and by the curl verification
 * recorded in {@code docs/plans/testing-plan.md}.
 */
class GlobalExceptionHandlerWebMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ------------------------------------------------- pre-existing handlers

    @Test
    void applicationExceptionKeepsItsStatusEndToEnd() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.httpStatus").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("User not found"))
                .andExpect(jsonPath("$.path").value("/probe/not-found"))
                .andExpect(jsonPath("$.timeStamp").exists());
    }

    /*
        The Step 8 regression, end to end: an AuthorizationDeniedException raised inside the
        DispatcherServlet must come back as 403, not 500.
     */
    @Test
    @DisplayName("@PreAuthorize-shaped denial returns 403, not 500")
    void authorizationDeniedReturns403() throws Exception {
        try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
            mockMvc.perform(get("/probe/denied"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.httpStatus").value(403))
                    .andExpect(jsonPath("$.message").value("Forbidden"));

            assertOnlyWarnedWith(logs, "ACCESS_DENIED");
        }
    }

    @Test
    void genuineFaultsStillReturn500() throws Exception {
        try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
            mockMvc.perform(get("/probe/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("Unexpected error occurred"));

            ILoggingEvent event = logs.onlyEvent();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).startsWith("UNHANDLED_EXCEPTION");
        }
    }

    // ------------------------------------------- the three 2026-08-06 cases

    /* GET /users/abc - roughly 20 {id} endpoints across the five services had this shape. */
    @Test
    @DisplayName("unparseable path variable returns 400, not 500")
    void unparseablePathVariableReturns400() throws Exception {
        try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
            mockMvc.perform(get("/probe/abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.httpStatus").value(400))
                    .andExpect(jsonPath("$.error").value("Bad Request"))
                    .andExpect(jsonPath("$.message").value("Parameter 'id' must be of type Long"))
                    .andExpect(jsonPath("$.path").value("/probe/abc"));

            assertOnlyWarnedWith(logs, "TYPE_MISMATCH");
        }
    }

    /* PATCH /users/1/activate?activate=maybe */
    @Test
    @DisplayName("un-convertible query parameter returns 400, not 500")
    void unconvertibleQueryParameterReturns400() throws Exception {
        try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
            mockMvc.perform(patch("/probe/1/activate").param("activate", "maybe"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Parameter 'activate' must be of type boolean"));

            assertOnlyWarnedWith(logs, "TYPE_MISMATCH");
        }
    }

    @Test
    @DisplayName("malformed request body returns 400, not 500")
    void malformedBodyReturns400() throws Exception {
        try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
            mockMvc.perform(post("/probe")
                            .contentType("application/json")
                            .content("{\"broken"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Malformed request body"));

            assertOnlyWarnedWith(logs, "MALFORMED_REQUEST_BODY");
        }
    }

    @Test
    @DisplayName("wrong HTTP method returns 405, not 500")
    void wrongMethodReturns405() throws Exception {
        try (LogCapture logs = LogCapture.on(GlobalExceptionHandler.class)) {
            mockMvc.perform(delete("/probe"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.httpStatus").value(405))
                    .andExpect(jsonPath("$.message")
                            .value("Method DELETE is not supported for this endpoint"));

            assertOnlyWarnedWith(logs, "METHOD_NOT_ALLOWED");
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * The half of the fix that is invisible in the response body: these are client input
     * errors, so nothing may be logged at ERROR and nothing may carry the UNHANDLED_EXCEPTION
     * marker. Before the fix each of these produced exactly that, with a full stack trace.
     */
    private static void assertOnlyWarnedWith(LogCapture logs, String expectedMarker) {
        List<ILoggingEvent> events = logs.events();

        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event ->
                assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(events).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("UNHANDLED_EXCEPTION"));
        assertThat(events).anySatisfy(event ->
                assertThat(event.getFormattedMessage()).startsWith(expectedMarker));
    }

    // ---------------------------------------------------------- the probe

    /** Mirrors the real controller signatures that produced 500s: {@code Long} path variables,
     *  a {@code boolean} query parameter, and a JSON request body. */
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/{id}")
        String findById(@PathVariable("id") Long id) {
            return "id=" + id;
        }

        @PatchMapping("/{id}/activate")
        String activate(@PathVariable("id") Long id, @RequestParam("activate") boolean activate) {
            return id + "=" + activate;
        }

        @PostMapping
        String create(@RequestBody Payload payload) {
            return payload.email;
        }

        @DeleteMapping("/{id}")
        String delete(@PathVariable("id") Long id) {
            return "deleted " + id;
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new ApplicationException("User not found", HttpStatus.NOT_FOUND);
        }

        @GetMapping("/denied")
        String denied() {
            throw new AuthorizationDeniedException("Access Denied");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("something genuinely broke");
        }
    }

    static class Payload {
        public String email;
    }
}
