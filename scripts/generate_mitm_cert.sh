#!/usr/bin/env bash
# WARNING: Generating a root CA and using it for TLS interception is a powerful and dangerous action.
# Always inform & get explicit user consent. Do NOT use on other people's devices/networks.
#
# This script creates a root CA and a sample server cert for testing. For production you must protect
# the CA private key and provide a secure UI for users to install the CA certificate.
#
# Usage: ./generate_mitm_cert.sh myCA
set -e
CA_NAME=${1:-"adblock_mitm_ca"}
mkdir -p certs
# 1) generate CA key
openssl genrsa -out certs/${CA_NAME}.key 4096
# 2) create CA cert
openssl req -x509 -new -nodes -key certs/${CA_NAME}.key -sha256 -days 3650 -subj "/CN=${CA_NAME}"
# 3) generate server key & CSR
openssl genrsa -out certs/server.key 2048
openssl req -new -key certs/server.key -out certs/server.csr -subj "/CN=example.com"
# 4) sign server cert with CA
openssl x509 -req -in certs/server.csr -CA certs/${CA_NAME}.crt -CAkey certs/${CA_NAME}.key -CAcreateserial -out certs/server.crt -days 825 -sha256
echo "Generated certs in ./certs. Install ${CA_NAME}.crt on device as a trusted CA to test MITM (dangerous)."
