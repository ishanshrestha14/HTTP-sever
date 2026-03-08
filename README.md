# Java HTTP Server

An HTTP/1.1 server written from scratch in plain Java: raw `ServerSocket`s, no frameworks, no runtime dependencies. It handles keep-alive, path-parameter routing, chunked streaming, static files with traversal protection, cookie sessions, and TLS.

**Performance:** ~64,700 requests/sec across 100 concurrent keep-alive connections on a dynamic route (p99 4.1 ms), and ~38,800 requests/sec on a static file ([benchmark](#benchmark)).

---

## Features

| Feature | Where | Notes |
|---|---|---|
| HTTP/1.1 request parsing | `HttpRequest` | Request line, headers (case-insensitive), `Content-Length` POST bodies |
| Keep-alive connections | `SimpleServer` | Many requests per TCP connection; honors `Connection: close`; 30s idle timeout |
| Concurrency | `SimpleServer` | One virtual thread per connection (Java 21), with no fixed connection cap |
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

Requires Java 21+ and Maven.

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

`wrk -t4 -d30s --latency`, with the server and wrk on the same machine (Apple M4 Pro, 12 cores, 24 GB, OpenJDK 25). Request logging goes to `/dev/null`.

| Endpoint | Connections | Requests/sec | p50 | p99 | Errors |
|---|---|---|---|---|---|
| `GET /users/42` (router + path param) | 100 | **64,722** | 1.49 ms | 4.13 ms | 0 |
| `GET /index.html` (static file from disk) | 100 | **38,763** | 3.14 ms | 7.90 ms | 0 |
| `GET /users/42` | 16 | 64,729 | 0.24 ms | 0.43 ms | 0 |
| `GET /index.html` | 16 | 42,370 | 0.41 ms | 0.95 ms | 0 |

These are loopback numbers. They show request-handling overhead, not real network throughput.

**Platform threads vs. virtual threads.** The server first used a fixed pool of 16 platform threads. Each keep-alive connection held one thread while it waited for its next request, so only 16 connections could be open at once. A 17th client got no response until a worker freed up (30s idle timeout). That version did ~71,000 req/s (dynamic) and ~55,500 req/s (static), but only for those 16 connections. At `-c100`, the other 84 connections got no service.

With virtual threads, every connection gets served: per-thread request rates in wrk vary by about ±1k, where before they varied by ±10k. With 200 idle keep-alive connections held open, a new client is still answered in under 1 ms. The cost is roughly 9% lower throughput on dynamic routes and 24% on static files. That comes from the overhead of scheduling virtual threads and from blocking `FileInputStream` reads.

---

## Design Decisions

**No frameworks.** The point is to see what Tomcat, Netty, and Spring MVC hide: parsing bytes off a socket, framing responses with `Content-Length` or chunks, and managing connection lifetimes.

**Threads, not NIO.** Thread-per-connection with blocking I/O is the simplest model to reason about. The handler reads like a script: read request, route, write response, loop.

**Virtual threads.** Keep-alive connections spend most of their time idle and blocked on `readLine()`. A platform thread per connection is too expensive, and a fixed pool caps how many connections can be open. A virtual thread is unmounted from its carrier thread while it waits on socket I/O, so the simple blocking style still works with thousands of open connections.

**TLS is transparent.** `SSLServerSocket.accept()` returns a socket whose streams are already encrypted, so HTTP and HTTPS share one `handleClient` method.

**Path-based traversal check.** `getCanonicalFile()` resolves `..` and symlinks, then `Path.startsWith` compares whole path segments. A plain `String.startsWith` would treat `/srv/public-secret` as inside `/srv/public`. There is a test for this case.

---

## Known Limitations

- **No connection limit.** Virtual threads remove the 16-connection cap, but nothing replaces it. Many slow or idle clients (Slowloris-style) can hold sockets open until the 30s idle timeout.
- **No HTML escaping.** `/submit`, `/search` and `/users/:id` echo user input into HTML as-is (reflected XSS). This is acceptable for a learning project but not for anything public.
- **Request bodies** are read as characters, not bytes, so `Content-Length` is only exact for ASCII bodies. Only `Content-Length` bodies are supported, not chunked request bodies.
- **Demo credentials** are hard-coded in plain text in `SimpleServer`. Sessions live in memory and are lost on restart.
