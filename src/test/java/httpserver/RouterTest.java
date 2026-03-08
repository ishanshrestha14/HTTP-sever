package httpserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router();
        router.get("/hello", ctx -> "hello");
        router.get("/users/:id", ctx -> "user " + ctx.pathParams.get("id"));
        router.get("/users/:id/posts/:postId", ctx -> "post");
        router.post("/submit", ctx -> "submitted " + ctx.body);
    }

    @Test
    void matchesExactRoute() {
        Router.MatchResult m = router.match("GET", "/hello", "");
        assertNotNull(m);
        assertEquals("hello", m.handler.handle(m.context));
    }

    @Test
    void extractsSinglePathParam() {
        Router.MatchResult m = router.match("GET", "/users/42", "");
        assertNotNull(m);
        assertEquals(Map.of("id", "42"), m.context.pathParams);
        assertEquals("user 42", m.handler.handle(m.context));
    }

    @Test
    void extractsMultiplePathParams() {
        Router.MatchResult m = router.match("GET", "/users/7/posts/99", "");
        assertNotNull(m);
        assertEquals(Map.of("id", "7", "postId", "99"), m.context.pathParams);
    }

    @Test
    void toleratesTrailingSlash() {
        Router.MatchResult m = router.match("GET", "/users/42/", "");
        assertNotNull(m);
        assertEquals("42", m.context.pathParams.get("id"));
    }

    @Test
    void pathParamsAndQueryParamsAreSeparate() {
        Router.MatchResult m = router.match("GET", "/users/42?tab=posts&q=hello%20world", "");
        assertNotNull(m);
        assertEquals(Map.of("id", "42"), m.context.pathParams);
        assertEquals("posts", m.context.queryParams.get("tab"));
        assertEquals("hello world", m.context.queryParams.get("q"));
    }

    @Test
    void segmentCountMustMatch() {
        assertNull(router.match("GET", "/users", ""));
        assertNull(router.match("GET", "/users/42/posts", ""));
        assertNull(router.match("GET", "/users/42/posts/1/extra", ""));
    }

    @Test
    void methodMustMatch() {
        assertNull(router.match("POST", "/users/42", ""));
        assertNull(router.match("GET", "/submit", ""));

        Router.MatchResult m = router.match("post", "/submit", "a=1");
        assertNotNull(m, "method comparison is case-insensitive");
        assertEquals("submitted a=1", m.handler.handle(m.context));
    }

    @Test
    void unknownPathReturnsNull() {
        assertNull(router.match("GET", "/nope", ""));
    }
}
