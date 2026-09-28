#!/bin/bash
# Run this on your machine with Java installed to generate a signing keystore

KEYSTORE_FILE="mdm-release.keystore"
ALIAS="mdm"
PASSWORD="changeit"

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=Your Company, OU=IT, O=Your Company, L=City, S=State, C=Country"

echo "Keystore generated: $KEYSTORE_FILE"
echo "Alias: $ALIAS"
echo "Password: $PASSWORD"
echo ""
echo "Update gradle.properties with your values, then run:"
echo "  ./gradlew assembleRelease"
