find_package(JNI 25 REQUIRED COMPONENTS JVM)
find_package(PkgConfig REQUIRED)
pkg_check_modules(MINECRAFT_GOBJECT REQUIRED IMPORTED_TARGET gobject-2.0)

set(ATLASNET_NATIVE_BUILD_DIR "${PROJECT_SOURCE_DIR}/AtlasNet/build" CACHE PATH
    "AtlasNet build directory containing the Node, shard worker, and GObject bindings")
set(ATLASNET_NATIVE_DEPENDENCIES "${ATLASNET_NATIVE_BUILD_DIR}/vcpkg_installed/x64-linux-clang/include"
    CACHE PATH "Headers from the matching AtlasNet native dependency build")


add_library(atlasnet-minecraft-module MODULE MinecraftJvmModule.cpp)
target_compile_features(atlasnet-minecraft-module PRIVATE cxx_std_23)
set_target_properties(atlasnet-minecraft-module PROPERTIES CXX_EXTENSIONS OFF)
target_compile_definitions(atlasnet-minecraft-module PRIVATE
    BOOST_THREAD_PROVIDES_FUTURE BOOST_THREAD_PROVIDES_FUTURE_CONTINUATION
    BOOST_THREAD_PROVIDES_FUTURE_INVALID_AFTER_GET BOOST_THREAD_PROVIDES_EXECUTORS BOOST_THREAD_USES_MOVE
    SPDLOG_FMT_EXTERNAL
    ATLASNET_MINECRAFT_CLASSPATH_FILE="${PROJECT_SOURCE_DIR}/atlasnet-plugin/build/minecraft-host/classpath.txt"
    ATLASNET_MINECRAFT_NATIVE_LIB_DIR="${ATLASNET_NATIVE_BUILD_DIR}/Integrations/GObject")
target_include_directories(atlasnet-minecraft-module PRIVATE
    "${PROJECT_SOURCE_DIR}/AtlasNet/include"
    "${PROJECT_SOURCE_DIR}/AtlasNet/Integrations/GObject"
    "${ATLASNET_NATIVE_DEPENDENCIES}")
target_link_libraries(atlasnet-minecraft-module PRIVATE JNI::JVM PkgConfig::MINECRAFT_GOBJECT
    "${ATLASNET_NATIVE_BUILD_DIR}/Integrations/GObject/libatlasnet-gobject.so"
    "${ATLASNET_NATIVE_DEPENDENCIES}/../lib/libfmt.a")

add_executable(atlasnet-minecraft-host main.cpp)
target_compile_features(atlasnet-minecraft-host PRIVATE cxx_std_17)
target_compile_definitions(atlasnet-minecraft-host PRIVATE
    ATLASNET_MINECRAFT_NODE="${ATLASNET_NATIVE_BUILD_DIR}/AtlasNetNode"
    ATLASNET_MINECRAFT_MODULE="$<TARGET_FILE:atlasnet-minecraft-module>")
add_dependencies(atlasnet-minecraft-host atlasnet-minecraft-module minecraft-host-classpath)
