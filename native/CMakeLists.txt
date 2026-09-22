# libpvmnative — optional. Not wired into AGP until a real NDK build ships
# (docs/NATIVE_BINARIES.md H4). Sources compile with:
#   cmake -B build -DANDROID_ABI=arm64-v8a  (from an NDK toolchain file)
cmake_minimum_required(VERSION 3.22)
project(pvmnative C)

add_library(pvmnative SHARED
    pty/pty.c
    launcher/launcher.c
)
target_include_directories(pvmnative PRIVATE pty launcher)
