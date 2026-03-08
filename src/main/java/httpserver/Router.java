package httpserver;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A mini HTTP router that supports:
 *  - Exact routes:      /hello
 *  - Param routes:      /users/:id
 *  - Multi-param:       /posts/:postId/comments/:commentId
 *  - Query strings:     /search?q=java&page=2
 */
public class Router {

    // -------------------------------------------------------------------------
    // Data structures
    // -------------------------------------------------------------------------

    /**
     * A registered route: an HTTP method + URL pattern + handler.
     * Example: method="GET", pattern="/users/:id", handler=...
     */
    private static class Route {
        String method;
        String pattern;
        RouteHandler handler;

        Route(String method, String pattern, RouteHandler handler) {
            this.method  = method.toUpperCase();
            this.pattern = pattern;
            this.handler = handler;
        }
    }

    /**
     * Everything a handler needs to know about the incoming request.
     *
     * pathParams  → values extracted from :placeholders  e.g. { "id": "42" }
     * queryParams → values from the query string          e.g. { "q": "java" }
     * body        → raw POST body string
     */
    public static class RequestContext {
        public final Map<String, String> pathParams;
        public final Map<String, String> queryParams;
        public final String body;

        RequestContext(Map<String, String> pathParams,
                       Map<String, String> queryParams,
                       String body) {
            this.pathParams  = Collections.unmodifiableMap(pathParams);
            this.queryParams = Collections.unmodifiableMap(queryParams);
            this.body        = body;
        }
    }

    /**
     * A handler is just a function: RequestContext → response body string.
     * Using a functional interface lets us pass lambdas when registering routes.
     */
    @FunctionalInterface
    public interface RouteHandler {
        String handle(RequestContext ctx);
    }

    /**
     * What the router returns after a successful match.
     */
    public static class MatchResult {
        public final RouteHandler handler;
        public final RequestContext context;

        MatchResult(RouteHandler handler, RequestContext context) {
            this.handler = handler;
            this.context = context;
        }
    }

    // -------------------------------------------------------------------------
    // Router internals
    // -------------------------------------------------------------------------

    private final List<Route> routes = new ArrayList<>();

    /** Register a GET route */
    public void get(String pattern, RouteHandler handler) {
        routes.add(new Route("GET", pattern, handler));
    }

    /** Register a POST route */
    public void post(String pattern, RouteHandler handler) {
        routes.add(new Route("POST", pattern, handler));
    }

    // -------------------------------------------------------------------------
    // Matching logic
    // -------------------------------------------------------------------------

    /**
     * Try to find a matching route for this method + rawPath.*
     * rawPath may include a query string: "/search?q=java"
     * Returns null if no route matches (caller should send 404).
     */
    public MatchResult match(String method, String rawPath, String body) {

        // Step 1: Split rawPath into the actual path and the query string
        //
        //   "/search?q=java&page=2"
        //         ↓
        //   cleanPath  = "/search"
        //   queryString = "q=java&page=2"
        //
        String cleanPath;
        String queryString = "";

        int questionMark = rawPath.indexOf('?');
        if (questionMark >= 0) {
            cleanPath   = rawPath.substring(0, questionMark);
            queryString = rawPath.substring(questionMark + 1);
        } else {
            cleanPath = rawPath;
        }

        // Step 2: Parse the query string into a map
        Map<String, String> queryParams = parseQueryString(queryString);

        // Step 3: Try each registered route in order
        for (Route route : routes) {

            // Method must match first (GET vs POST)
            if (!route.method.equalsIgnoreCase(method)) continue;

            // Try to match the URL pattern — this is where :params are extracted
            Map<String, String> pathParams = matchPattern(route.pattern, cleanPath);

            if (pathParams != null) {
                // We found a match!
                RequestContext ctx = new RequestContext(pathParams, queryParams, body);
                return new MatchResult(route.handler, ctx);
            }
        }

        return null; // no route matched → caller sends 404
    }

    // -------------------------------------------------------------------------
    // Pattern matching: does "/users/:id" match "/users/42"?
    // -------------------------------------------------------------------------

    /**
     * Compare a route pattern against an actual URL path, segment by segment.
     *
     * Pattern: /users/:id/posts/:postId
     * Path:    /users/42/posts/7
     *
     * Splits both into ["users", ":id", "posts", ":postId"]
     *                   ["users", "42",  "posts", "7"]
     *
     * Walks through each position:
     *   - "users" == "users"  → exact match, continue
     *   - ":id"  starts with ":"  → capture: id = "42"
     *   - "posts" == "posts"  → exact match, continue
     *   - ":postId" → capture: postId = "7"
     *
     * Returns a map of captured values, or null if the pattern doesn't match.
     */
    private Map<String, String> matchPattern(String pattern, String path) {
        String[] patternSegments = splitPath(pattern);
        String[] pathSegments    = splitPath(path);

        // Different number of segments → can't possibly match
        if (patternSegments.length != pathSegments.length) return null;

        Map<String, String> params = new HashMap<>();

        for (int i = 0; i < patternSegments.length; i++) {
            String patSeg  = patternSegments[i];
            String pathSeg = pathSegments[i];

            if (patSeg.startsWith(":")) {
                // This segment is a named parameter — capture it
                String paramName = patSeg.substring(1); // strip the leading ":"
                params.put(paramName, pathSeg);
            } else {
                // Literal segment — must match exactly
                if (!patSeg.equals(pathSeg)) return null;
            }
        }

        return params; // all segments matched
    }

    /**
     * Split "/users/42" → ["users", "42"]
     * Strips leading/trailing slashes before splitting.
     */
    private String[] splitPath(String path) {
        String trimmed = path.replaceAll("^/+|/+$", ""); // remove leading/trailing /
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("/");
    }

    // -------------------------------------------------------------------------
    // Query string parsing: "q=java&page=2" → { "q": "java", "page": "2" }
    // -------------------------------------------------------------------------

    /**
     * Parse a URL query string into key=value pairs.
     *
     * "q=java&page=2&flag"
     *   → { "q": "java", "page": "2", "flag": "" }
     *
     * In real browsers, values are URL-encoded (space → %20, etc.).
     * We do a simple decode here to handle the most common cases.
     */
    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new LinkedHashMap<>(); // preserves insertion order
        if (queryString == null || queryString.isEmpty()) return params;

        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key   = urlDecode(pair.substring(0, eq));
                String value = urlDecode(pair.substring(eq + 1));
                params.put(key, value);
            } else {
                // key with no value, e.g. "?flag"
                params.put(urlDecode(pair), "");
            }
        }
        return params;
    }

    /**
     * Minimal URL decoding:
     *   %20 → space
     *   +   → space  (common in form submissions)
     *   %2B → +
     */
    private String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s; // if decoding fails, return as-is
        }
    }
}