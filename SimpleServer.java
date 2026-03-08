import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class SimpleServer {

    // -------------------------------------------------------------------------
    // Route definitions — register all your routes here, at the top.
    // Adding a new route = one new line. No touching the server loop.
    // -------------------------------------------------------------------------
    private static final Router router = buildRouter();

    private static Router buildRouter() {
        Router r = new Router();

        // Basic routes
        r.get("/", ctx ->
                "<h1>Welcome to my Server! 🚀</h1>" +
                        "<p><a href='/hello'>Say hello</a> | " +
                        "<a href='/users/42'>View user 42</a> | " +
                        "<a href='/search?q=java&page=1'>Search</a></p>"
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

        return r;
    }

    // -------------------------------------------------------------------------
    // Server startup
    // -------------------------------------------------------------------------
    private static StaticFileServer staticFiles;

    public static void main(String[] args) throws IOException {
        staticFiles = new StaticFileServer("public");
        
        int port = 8080;
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("Server started on port " + port);

        ExecutorService pool = Executors.newFixedThreadPool(8);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected: " + clientSocket.getRemoteSocketAddress());
            pool.submit(() -> handleClient(clientSocket));
        }
    }

    // -------------------------------------------------------------------------
    // Connection handler — keep-alive loop
    // -------------------------------------------------------------------------

    private static void handleClient(Socket clientSocket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = clientSocket.getOutputStream();

            clientSocket.setSoTimeout(30000);

            while (true) {

                // --- Parse request line ---
                String requestLine;
                try {
                    requestLine = reader.readLine();
                } catch (SocketTimeoutException e) {
                    System.out.println("Idle timeout — closing connection.");
                    break;
                }

                if (requestLine == null || requestLine.isEmpty()) break;

                String[] parts = requestLine.split(" ");
                if (parts.length < 3) break;

                String method  = parts[0];
                String rawPath = parts[1]; // may include query string

                System.out.println("[" + method + "] " + rawPath);

                // --- Parse headers ---
                String line;
                int contentLength = 0;
                boolean clientWantsClose = false;

                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                    if (lower.startsWith("connection:") && lower.contains("close")) {
                        clientWantsClose = true;
                    }
                }

                // --- Read POST body ---
                String body = "";
                if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                    char[] bodyChars = new char[contentLength];
                    reader.read(bodyChars, 0, contentLength);
                    body = new String(bodyChars);
                }

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

                if (clientWantsClose) {
                    System.out.println("Client requested close.");
                    break;
                }
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
}