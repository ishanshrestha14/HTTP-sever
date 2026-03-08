package httpserver;

import java.io.*;

/**
 * One parsed HTTP request: request line, the headers we care about, and the body.
 *
 * Parsing lives here (not inline in SimpleServer) so it can be unit-tested
 * against a plain BufferedReader — no sockets needed.
 */
public class HttpRequest {
    public final String method;
    public final String rawPath;      // may include query string: "/search?q=java"
    public final String cleanPath;    // path without query string: "/search"
    public final int contentLength;
    public final boolean wantsClose;  // client sent "Connection: close"
    public final String cookieHeader; // null if no Cookie header
    public final String body;

    private HttpRequest(String method, String rawPath, int contentLength,
                        boolean wantsClose, String cookieHeader, String body) {
        this.method        = method;
        this.rawPath       = rawPath;
        int q = rawPath.indexOf('?');
        this.cleanPath     = q >= 0 ? rawPath.substring(0, q) : rawPath;
        this.contentLength = contentLength;
        this.wantsClose    = wantsClose;
        this.cookieHeader  = cookieHeader;
        this.body          = body;
    }

    /**
     * Read one request from the connection.
     *
     * Returns null when there is no usable request — the client closed the
     * connection, sent an empty line, or sent a malformed request line.
     * The caller should close the connection in that case.
     */
    public static HttpRequest parse(BufferedReader reader) throws IOException {

        // --- Request line: "GET /path HTTP/1.1" ---
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 3) return null;

        String method  = parts[0];
        String rawPath = parts[1];

        // --- Headers, until the blank line ---
        String line;
        int contentLength = 0;
        boolean wantsClose = false;
        String cookieHeader = null;

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
            if (lower.startsWith("connection:") && lower.contains("close")) {
                wantsClose = true;
            }
            if (lower.startsWith("cookie:")) {
                cookieHeader = line.substring("cookie:".length()).trim();
            }
        }

        // --- POST body: exactly Content-Length chars after the blank line ---
        // read() may return fewer chars than asked, so loop until we have them all.
        String body = "";
        if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
            char[] bodyChars = new char[contentLength];
            int total = 0;
            while (total < contentLength) {
                int n = reader.read(bodyChars, total, contentLength - total);
                if (n == -1) break;
                total += n;
            }
            body = new String(bodyChars, 0, total);
        }

        return new HttpRequest(method, rawPath, contentLength, wantsClose, cookieHeader, body);
    }
}
