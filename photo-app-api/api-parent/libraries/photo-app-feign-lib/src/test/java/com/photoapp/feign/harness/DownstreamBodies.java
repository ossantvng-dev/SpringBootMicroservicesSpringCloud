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

    /** A Spring Data {@code Page} on the wire, as the paged endpoints return it. */
    public static String pageOf(String contentJson) {
        return """
                {
                  "content": [%s],
                  "number": 0,
                  "size": 20,
                  "totalElements": 1,
                  "totalPages": 1,
                  "first": true,
                  "last": true,
                  "numberOfElements": 1,
                  "empty": false,
                  "sort": { "sorted": false, "unsorted": true, "empty": true },
                  "pageable": {
                    "pageNumber": 0,
                    "pageSize": 20,
                    "offset": 0,
                    "paged": true,
                    "unpaged": false,
                    "sort": { "sorted": false, "unsorted": true, "empty": true }
                  }
                }""".formatted(contentJson);
    }
}
