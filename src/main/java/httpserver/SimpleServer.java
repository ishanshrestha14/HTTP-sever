package httpserver;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class SimpleServer {
    // -------------------------------------------------------------------------
    // Shared state — one instance, shared across all threads
    // -------------------------------------------------------------------------

    // The session store is a singleton — all threads read/write the same map.
    // ConcurrentHashMap inside makes it thread-safe.
    private static final SessionStore sessionStore = new SessionStore();

    // Fake user database — in a real app this would be a database with hashed passwords.
    // NEVER store plain-text passwords in production.
    private static final Map<String, String> USERS = Map.of(
            "ishan",  "password123",
            "admin",  "admin"
    );
    // -------------------------------------------------------------------------
    // Route definitions — register all your routes here, at the top.
    // Adding a new route = one new line. No touching the server loop.
    // -------------------------------------------------------------------------
    private static final Router router = buildRouter();

    private static Router buildRouter() {
        Router r = new Router();

        // Basic routes
        r.get("/", ctx ->
                "<html><body style='font-family:sans-serif;padding:2rem;background:#0f172a;color:#e2e8f0'>" +
                        "<h1>🚀 My Java Server</h1>" +
                        "<p>" +
                        "<a href='/hello' style='color:#38bdf8'>Hello</a> | " +
                        "<a href='/stream/countdown' style='color:#38bdf8'>Stream</a> | " +
                        "<a href='/login' style='color:#4ade80'>Login</a> | " +
                        "<a href='/dashboard' style='color:#fbbf24'>Dashboard</a>" +
                        "</p></body></html>"
        );

        r.get("/hello", ctx ->
                "<h1>Hello Ezzzyyy 🔥</h1>"
        );

        // POST route — reads the submitted body
        r.post("/submit", ctx ->
                "<h1>Received!</h1><p>" + ctx.body + "</p>"
        );

        // ------------------------------------------------------------------
        // Dynamic routes — path parameters
        // ------------------------------------------------------------------

        // /users/:id  →  ctx.pathParams.get("id") = "42"
        r.get("/users/:id", ctx -> {
            String id = ctx.pathParams.get("id");
            return "<h1>User Profile</h1><p>Viewing user ID: <strong>" + id + "</strong></p>";
        });

        // /users/:id/posts/:postId  →  multiple params in one route
        r.get("/users/:id/posts/:postId", ctx -> {
            String userId = ctx.pathParams.get("id");
            String postId = ctx.pathParams.get("postId");
            return "<h1>Post</h1><p>User " + userId + " → Post " + postId + "</p>";
        });

        // ------------------------------------------------------------------
        // Query string route
        // /search?q=java&page=2  →  ctx.queryParams.get("q") = "java"
        // ------------------------------------------------------------------
        r.get("/search", ctx -> {
            String query = ctx.queryParams.getOrDefault("q", "(no query)");
            String page  = ctx.queryParams.getOrDefault("page", "1");
            return "<h1>Search Results</h1>"
                    + "<p>Query: <strong>" + query + "</strong></p>"
                    + "<p>Page: " + page + "</p>";
        });

        r.get("/stream/countdown", ctx -> "__CHUNKED__");
        r.get("/stream/generate", ctx -> "__CHUNKED__");

        return r;
    }

    // -------------------------------------------------------------------------
    // Server startup
    // -------------------------------------------------------------------------
    private static StaticFileServer staticFiles;

    public static void main(String[] args) throws IOException {
        staticFiles = new StaticFileServer("public");

        // One virtual thread per connection. A keep-alive connection blocks its thread
        // on readLine() between requests; with platform threads in a fixed pool that
        // capped us at 16 open connections. Virtual threads unmount while blocked on
        // I/O, so idle connections cost almost nothing and there's no cap.
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        
        int port = 8080;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Server started on port " + port);

        // --- HTTPS on port 8443 ---
        // Only starts if keystore.jks exists — fails gracefully otherwise
        ServerSocket httpsSocket = null;
        File keystoreFile = new File("keystore.jks");

        if (keystoreFile.exists()) {
            try {
                httpsSocket = HttpsSetup.createSSLServerSocket(8443, "keystore.jks", "changeit");
                System.out.println("HTTPS listening on https://localhost:8443");
            } catch (Exception e) {
                System.err.println("⚠️  HTTPS setup failed: " + e.getMessage());
                System.err.println("   Run generate-cert.sh first to create keystore.jks");
            }
        } else {
            System.out.println("⚠️  keystore.jks not found — HTTPS disabled.");
            System.out.println("   Run: bash generate-cert.sh  to enable HTTPS");
        }

        // --- Accept loop for HTTPS (runs on a dedicated thread) ---
        // We spin up a separate thread just for HTTPS accept() calls.
        // Once accepted, connections go into the same shared pool as HTTP.
        //
        // Why a separate thread?
        // accept() is a blocking call — it sits and waits for the next connection.
        // If we put both accept() calls in the same thread, the second one would
        // never run while the first is waiting. Two threads = both ports active.
        if (httpsSocket != null) {
            final ServerSocket finalHttpsSocket = httpsSocket;
            Thread httpsAcceptor = new Thread(() -> {
                while (true) {
                    try {
                        Socket client = finalHttpsSocket.accept();
                        // TLS handshake already completed inside accept()
                        // handleClient sees a normal Socket — no TLS code needed there
                        pool.submit(() -> handleClient(client));
                    } catch (IOException e) {
                        System.err.println("HTTPS accept error: " + e.getMessage());
                    }
                }
            });
            httpsAcceptor.setDaemon(true); // dies when main thread dies
            httpsAcceptor.start();
        }

        // --- Accept loop for HTTP (runs on main thread) ---
        while (true) {
            Socket clientSocket = serverSocket.accept();
            pool.submit(() -> handleClient(clientSocket));
        }
    }

    // -------------------------------------------------------------------------
    // Connection handler — identical for HTTP and HTTPS
    // TLS is completely invisible here - the socket behaves the same either way
    // -------------------------------------------------------------------------

    private static void handleClient(Socket clientSocket) {
        try {
            // Log whether this connection is plain HTTP or HTTPS
            // SSLSocket is a subclass of Socket — instanceof tells us which
            boolean isHttps = clientSocket instanceof javax.net.ssl.SSLSocket;
            String protocol = isHttps ? "HTTPS" : "HTTP";

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = clientSocket.getOutputStream();
            clientSocket.setSoTimeout(30000);

            while (true) {

                // --- Parse request ---
                HttpRequest req;
                try {
                    req = HttpRequest.parse(reader);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (req == null) break;

                String method       = req.method;
                String rawPath      = req.rawPath;
                String cleanPath    = req.cleanPath;
                String body         = req.body;
                boolean clientWantsClose = req.wantsClose;
                System.out.println("[" + protocol + "] [" + method + "] " + rawPath);

                // --- Extract session from cookie (if any) ---
                // Every request: check if the browser sent a sessionId cookie.
                // If it did, look it up in the store. Null = not logged in.
                String sessionId = SessionStore.extractSessionId(req.cookieHeader);
                SessionStore.Session session = sessionStore.get(sessionId);

                // Auth routes — handled separately because they need to set/clear cookies
                if (cleanPath.equals("/login") && method.equalsIgnoreCase("GET")) {
                    sendResponse(out, 200, "OK", buildLoginPage(null), null, clientWantsClose);

                } else if (cleanPath.equals("/login") && method.equalsIgnoreCase("POST")) {
                    handleLogin(body, out, clientWantsClose);

                } else if (cleanPath.equals("/logout")) {
                    handleLogout(sessionId, out);

                } else if (cleanPath.equals("/dashboard")) {
                    handleDashboard(session, out, clientWantsClose);

                } else if (cleanPath.startsWith("/stream/")) {
                    handleStreamRoute(cleanPath, out);
                } else {
                    // --- Route the request ---
                    Router.MatchResult match = router.match(method, rawPath, body);

                    String responseBody;
                    int statusCode;
                    String statusText;

                    if (match != null) {
                        statusCode = 200;
                        statusText = "OK";
                        responseBody = match.handler.handle(match.context);
                        // --- Send response ---
                        byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                        String connectionHeader = clientWantsClose ? "close" : "keep-alive";

                        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                                + "Content-Type: text/html; charset=utf-8\r\n"
                                + "Content-Length: " + bodyBytes.length + "\r\n"
                                + "Connection: " + connectionHeader + "\r\n"
                                + "\r\n";

                        out.write(headers.getBytes(StandardCharsets.UTF_8));
                        out.write(bodyBytes);
                        out.flush();
                    } else {
                        StaticFileServer.ServeResult result = staticFiles.resolve(rawPath);

                        switch (result.status) {
                            case OK:
                                // File found — stream it
                                staticFiles.serve(result.file, result.mimeType, out);
                                break;
                            case FORBIDDEN:
                                // Traversal attack detected
                                staticFiles.send403(out);
                                break;
                            case NOT_FOUND:
                                // Nothing matched at all
                                staticFiles.send404(out);
                                break;
                        }
                    }
                }

                if (clientWantsClose) break;
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
            System.out.println("Connection closed.\n");
        }
    }

    private static String guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css"))  return "text/css";
        if (lower.endsWith(".js"))   return "application/javascript";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        if (lower.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

// -------------------------------------------------------------------------
    // Auth route handlers
    // -------------------------------------------------------------------------

    /**
     * POST /login
     *
     * Body arrives as URL-encoded form: "username=ishan&password=password123"
     * We parse it, check credentials, and either:
     *   - Create a session + send Set-Cookie header (success)
     *   - Re-render the login form with an error (failure)
     */
    private static void handleLogin(String body, OutputStream out, boolean wantsClose)
            throws IOException {

        // Parse "username=ishan&password=password123" into a map
        Map<String, String> formData = parseFormBody(body);
        String username = formData.getOrDefault("username", "");
        String password = formData.getOrDefault("password", "");

        String expectedPassword = USERS.get(username);

        if (expectedPassword != null && expectedPassword.equals(password)) {
            // ✅ Credentials valid — create a session
            SessionStore.Session session = sessionStore.create();
            session.set("username", username);
            session.set("loginTime", new Date().toString());

            // Send redirect to dashboard WITH the Set-Cookie header
            // The browser will store the cookie and send it on every future request
            String setCookie = SessionStore.makeSetCookieHeader(session.getId());
            String headers = "HTTP/1.1 302 Found\r\n"
                    + "Location: /dashboard\r\n"
                    + "Set-Cookie: " + setCookie + "\r\n"   // ← browser stores this
                    + "Content-Length: 0\r\n"
                    + "\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.flush();
            System.out.println("Login successful: " + username);

        } else {
            // ❌ Bad credentials — show the form again with an error message
            System.out.println("Login failed for: " + username);
            sendResponse(out, 401, "Unauthorized",
                    buildLoginPage("❌ Invalid username or password"), null, wantsClose);
        }
    }

    /**
     * GET /dashboard
     *
     * Protected route — only accessible with a valid session.
     * If no valid session: redirect to /login instead of showing the page.
     */
    private static void handleDashboard(SessionStore.Session session,
                                        OutputStream out, boolean wantsClose)
            throws IOException {

        if (session == null) {
            // Not logged in — redirect to login page
            // This is the standard pattern for protected routes
            String headers = "HTTP/1.1 302 Found\r\n"
                    + "Location: /login\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        // Logged in — show the dashboard with their session data
        String username  = session.get("username");
        String loginTime = session.get("loginTime");
        String page = "<html><body style='font-family:sans-serif;padding:2rem;background:#0f172a;color:#e2e8f0'>"
                + "<h1>🎉 Welcome, " + username + "!</h1>"
                + "<p style='color:#94a3b8'>Session ID: <code style='color:#f472b6'>"
                + session.getId().substring(0, 16) + "...</code></p>"
                + "<p style='color:#94a3b8'>Logged in at: " + loginTime + "</p>"
                + "<p style='color:#94a3b8'>Session age: " + session.ageMinutes() + " minutes</p>"
                + "<br>"
                + "<a href='/logout' style='background:#ef4444;color:white;padding:8px 16px;"
                + "border-radius:4px;text-decoration:none'>Logout</a>"
                + "</body></html>";

        sendResponse(out, 200, "OK", page, null, wantsClose);
    }

    /**
     * GET /logout
     *
     * Destroy the session on the server AND clear the cookie in the browser.
     * Both steps are necessary:
     *   - Destroying server session:  makes the ID invalid immediately
     *   - Clearing browser cookie:    browser stops sending the dead ID
     */
    private static void handleLogout(String sessionId, OutputStream out)
            throws IOException {

        sessionStore.destroy(sessionId);

        // Clear cookie + redirect to login
        String headers = "HTTP/1.1 302 Found\r\n"
                + "Location: /login\r\n"
                + "Set-Cookie: " + SessionStore.makeClearCookieHeader() + "\r\n"  // ← Max-Age=0 deletes it
                + "Content-Length: 0\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Login page HTML builder
    // -------------------------------------------------------------------------

    private static String buildLoginPage(String errorMessage) {
        String error = (errorMessage != null)
                ? "<p style='color:#f87171'>" + errorMessage + "</p>"
                : "";
        // Note: no <form> tag needed — this is raw HTML sent from the server
        return "<html><body style='font-family:sans-serif;padding:2rem;background:#0f172a;color:#e2e8f0'>"
                + "<h1>🔐 Login</h1>"
                + error
                + "<form method='POST' action='/login'>"
                + "<div style='margin-bottom:1rem'>"
                + "<label>Username<br>"
                + "<input name='username' style='padding:8px;margin-top:4px;background:#1e293b;"
                + "color:#e2e8f0;border:1px solid #334155;border-radius:4px'></label></div>"
                + "<div style='margin-bottom:1rem'>"
                + "<label>Password<br>"
                + "<input name='password' type='password' style='padding:8px;margin-top:4px;"
                + "background:#1e293b;color:#e2e8f0;border:1px solid #334155;border-radius:4px'></label></div>"
                + "<button type='submit' style='background:#3b82f6;color:white;padding:8px 20px;"
                + "border:none;border-radius:4px;cursor:pointer'>Login</button>"
                + "</form>"
                + "<p style='color:#94a3b8;margin-top:1rem'>Try: ishan / password123</p>"
                + "</body></html>";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parse a URL-encoded form body into a map.
     * "username=ishan&password=abc" → { "username" → "ishan", "password" → "abc" }
     *
     * This is the same format as query strings — same parser works for both.
     */
    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    String key = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String val = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    map.put(key, val);
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    /**
     * Send a standard HTML response, with an optional extra header (e.g. Set-Cookie).
     */
    private static void sendResponse(OutputStream out, int status, String statusText,
                                     String body, String extraHeader, boolean wantsClose)
            throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(status).append(" ").append(statusText).append("\r\n");
        headers.append("Content-Type: text/html; charset=utf-8\r\n");
        headers.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        headers.append("Connection: ").append(wantsClose ? "close" : "keep-alive").append("\r\n");
        if (extraHeader != null) headers.append(extraHeader).append("\r\n");
        headers.append("\r\n");

        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Chunked streaming routes
    // -------------------------------------------------------------------------

    private static void handleStreamRoute(String path, OutputStream out) throws IOException {
        switch (path) {
            case "/stream/countdown": streamCountdown(out); break;
            case "/stream/generate":  streamGenerated(out); break;
            default: staticFiles.send404(out);
        }
    }

    private static void streamCountdown(OutputStream out) throws IOException {
        ChunkedResponse chunked = new ChunkedResponse(out);
        chunked.begin(200, "OK", "text/html; charset=utf-8");
        chunked.writeChunk("<html><body style='font-family:sans-serif;padding:2rem;"
                + "background:#0f172a;color:#e2e8f0'><h1>⏱ Countdown</h1>");
        for (int i = 10; i >= 1; i--) {
            chunked.writeChunk("<p style='font-size:2rem;color:#38bdf8'>" + i + "...</p>");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        chunked.writeChunk("<h2 style='color:#4ade80'>🚀 Done!</h2></body></html>");
        chunked.end();
    }

    private static void streamGenerated(OutputStream out) throws IOException {
        ChunkedResponse chunked = new ChunkedResponse(out);
        chunked.begin(200, "OK", "text/html; charset=utf-8");
        chunked.writeChunk("<html><body style='font-family:sans-serif;padding:2rem;"
                + "background:#0f172a;color:#e2e8f0'><h1>📊 Report</h1>"
                + "<table border='1' style='border-collapse:collapse;color:#e2e8f0'>"
                + "<tr><th style='padding:8px'>ID</th><th style='padding:8px'>Value</th></tr>");
        for (int i = 1; i <= 20; i++) {
            chunked.writeChunk("<tr><td style='padding:8px'>" + i
                    + "</td><td style='padding:8px'>" + (i * 42) + "</td></tr>");
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        chunked.writeChunk("</table></body></html>");
        chunked.end();
    }
}