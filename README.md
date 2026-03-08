# Java HTTP Server

An HTTP/1.1 server written from scratch in plain Java: raw `ServerSocket`s, no frameworks, no runtime dependencies. It handles keep-alive, path-parameter routing, chunked streaming, static files with traversal protection, cookie sessions, and TLS.

**Performance:** ~71,000 requests/sec on a dynamic route, ~55,500 requests/sec on a static file, with p99 latency under 0.7 ms ([benchmark](#benchmark)).

---

## Features

| Feature | Where | Notes |
|---|---|---|
| HTTP/1.1 request parsing | `HttpRequest` | Request line, headers (case-insensitive), `Content-Length` POST bodies |
| Keep-alive connections | `SimpleServer` | Many requests per TCP connection; honors `Connection: close`; 30s idle timeout |
| Concurrency | `SimpleServer` | Fixed pool of 16 worker threads (`ExecutorService`) |
| Routing | `Router` | Exact routes, path params (`/users/:id/posts/:postId`), URL-decoded query strings |
| Static files | `StaticFileServer` | Served from `public/`, MIME detection for ~20 types, 8 KB streaming, `index.html` fallback |
| Directory traversal protection | `StaticFileServer` | Canonicalizes the path and checks containment with `Path.startsWith`, which returns 403 for `../` escapes |
| Chunked transfer encoding | `ChunkedResponse` | `Transfer-Encoding: chunked` for responses of unknown length |
| Cookie sessions | `SessionStore` | 256-bit `SecureRandom` IDs, `HttpOnly` + `SameSite=Strict`, 30-minute expiry |
| HTTPS | `HttpsSetup` | `SSLServerSocket` on :8443, TLS 1.2/1.3 only, same handler as HTTP |

---

## Project Structure

```
.
├── pom.xml
├── generate-cert.sh                  # Creates keystore.jks (self-signed cert) for HTTPS
├── scripts/
│   └── loadtest.sh                   # Builds, starts the server, runs wrk
├── public/                           # Static files served at /
│   ├── index.html
│   ├── style.css
│   └── app.js
└── src/
    ├── main/java/httpserver/
    │   ├── SimpleServer.java         # Entry point: accept loops, connection handling, route table
    │   ├── HttpRequest.java          # Parses one request off the connection
    │   ├── Router.java               # Pattern matching, path params, query strings
    │   ├── StaticFileServer.java     # File resolution, traversal check, MIME types
    │   ├── ChunkedResponse.java      # Chunked transfer encoding writer
    │   ├── SessionStore.java         # In-memory sessions + cookie helpers
    │   └── HttpsSetup.java           # KeyStore → SSLContext → SSLServerSocket
    └── test/java/httpserver/
        ├── HttpRequestTest.java
        ├── RouterTest.java
        └── StaticFileServerTest.java
```

---

## Running

Requires Java 17+ and Maven.

```bash
mvn package
java -jar target/java-http-server-1.0-SNAPSHOT.jar
# → http://localhost:8080
```

Run from the repository root. The server looks for `public/` and `keystore.jks` relative to the working directory.

### HTTPS (optional)

```bash
bash generate-cert.sh       # writes keystore.jks (self-signed, CN=localhost)
java -jar target/java-http-server-1.0-SNAPSHOT.jar
# → https://localhost:8443  (browser will warn about the self-signed cert)
```

If `keystore.jks` is missing, the server logs a warning and runs HTTP only.

### Routes

| Method | Path | What it does |
|---|---|---|
| GET | `/` | Home page with links |
| GET | `/hello` | Plain HTML response |
| POST | `/submit` | Echoes the request body |
| GET | `/users/:id` | Path parameter |
| GET | `/users/:id/posts/:postId` | Two path parameters |
| GET | `/search?q=…&page=…` | Query string parameters |
| GET | `/stream/countdown` | Chunked response, one chunk every 500 ms |
| GET | `/stream/generate` | Chunked HTML table, 20 rows |
| GET/POST | `/login` | Login form / credential check (try `ishan` / `password123`) |
| GET | `/dashboard` | Session-protected; redirects to `/login` without a valid cookie |
| GET | `/logout` | Destroys the session and clears the cookie |
| GET | anything else | Static file from `public/`, or 404 |

### Try it with curl

```bash
curl http://localhost:8080/users/42
curl "http://localhost:8080/search?q=java&page=2"
curl -d "name=Alice" http://localhost:8080/submit
curl -N http://localhost:8080/stream/countdown                    # watch chunks arrive
curl -v http://localhost:8080/hello http://localhost:8080/users/1  # 2nd request reuses the connection
curl --path-as-is http://localhost:8080/../pom.xml                # 403 Forbidden
```

---

## Tests

```bash
mvn test
```

19 JUnit 5 tests:

- **`HttpRequestTest`**: request line and query split, `Content-Length` body reads (ignores trailing bytes), case-insensitive headers, two pipelined requests on one keep-alive stream, null on malformed or closed input.
- **`RouterTest`**: exact matches, single and multiple path params, trailing slashes, path vs. query params, segment-count and method mismatches.
- **`StaticFileServerTest`**: serving inside `public/`, `index.html` fallback, 404s, and traversal attempts (`/../secret.txt`, `/css/../../secret.txt`, `/../../../../etc/passwd`, plus the sibling-prefix case `/../public-secret/key.txt`). Uses a JUnit `@TempDir`.

---

## Benchmark

```bash
bash scripts/loadtest.sh            # needs wrk: brew install wrk
```

`wrk -t4 -c16 -d30s --latency`, with the server and wrk on the same machine (Apple M4 Pro, 12 cores, 24 GB, OpenJDK 25). Request logging goes to `/dev/null`.

| Endpoint | Requests/sec | p50 | p99 | Errors |
|---|---|---|---|---|
| `GET /users/42` (router + path param) | **70,987** | 0.22 ms | 0.31 ms | 0 |
| `GET /index.html` (static file from disk) | **55,517** | 0.27 ms | 0.62 ms | 0 |

These are loopback numbers. They show request-handling overhead, not real network throughput.

**Why 16 connections:** The worker pool has 16 threads, and each worker stays pinned to one keep-alive connection until it closes or idles out (30s). Throughput at `-c100` is the same as at `-c16` (~72k req/s), but only 16 connections get served. The other 84 wait, and a 17th client gets no response until a worker frees up. See [Limitations](#known-limitations).

---

## Design Decisions

**No frameworks.** The point is to see what Tomcat, Netty, and Spring MVC hide: parsing bytes off a socket, framing responses with `Content-Length` or chunks, and managing connection lifetimes.

**Threads, not NIO.** Thread-per-connection with blocking I/O is the simplest model to reason about. The handler reads like a script: read request, route, write response, loop.

**Fixed thread pool.** Unbounded thread creation runs out of memory under load. A fixed pool caps resource use, but with keep-alive it also caps concurrent connections (see below).

**TLS is transparent.** `SSLServerSocket.accept()` returns a socket whose streams are already encrypted, so HTTP and HTTPS share one `handleClient` method.

**Path-based traversal check.** `getCanonicalFile()` resolves `..` and symlinks, then `Path.startsWith` compares whole path segments. A plain `String.startsWith` would treat `/srv/public-secret` as inside `/srv/public`. There is a test for this case.

---

## Known Limitations

- **Connection cap of 16.** Keep-alive connections each hold a worker thread, so client 17 waits. Fixes: switch to virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`, Java 21+), or move to NIO selectors.
- **No HTML escaping.** `/submit`, `/search` and `/users/:id` echo user input into HTML as-is (reflected XSS). This is acceptable for a learning project but not for anything public.
- **Request bodies** are read as characters, not bytes, so `Content-Length` is only exact for ASCII bodies. Only `Content-Length` bodies are supported, not chunked request bodies.
- **Demo credentials** are hard-coded in plain text in `SimpleServer`. Sessions live in memory and are lost on restart.
