#!/bin/bash
# generate-cert.sh
#
# Generates a self-signed TLS certificate and stores it in a Java KeyStore.
#
# What each flag means:
#   -genkeypair     → generate a public/private key pair
#   -alias server   → name for this entry in the keystore
#   -keyalg RSA     → RSA encryption algorithm (industry standard)
#   -keysize 2048   → 2048-bit key (strong enough for local dev)
#   -validity 365   → certificate valid for 1 year
#   -keystore       → output file (the keystore)
#   -storepass      → password to protect the keystore file
#   -keypass        → password to protect the private key
#   -dname          → Distinguished Name — identifies who owns the certificate
#                     CN=localhost means "this cert is for localhost"
#
# In production you'd replace this with a certificate from Let's Encrypt,
# and the -dname CN would be your actual domain name (e.g. CN=mysite.com)

keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore keystore.jks \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost, OU=Dev, O=MyServer, L=City, ST=State, C=US"

echo ""
echo "✅ keystore.jks generated successfully."
echo "   Place it in the same directory as your .java files before running the server."
echo ""
echo "⚠️  When you open https://localhost:8443 in a browser,"
echo "   you'll see a security warning — this is expected for self-signed certs."
echo "   Click 'Advanced' → 'Proceed to localhost' to continue."