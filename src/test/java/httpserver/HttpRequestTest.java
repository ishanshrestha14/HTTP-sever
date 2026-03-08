package httpserver;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestTest {

    private static HttpRequest parse(String raw) throws IOException {
        return HttpRequest.parse(new BufferedReader(new StringReader(raw)));
    }

    @Test
    void parsesRequestLineAndSplitsQueryString() throws IOException {
        HttpRequest req = parse("GET /search?q=java&page=2 HTTP/1.1\r\nHost: localhost\r\n\r\n");

        assertEquals("GET", req.method);
        assertEquals("/search?q=java&page=2", req.rawPath);
        assertEquals("/search", req.cleanPath);
        assertEquals("", req.body);
        assertFalse(req.wantsClose);
        assertNull(req.cookieHeader);
    }

    @Test
    void readsPostBodyUsingContentLength() throws IOException {
        String raw = "POST /submit HTTP/1.1\r\n"
                + "Content-Length: 11\r\n"
                + "\r\n"
                + "name=Alice&EXTRA-BYTES-NOT-PART-OF-BODY";
        HttpRequest req = parse(raw);

        assertEquals("POST", req.method);
        assertEquals(11, req.contentLength);
        assertEquals("name=Alice&", req.body);
    }

    @Test
    void headerNamesAreCaseInsensitive() throws IOException {
        HttpRequest req = parse("GET / HTTP/1.1\r\n"
                + "CONNECTION: Close\r\n"
                + "cOoKiE: theme=dark; sessionId=abc123\r\n"
                + "\r\n");

        assertTrue(req.wantsClose);
        assertEquals("theme=dark; sessionId=abc123", req.cookieHeader);
        assertEquals("abc123", SessionStore.extractSessionId(req.cookieHeader));
    }

    @Test
    void parsesConsecutiveRequestsOnOneKeepAliveConnection() throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(
                "POST /a HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc"
                + "GET /b HTTP/1.1\r\nConnection: close\r\n\r\n"));

        HttpRequest first = HttpRequest.parse(reader);
        HttpRequest second = HttpRequest.parse(reader);

        assertEquals("/a", first.cleanPath);
        assertEquals("abc", first.body);
        assertEquals("/b", second.cleanPath);
        assertTrue(second.wantsClose);
        assertNull(HttpRequest.parse(reader), "end of stream yields null");
    }

    @Test
    void returnsNullForClosedOrMalformedInput() throws IOException {
        assertNull(parse(""));                      // client closed connection
        assertNull(parse("\r\n"));                  // blank request line
        assertNull(parse("GARBAGE\r\n\r\n"));       // missing path + version
    }
}
