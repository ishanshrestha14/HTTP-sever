package httpserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static httpserver.StaticFileServer.ServeStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class StaticFileServerTest {

    @TempDir
    Path root;

    private StaticFileServer server;

    @BeforeEach
    void setUp() throws IOException {
        // root/
        //   secret.txt            <- outside public/, must never be served
        //   public-secret/key.txt <- sibling whose name starts with "public"
        //   public/index.html
        //   public/css/style.css
        Files.writeString(root.resolve("secret.txt"), "top secret");
        Files.createDirectories(root.resolve("public-secret"));
        Files.writeString(root.resolve("public-secret/key.txt"), "private key");
        Files.createDirectories(root.resolve("public/css"));
        Files.writeString(root.resolve("public/index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("public/css/style.css"), "body{}");

        server = new StaticFileServer(root.resolve("public").toString());
    }

    @Test
    void servesFileInsidePublicDir() throws IOException {
        StaticFileServer.ServeResult r = server.resolve("/css/style.css");
        assertEquals(OK, r.status);
        assertEquals("text/css; charset=utf-8", r.mimeType);
    }

    @Test
    void directoryFallsBackToIndexHtml() throws IOException {
        StaticFileServer.ServeResult r = server.resolve("/");
        assertEquals(OK, r.status);
        assertEquals("index.html", r.file.getName());
    }

    @Test
    void missingFileIsNotFound() throws IOException {
        assertEquals(NOT_FOUND, server.resolve("/nope.html").status);
    }

    @Test
    void blocksParentDirectoryTraversal() throws IOException {
        assertEquals(FORBIDDEN, server.resolve("/../secret.txt").status);
        assertEquals(FORBIDDEN, server.resolve("/css/../../secret.txt").status);
        assertEquals(FORBIDDEN, server.resolve("/../../../../../../etc/passwd").status);
    }

    @Test
    void blocksTraversalIntoSiblingWithSharedPrefix() throws IOException {
        // ".../public-secret" starts with the string ".../public",
        // so a naive String.startsWith check would let this through.
        assertEquals(FORBIDDEN, server.resolve("/../public-secret/key.txt").status);
    }

    @Test
    void dotDotThatStaysInsidePublicIsAllowed() throws IOException {
        StaticFileServer.ServeResult r = server.resolve("/css/../index.html");
        assertEquals(OK, r.status);
        assertEquals("index.html", r.file.getName());
    }
}
