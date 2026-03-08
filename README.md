# 🖥️ Java HTTP Server — Learning Project

A ground-up HTTP/1.1 server built in pure Java with no external frameworks. This project is structured as a series of progressive phases, each introducing new concepts in networking, concurrency, and web server design.

---

## 📁 Project Structure

```
java-http-server/
├── src/
│   └── main/
│       └── java/
│           ├── HttpServer.java        # Main server entry point
│           ├── ThreadPool.java        # Worker thread management
│           ├── RequestHandler.java    # HTTP parsing & routing
│           ├── Router.java            # URL routing logic
│           └── MimeTypes.java         # MIME type detection
├── public/                            # Static files served by the server
│   ├── index.html
│   └── style.css
└── README.md
```

---

## ✅ Completed Phases

### Phase 1 — Minimal HTTP Server
> **Core Goal:** Understand the raw TCP → HTTP request/response lifecycle.

**What was built:**
- Accept incoming TCP connections via `ServerSocket`
- Parse the HTTP request line (method, path, HTTP version) and headers
- Send a properly formatted HTTP/1.1 response
- Set correct `Content-Length` and `Content-Type` headers
- Basic routing for `/` and `/hello`

**Key concepts learned:**
- HTTP is just text over TCP
- The structure of a raw HTTP request and response
- Why `Content-Length` matters — clients use it to know when the body ends

---

### Phase 2 — Concurrency + POST + Static Files
> **Core Goal:** Handle multiple clients simultaneously and serve real content.

**What was built:**
- Thread pool for handling concurrent client connections
- POST body reading (using `Content-Length` to read the correct number of bytes)
- Extended routing: `/`, `/hello`, `/submit`
- Static file serving from a `public/` directory
- Basic MIME type detection (HTML, plain text, etc.)
- Proper response headers for all routes

**Key concepts learned:**
- Why a single-threaded server blocks all other clients
- How a thread pool limits resource usage vs. spawning unlimited threads
- How POST bodies differ from GET — they follow the headers after a blank line
- How static file servers work under the hood

---

## 🛠️ Remaining Phases

### Phase 3 — Advanced HTTP Handling

#### 3.1 Keep-Alive Connections
Handle multiple requests over a single TCP connection (HTTP/1.1 default behavior).

- Don't close the socket after each response
- Read the next request on the same connection
- Respect `Connection: close` header to end the session
- Add idle connection timeouts to prevent resource leaks

> **Why it matters:** Opening a new TCP connection for every request is expensive. Keep-alive dramatically reduces latency on pages with many assets.

#### 3.2 Dynamic Routing / Mini Router
Support parameterized URLs and query strings — the foundation of any REST API.

- Route patterns like `/users/:id` and `/posts/:postId`
- Extract and expose URL parameters to handlers
- Parse query strings: `/search?q=java&page=2`
- Middleware-style hooks for logging and simulated auth

> **Why it matters:** Real applications route to different logic based on URL structure, not just static paths.

#### 3.3 Chunked Transfer / Streaming Responses
Stream large responses without loading them fully into memory.

- Implement `Transfer-Encoding: chunked`
- Stream files in chunks (e.g., 8KB at a time)
- Handle large file downloads efficiently

> **Why it matters:** Loading a 500MB file into a byte array before sending it would crash the JVM. Streaming is mandatory for large content.

#### 3.4 Better MIME / Static File Handling
Make static file serving production-quality.

- Detect content type from file extension (CSS, JS, PNG, JPEG, SVG, etc.)
- Prevent `../` directory traversal attacks
- Return proper `404` for missing files
- Optionally: support `If-Modified-Since` caching headers

> **Why it matters:** Security and correctness — a server that serves `../../etc/passwd` is dangerous, and wrong MIME types break browsers.

---

### Phase 4 — Optional Learning Extensions

| Feature | What You'll Learn |
|---|---|
| Basic templating (`<%= name %>`) | String parsing, server-side rendering concepts |
| Cookie handling & sessions | Stateless HTTP + how sessions are faked with cookies |
| Request/response logging | Observability, middleware patterns |
| Metrics (request count, latency) | Performance monitoring basics |
| Minimal HTTPS (SSLContext) | TLS handshake, `SSLServerSocket`, certificate basics |

---

## 🚀 Running the Server

```bash
# Compile
javac -d out src/main/java/*.java

# Run (default port 8080)
java -cp out HttpServer

# Test with curl
curl http://localhost:8080/
curl http://localhost:8080/hello
curl -X POST -d "name=Alice" http://localhost:8080/submit
```

---

## 🧪 Manual Testing Cheatsheet

```bash
# GET requests
curl -v http://localhost:8080/
curl -v http://localhost:8080/hello

# POST request
curl -v -X POST -d "message=hello" http://localhost:8080/submit

# Static file
curl http://localhost:8080/index.html

# Test keep-alive (Phase 3)
curl --http1.1 -v http://localhost:8080/ http://localhost:8080/hello

# Test path param (Phase 3)
curl http://localhost:8080/users/42

# Test query string (Phase 3)
curl "http://localhost:8080/search?q=test&page=1"
```

---

## 📚 HTTP Reference

### Request Format
```
GET /path HTTP/1.1\r\n
Host: localhost:8080\r\n
Connection: keep-alive\r\n
\r\n
```

### Response Format
```
HTTP/1.1 200 OK\r\n
Content-Type: text/html\r\n
Content-Length: 42\r\n
\r\n
<body goes here>
```

### Common Status Codes Used
| Code | Meaning |
|---|---|
| `200 OK` | Success |
| `404 Not Found` | Route or file doesn't exist |
| `400 Bad Request` | Malformed request |
| `405 Method Not Allowed` | Wrong HTTP method for route |
| `500 Internal Server Error` | Unhandled server exception |

---

## 💡 Design Decisions

**Why no frameworks?** The goal is to understand what frameworks like Tomcat, Netty, and Spring MVC abstract away — parsing raw bytes, managing sockets, and speaking the HTTP protocol directly.

**Why Java threads and not async/NIO?** Threads are conceptually simpler and map well to the request-per-thread model. NIO and virtual threads (Project Loom) are natural next steps after mastering the basics.

**Why a thread pool and not unlimited threads?** Unbounded thread creation will exhaust memory under load. A fixed pool provides backpressure — requests queue up rather than crashing the server.