import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Implements HTTP/1.1 Chunked Transfer Encoding.
 *
 * Use this when:
 *   - Response size is unknown at the time you start sending
 *   - You want to stream data progressively (live feed, large files, generated content)
 *   - You want the client to start rendering before the full response is ready
 *
 * Wire format:
 *   <hex-size>\r\n
 *   <data>\r\n
 *   <hex-size>\r\n
 *   <data>\r\n
 *   0\r\n          ← terminal chunk — signals end of stream
 *   \r\n
 */
public class ChunkedResponse {

    private final OutputStream out;

    public ChunkedResponse(OutputStream out) {
        this.out = out;
    }

    // -------------------------------------------------------------------------
    // Send the HTTP headers that open a chunked response
    // -------------------------------------------------------------------------

    /**
     * Write the response status line + headers.
     * Note: NO Content-Length header — that's the whole point of chunked.
     */
    public void begin(int statusCode, String statusText, String contentType) throws IOException {
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: "       + contentType + "\r\n"
                + "Transfer-Encoding: chunked\r\n"   // ← tells client to expect chunks
                + "Connection: keep-alive\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "\r\n";   // blank line ends headers, chunks start immediately after
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Send a single chunk
    // -------------------------------------------------------------------------

    /**
     * Send one chunk of string data.
     *
     * Format:
     *   <length in hex>\r\n
     *   <data bytes>\r\n
     *
     * Example — sending "Hello":
     *   5\r\n
     *   Hello\r\n
     */
    public void writeChunk(String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        writeChunk(data);
    }

    /**
     * Send one chunk of raw bytes.
     * Used for binary data (images, file segments, etc.)
     */
    public void writeChunk(byte[] data) throws IOException {
        if (data.length == 0) return; // never send a zero-size chunk mid-stream — that means EOF

        // Write size in hexadecimal — why hex? It's the HTTP spec.
        // decimal 255 = hex FF = exactly 2 chars instead of 3
        String sizeHex = Integer.toHexString(data.length);

        out.write((sizeHex + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush(); // flush after each chunk so client sees it immediately
    }

    // -------------------------------------------------------------------------
    // Terminate the stream
    // -------------------------------------------------------------------------

    /**
     * Send the terminal chunk: "0\r\n\r\n"
     * This is how the client knows the response is complete.
     * ALWAYS call this — without it the client hangs waiting for more data.
     */
    public void end() throws IOException {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Convenience: stream an entire file in chunks
    // -------------------------------------------------------------------------

    /**
     * Read a file from disk and send it as chunks.
     *
     * Why this is better than Files.readAllBytes():
     *   A 100MB file → readAllBytes() → 100MB in RAM before first byte sent
     *   A 100MB file → streamFile()   → 8KB in RAM, client receives data instantly
     */
    public void streamFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; // 8KB per chunk
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                // Only send the bytes actually read (last chunk may be smaller)
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                writeChunk(chunk);
            }
        }
    }
}