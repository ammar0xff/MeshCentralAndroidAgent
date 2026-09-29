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
echo "Persist it so CI signs future builds with the same key (in-place upgrades):"
echo "  base64 -w0 \"$KEYSTORE_FILE\" | gh secret set ANDROID_SIGNING_KEY_B64 --repo ammar0xff/MeshCentralAndroidAgent"
echo ""
echo "Or build locally with: ./gradlew assembleRelease"
