package httpserver;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Serves static files from a public/ directory.
 *
 * Responsibilities:
 *   1. Map URL path → file on disk
 *   2. Block directory traversal attacks (../../etc/passwd)
 *   3. Detect correct MIME type
 *   4. Stream file bytes directly to the client
 *   5. Return proper 403 / 404 when needed
 */
public class StaticFileServer {

    // The folder we're allowed to serve files from.
    // Everything outside this folder is forbidden.
    private final File publicDir;

    public StaticFileServer(String publicDirPath) throws IOException {
        this.publicDir = new File(publicDirPath).getCanonicalFile();

        // Create the directory if it doesn't exist yet
        if (!this.publicDir.exists()) {
            this.publicDir.mkdirs();
            System.out.println("Created public directory at: " + this.publicDir.getAbsolutePath());
        }
    }

    // -------------------------------------------------------------------------
    // Result of attempting to serve a file
    // -------------------------------------------------------------------------

    public enum ServeStatus { OK, NOT_FOUND, FORBIDDEN }

    public static class ServeResult {
        public final ServeStatus status;
        public final File file;         // non-null if status == OK
        public final String mimeType;   // non-null if status == OK

        private ServeResult(ServeStatus status, File file, String mimeType) {
            this.status   = status;
            this.file     = file;
            this.mimeType = mimeType;
        }

        static ServeResult ok(File file, String mimeType) {
            return new ServeResult(ServeStatus.OK, file, mimeType);
        }
        static ServeResult notFound()  { return new ServeResult(ServeStatus.NOT_FOUND,  null, null); }
        static ServeResult forbidden() { return new ServeResult(ServeStatus.FORBIDDEN,  null, null); }
    }

    // -------------------------------------------------------------------------
    // Main entry point — called by SimpleServer for every unmatched request
    // -------------------------------------------------------------------------

    /**
     * Try to resolve a URL path to a file inside public/.
     *
     * Returns a ServeResult describing what happened.
     * SimpleServer reads the result and writes the actual HTTP response.
     */
    public ServeResult resolve(String urlPath) throws IOException {

        // Step 1: Strip query string if somehow still present
        //   "/style.css?v=2" → "/style.css"
        int q = urlPath.indexOf('?');
        if (q >= 0) urlPath = urlPath.substring(0, q);

        // Step 2: Map URL path to a file path inside public/
        //   URL:  /style.css
        //   File: public/style.css
        File requested = new File(publicDir, urlPath).getCanonicalFile();
        //
        // .getCanonicalFile() is the key security step —
        // it resolves ALL ".." jumps and symlinks into an absolute path.
        //
        // Without it:
        //   new File("public/", "../../etc/passwd") → "public/../../etc/passwd"
        //   which Java happily opens as /etc/passwd
        //
        // With it:
        //   new File("public/", "../../etc/passwd").getCanonicalFile()
        //   → "/absolute/path/to/etc/passwd"  ← clearly outside public/

        // Step 3: Directory traversal check
        //
        // After canonicalizing, verify the result still lives inside publicDir.
        // If it escaped (via ..), reject with 403 Forbidden.
        //
        //   publicDir canonical:   /home/user/project/public
        //   requested canonical:   /etc/passwd
        //   startsWith check:      ❌ → 403
        //
        //   requested canonical:   /home/user/project/public/style.css
        //   startsWith check:      ✅ → continue
        //
        // Compare as Paths, not Strings: Path.startsWith works segment by segment,
        // so a sibling like "public-secret/" doesn't count as inside "public/".
        if (!requested.toPath().startsWith(publicDir.toPath())) {
            System.out.println("BLOCKED traversal attempt: " + urlPath);
            return ServeResult.forbidden();
        }

        // Step 4: If the path is a directory, look for index.html inside it
        //   /  →  public/index.html
        if (requested.isDirectory()) {
            requested = new File(requested, "index.html").getCanonicalFile();
        }

        // Step 5: File must actually exist and be a regular file
        if (!requested.exists() || !requested.isFile()) {
            return ServeResult.notFound();
        }

        // Step 6: Detect MIME type
        String mimeType = guessMimeType(requested.getName());

        return ServeResult.ok(requested, mimeType);
    }

    // -------------------------------------------------------------------------
    // Stream file bytes to client
    // -------------------------------------------------------------------------

    /**
     * Write a complete HTTP response for a successfully resolved file.
     *
     * Key difference from string responses:
     *   - We use file.length() for Content-Length (exact byte count)
     *   - We stream in 8KB chunks — never load the whole file into memory
     *   - This works correctly even for large images, JS bundles, etc.
     */
    public void serve(File file, String mimeType, OutputStream out) throws IOException {

        // Write headers
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: "   + mimeType      + "\r\n"
                + "Content-Length: " + file.length() + "\r\n"  // exact file size in bytes
                + "Connection: keep-alive\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));

        // Stream file in chunks — the right way to send any file
        //
        // Instead of:  out.write(Files.readAllBytes(file.toPath()))  ← loads 500MB into RAM
        // We do:       read 8KB → write 8KB → repeat until done    ← constant memory use
        //
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        out.flush();
        System.out.println("Served static file: " + file.getName() + " (" + file.length() + " bytes)");
    }

    // -------------------------------------------------------------------------
    // Error responses
    // -------------------------------------------------------------------------

    public void send403(OutputStream out) throws IOException {
        String body    = "<h1>403 Forbidden</h1><p>Access denied.</p>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 403 Forbidden\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    public void send404(OutputStream out) throws IOException {
        String body    = "<h1>404 Not Found</h1><p>That file doesn't exist.</p>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 404 Not Found\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }

    // -------------------------------------------------------------------------
    // MIME type detection
    // -------------------------------------------------------------------------

    /**
     * Map file extension → Content-Type header value.
     *
     * Why this matters:
     *   If you serve style.css with Content-Type: text/html,
     *   the browser ignores it as a stylesheet. MIME type is not optional.
     *
     * "application/octet-stream" is the safe fallback —
     *   it tells the browser "raw binary, just download it".
     */
    private String guessMimeType(String filename) {
        String f = filename.toLowerCase();

        // Text formats
        if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html; charset=utf-8";
        if (f.endsWith(".css"))  return "text/css; charset=utf-8";
        if (f.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (f.endsWith(".json")) return "application/json; charset=utf-8";
        if (f.endsWith(".txt"))  return "text/plain; charset=utf-8";
        if (f.endsWith(".xml"))  return "application/xml";
        if (f.endsWith(".csv"))  return "text/csv; charset=utf-8";

        // Images
        if (f.endsWith(".png"))  return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".gif"))  return "image/gif";
        if (f.endsWith(".svg"))  return "image/svg+xml";
        if (f.endsWith(".ico"))  return "image/x-icon";
        if (f.endsWith(".webp")) return "image/webp";

        // Fonts
        if (f.endsWith(".woff"))  return "font/woff";
        if (f.endsWith(".woff2")) return "font/woff2";
        if (f.endsWith(".ttf"))   return "font/ttf";

        // Audio / Video
        if (f.endsWith(".mp3"))  return "audio/mpeg";
        if (f.endsWith(".mp4"))  return "video/mp4";
        if (f.endsWith(".webm")) return "video/webm";

        // Safe fallback — browser will offer a download
        return "application/octet-stream";
    }
}