import javax.net.ssl.*;
import java.io.*;
import java.net.ServerSocket;
import java.security.*;

/**
 * Sets up an HTTPS server socket using Java's SSLContext.*
 * Responsibilities:
 *   1. Load the keystore file (contains our certificate + private key)
 *   2. Build an SSLContext (the TLS engine)
 *   3. Return an SSLServerSocket that handles TLS handshakes automatically*
 * Once you have the SSLServerSocket, you use it exactly like a plain
 * ServerSocket — accept(), getInputStream(), getOutputStream() all work
 * the same way. TLS is completely transparent after setup.
 */
public class HttpsSetup {

    /**
     * Create and return an SSLServerSocket on the given port.
     *
     * @param port         The HTTPS port to listen on (typically 8443 for dev)
     * @param keystorePath Path to the .jks keystore file
     * @param password     Password for both the keystore and private key
     */
    public static ServerSocket createSSLServerSocket(int port, String keystorePath, String password)
            throws Exception {

        char[] passwordChars = password.toCharArray();

        // -----------------------------------------------------------------
        // Step 1: Load the KeyStore
        //
        // A KeyStore is a protected file that holds:
        //   - Your certificate (public key + identity info)
        //   - Your private key (used to decrypt incoming messages)
        //
        // Think of it like a secure wallet for cryptographic material.
        // "JKS" = Java KeyStore format (Java's native format)
        // -----------------------------------------------------------------
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keystorePath)) {
            keyStore.load(fis, passwordChars);
        }
        System.out.println("✅ KeyStore loaded from: " + keystorePath);

        // -----------------------------------------------------------------
        // Step 2: Create a KeyManagerFactory
        //
        // The KeyManager is responsible for choosing which certificate
        // to present to clients during the TLS handshake.
        //
        // In our case we only have one certificate, so it always picks that.
        // Production servers with multiple domains might have multiple certs.
        //
        // "SunX509" = the standard X.509 certificate algorithm
        // -----------------------------------------------------------------
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, passwordChars);

        // -----------------------------------------------------------------
        // Step 3: Create a TrustManagerFactory
        //
        // A TrustManager decides which certificates to TRUST from clients.
        //
        // For a typical web server:
        //   - Server presents its certificate → client verifies it
        //   - Client does NOT present a certificate (no mutual TLS)
        //
        // So this TrustManager is mostly here because SSLContext requires it.
        // Using the same keystore means we'd trust clients presenting our
        // own cert — fine for dev, in prod you'd use the system trust store.
        // -----------------------------------------------------------------
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(keyStore);

        // -----------------------------------------------------------------
        // Step 4: Build the SSLContext
        //
        // SSLContext is the TLS engine — it knows the keys and certificates
        // and uses them to perform the handshake with each new client.
        //
        // "TLS" = use the best available TLS version (TLS 1.3 if available)
        // Older code used "SSLv3" or "TLSv1" — never use these, they're broken.
        // -----------------------------------------------------------------
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
                kmf.getKeyManagers(),    // our certificate + private key
                tmf.getTrustManagers(),  // which client certs to trust
                new SecureRandom()       // entropy source for key generation
        );

        // -----------------------------------------------------------------
        // Step 5: Create the SSLServerSocket
        //
        // This is a drop-in replacement for ServerSocket.
        // When a client connects, it automatically performs the TLS handshake
        // BEFORE returning from accept() — by the time your code sees the
        // socket, the connection is already encrypted.
        //
        // Your handleClient() method never needs to know about TLS at all.
        // -----------------------------------------------------------------
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket sslServerSocket = (SSLServerSocket) factory.createServerSocket(port);

        // Only allow strong TLS versions — disable old/broken ones
        // TLS 1.0 and 1.1 have known vulnerabilities, always disable them
        sslServerSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});

        System.out.println("✅ HTTPS server socket created on port " + port);
        return sslServerSocket;
    }
}