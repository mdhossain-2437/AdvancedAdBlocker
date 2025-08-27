cmake_minimum_required(VERSION 3.4.1)
project(adblock_native)

add_library(nativeproxy SHARED nativeproxy.cpp native_tun.cpp dns_proxy.cpp tcp_http_proxy.cpp adblock_bridge.cpp)

find_library(log-lib log)
target_link_libraries(nativeproxy ${log-lib})
