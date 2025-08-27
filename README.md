AdBlocker Production-ready Starter
=================================

What I built for you:
 - An expanded Android Studio project skeleton with:
   - Aho-Corasick based FilterCompiler (Kotlin)
   - FilterManager with WorkManager-based updater (OkHttp)
   - SimpleDnsBlocker integrating FilterManager
   - Settings and Logs UI screens
   - Improved README and notes about production hard parts

What is still required to make this truly production-ready:
 1. **User-space packet handling / HTTP proxy**: Implement robust packet I/O from TUN fd, TCP reassembly and HTTP parsing. This is the core of system-wide blocking and typically requires native code (NDK) for performance and correctness.
 2. **HTTPS interception (optional)**: If you need deep content filtering for HTTPS, implement TLS interception with a user-installed CA, manage certificate installation and security warnings.
 3. **Performance optimization**: Large filter lists (EasyList/EasyPrivacy) need precompilation into highly-optimized binary formats. Consider native AC automaton, memory-mapped files, and tuned data structures.
 4. **Extensive testing**: Network edge-cases, Android OEM differences, battery profiling, multi-threading concurrency checks.
 5. **Privacy & legal**: If you use GPLv3 code (uBlock Origin), release your combined source under GPLv3. Check filter lists license terms.
 6. **Security audit**: MITM is a sensitive feature; get third-party review before shipping.
 7. **Telemetry & analytics (optional)**: If you collect metrics, make privacy-first decisions and provide opt-in.

How to use:
 - Open the project in Android Studio (set SDK/Gradle).
 - Build & install on a device (minSdk 26+).
 - Start the app, enable VPN when prompted.

If you want, next I can:
 - Implement a reference **user-space HTTP proxy** (Kotlin, using OkHttp) and steps to route traffic through it from the VPN (still complex).
 - Add a native Aho-Corasick implementation via NDK for very large lists.
 - Add example TLS-interception flow with certificate generation & installation scripts (dangerous; must be opt-in).

Legal:
 - This project scaffold is original code authored for you. It does not include uBlock Origin or AdGuard source. If you later integrate GPLv3 code, comply with GPL.



## Native (NDK) module

A reference native TCP proxy has been added at `app/src/main/cpp/nativeproxy.cpp` with JNI wrappers exposed via `com.example.adblocker.native.NativeProxy`. This provides a starting point for implementing a user-space socket proxy for HTTP traffic. It is a **reference** only and is not a production-grade TCP/TUN stack. 

## HTTPS MITM scripts

A script `scripts/generate_mitm_cert.sh` is included to create a test CA and server certificate using OpenSSL. Use with extreme caution. Installing a CA on a device allows full TLS interception.

## CI / GitHub Actions

A workflow `.github/workflows/android-build.yml` is added to build the debug APK and run unit tests. You may need to tweak SDK versions and NDK depending on your environment.


## TUN integration (native)

This version includes a native TUN reader (`native_tun.cpp`) which accepts a file descriptor from the Android VpnService and reads raw packets. It demonstrates basic IPv4 header parsing and logs packet source/destination. This is a reference-only implementation and does not perform TCP reassembly or forwarding — it's meant to show how to hand the TUN fd to native code for high-performance packet processing.

Notes:
 - On Android, passing the `ParcelFileDescriptor.getFd()` integer to native code is possible, but ensure you duplicate the FD properly if needed.
 - The native TUN reader runs in a dedicated thread and exposes `startTun(int fd)` / `stopTun(ptr)` JNI methods.
 - Production-ready behavior requires robust error handling, backpressure, memory limits, and security review.


## Native DNS proxy

A native UDP DNS proxy (`dns_proxy.cpp`) has been added. It listens on the port you start it with (we start it on 5353 in the demo) and responds with 127.0.0.1 for blocked domains. The VPN is configured to use `127.0.0.1` as DNS, so DNS queries from apps will go to the native proxy.

How it works:
 - The Java service writes a `blocked_domains.txt` file into the app's filesDir from the bundled asset list.
 - The native DNS proxy loads that file and reloads it periodically.
 - For blocked names, it returns a bogus A record (127.0.0.1). For others, it forwards the query to the upstream DNS server.

Testing notes:
 - On device/emulator, run the app and enable VPN. Then query DNS using `nslookup` or by browsing. Blocked domains from `assets/filters/basic_blocklist.txt` should resolve to 127.0.0.1.
 - This is a safer and high-impact blocking approach (DNS-level) and avoids handling full TCP/HTTPS traffic for most common ad/tracker domains.

Limitations:
 - DNS blocking does not catch resources fetched by direct IP or some modern protocols. Use packet-level filtering for deeper coverage.


## Advanced HTTP Proxy

A native advanced HTTP proxy has been added. It supports:
 - Plain HTTP request-level blocking by Host header
 - HTTP CONNECT tunneling (for HTTPS) with SNI-based blocking (no MITM required)

How to test:
 - Launch the app and enable VPN. The service will start the DNS proxy (5353) and the advanced proxy (8888).
 - To test HTTP blocking via the proxy, configure an app or browser to use `localhost:8888` as HTTP proxy (on device, you can set Wi‑Fi proxy to 127.0.0.1:8888 or use an app-level browser pointed to the proxy).
 - For HTTPS blocking without MITM: connect through proxy using CONNECT; the proxy parses the TLS ClientHello and extracts SNI — if SNI matches blocklist, the proxy will close the connection.

Limitations:
 - To enforce system-wide proxying, devices must support redirecting traffic to the local proxy or you need root/iptables rules. DNS-based blocking (already implemented) handles many cases without proxy.
