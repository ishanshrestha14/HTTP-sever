package httpserver;

import java.util.*;
import java.util.concurrent.*;

/**
 * In-memory session store.
 *
 * Responsibilities:
 *   1. Create sessions and generate secure random session IDs
 *   2. Store arbitrary key-value data per session
 *   3. Look up sessions by ID
 *   4. Destroy sessions on logout
 *   5. Parse Cookie headers from incoming requests
 *   6. Build Set-Cookie headers for responses
 */
public class SessionStore {

    // -------------------------------------------------------------------------
    // Session data structure
    // -------------------------------------------------------------------------

    /**
     * One session = a map of key → value data for a single logged-in user.
     * Example: { "username" → "ishan", "role" → "admin" }
     */
    public static class Session {
        private final String id;
        private final Map<String, String> data = new ConcurrentHashMap<>();
        private final long createdAt = System.currentTimeMillis();

        Session(String id) {
            this.id = id;
        }

        public String getId()                    { return id; }
        public void set(String key, String val)  { data.put(key, val); }
        public String get(String key)            { return data.get(key); }
        public boolean has(String key)           { return data.containsKey(key); }

        /** How old is this session in minutes? */
        public long ageMinutes() {
            return (System.currentTimeMillis() - createdAt) / 60000;
        }
    }

    // -------------------------------------------------------------------------
    // The store itself
    // -------------------------------------------------------------------------

    // ConcurrentHashMap — multiple threads (one per connection) read/write this safely
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    // How long until a session expires (30 minutes)
    private static final long SESSION_TIMEOUT_MINUTES = 30;

    // -------------------------------------------------------------------------
    // Creating and destroying sessions
    // -------------------------------------------------------------------------

    /**
     * Create a new session and return it.
     * Call this after verifying login credentials.
     */
    public Session create() {
        String id = generateSessionId();
        Session session = new Session(id);
        sessions.put(id, session);
        System.out.println("Session created: " + id);
        return session;
    }

    /**
     * Look up a session by ID.
     *
     * Returns null if:
     *   - ID doesn't exist (never logged in, or already logged out)
     *   - Session has expired
     */
    public Session get(String sessionId) {
        if (sessionId == null) return null;

        Session session = sessions.get(sessionId);
        if (session == null) return null;

        // Expire old sessions
        if (session.ageMinutes() > SESSION_TIMEOUT_MINUTES) {
            sessions.remove(sessionId);
            System.out.println("Session expired: " + sessionId);
            return null;
        }

        return session;
    }

    /**
     * Destroy a session (logout).
     * The session ID becomes invalid immediately.
     */
    public void destroy(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
            System.out.println("Session destroyed: " + sessionId);
        }
    }

    // -------------------------------------------------------------------------
    // Cookie header parsing — reads the Cookie header from a request
    // -------------------------------------------------------------------------

    /**
     * Parse a raw Cookie header string into a map of name → value.
     *
     * Raw header:  "sessionId=abc123; theme=dark; lang=en"
     * Result:      { "sessionId" → "abc123", "theme" → "dark", "lang" → "en" }
     *
     * Cookies are separated by "; " (semicolon + space).
     * Each cookie is "name=value".
     */
    public static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) return cookies;

        String[] pairs = cookieHeader.split(";\\s*"); // split on "; "
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String name  = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    /**
     * Extract just the sessionId cookie value from a cookie header.
     * Convenience method — this is the most common thing you need.
     */
    public static String extractSessionId(String cookieHeader) {
        return parseCookies(cookieHeader).get("sessionId");
    }

    // -------------------------------------------------------------------------
    // Building Set-Cookie headers — sends a cookie to the browser
    // -------------------------------------------------------------------------

    /**
     * Build a "Set-Cookie" header value that creates a session cookie.
     *
     * Flags explained:
     *   HttpOnly  — JavaScript cannot read this cookie (blocks XSS theft)
     *   SameSite=Strict — cookie not sent on cross-site requests (blocks CSRF)
     *   Path=/    — cookie sent for all paths on this domain
     *
     * We deliberately do NOT set "Secure" here because we're on plain HTTP.
     * In production (HTTPS), you'd add "; Secure" to prevent sending over HTTP.
     */
    public static String makeSetCookieHeader(String sessionId) {
        return "sessionId=" + sessionId
                + "; HttpOnly"          // JS can't steal it
                + "; SameSite=Strict"   // CSRF protection
                + "; Path=/";           // valid for entire site
    }

    /**
     * Build a "Set-Cookie" header that DELETES the cookie in the browser.
     *
     * Browsers delete a cookie when they receive it with Max-Age=0 (or a past Expires).
     * We also clear the value to "deleted" for clarity.
     */
    public static String makeClearCookieHeader() {
        return "sessionId=deleted"
                + "; HttpOnly"
                + "; SameSite=Strict"
                + "; Path=/"
                + "; Max-Age=0";  // ← browser deletes it immediately
    }

    // -------------------------------------------------------------------------
    // Secure session ID generation
    // -------------------------------------------------------------------------

    /**
     * Generate a cryptographically random session ID.
     *
     * Why SecureRandom and not Random?
     *   Random is predictable — if you know the seed, you can guess future values.
     *   SecureRandom uses OS entropy (/dev/urandom on Linux) — unpredictable.
     *
     * 32 bytes = 256 bits of randomness.
     * Probability of collision: 1 in 2^256 — effectively impossible.
     */
    private String generateSessionId() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        // Convert bytes to hex string: each byte → 2 hex chars → 64 char ID
        // e.g. byte 255 → "ff",  byte 0 → "00"
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}