package com.photoapp.feign.harness;

/**
 * The JSON a healthy downstream service returns, written by hand rather than serialised from the
 * DTOs.
 *
 * <p>That is deliberate. Serialising {@code AccountDTO} to produce the stub and then deserialising
 * it back would assert only that Jackson is symmetric with itself — the test would still pass if
 * the wire contract changed on both sides at once. Literal JSON pins the shape the other service
 * actually emits, so a renamed field breaks the test instead of quietly agreeing with itself.
 */
public final class DownstreamBodies {

    private DownstreamBodies() {
    }

    /** A {@code User} entity as users-service serialises it. */
    public static final String USER = """
            {
              "id": 42,
              "version": 3,
              "createdAt": "2026-01-02T03:04:05",
              "updatedAt": "2026-01-02T03:04:05",
              "firstName": "Ada",
              "lastName": "Lovelace",
              "username": "ada",
              "email": "ada@example.com",
              "passwordHash": "$2a$10$hashed",
              "activeUser": true,
              "roles": []
            }""";

    public static final String USER_DTO = """
            {
              "id": 42,
              "firstName": "Ada",
              "lastName": "Lovelace",
              "username": "ada",
              "email": "ada@example.com",
              "activeUser": true,
              "roles": [],
              "version": 3,
              "createdAt": "2026-01-02T03:04:05",
              "updatedAt": "2026-01-02T03:04:05"
            }""";

    public static final String ACCOUNT_DTO = """
            {
              "id": 7,
              "userId": 42,
              "accountName": "ada-main",
              "accountTypeDTO": "PREMIUM",
              "activeAccount": true,
              "version": 1,
              "createdAt": "2026-01-02T03:04:05",
              "updatedAt": "2026-01-02T03:04:05"
            }""";

    public static final String ALBUM_DTO = """
            {
              "id": 11,
              "accountId": 7,
              "title": "Analytical Engine",
              "description": "notes and diagrams",
              "activeAlbum": true,
              "version": 1,
              "createdAt": "2026-01-02T03:04:05",
              "updatedAt": "2026-01-02T03:04:05"
            }""";

    public static final String PHOTO_DTO = """
            {
              "id": 99,
              "albumId": 11,
              "fileName": "note-g.png",
              "fileUrl": "https://example.invalid/note-g.png",
              "activePhoto": true,
              "version": 1,
              "createdAt": "2026-01-02T03:04:05",
              "updatedAt": "2026-01-02T03:04:05"
            }""";

    /**
     * A {@code PagedResponseDTO} on the wire, as every paginated endpoint returns it since
     * 2026-08-08.
     *
     * <p>Five flat fields. This replaced a hand-written {@code PageImpl} body — sixteen fields
     * across three nesting levels, including a {@code sort} object inside a {@code pageable}
     * object — which is the shape Spring serialises a raw {@code Page} to and warns about. That
     * the fixture shrank this much is the clearest measure of what the change bought.
     */
    public static String pagedResponseOf(String contentJson) {
        return """
                {
                  "totalElements": 1,
                  "totalPages": 1,
                  "pageNumber": 0,
                  "pageSize": 20,
                  "content": [%s]
                }""".formatted(contentJson);
    }

    /** An empty page, for the no-results path. */
    public static String emptyPagedResponse() {
        return """
                {
                  "totalElements": 0,
                  "totalPages": 0,
                  "pageNumber": 0,
                  "pageSize": 20,
                  "content": []
                }""";
    }
}
