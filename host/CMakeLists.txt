find_package(JNI 25 REQUIRED COMPONENTS JVM)
find_package(PkgConfig REQUIRED)
pkg_check_modules(MINECRAFT_GOBJECT REQUIRED IMPORTED_TARGET gobject-2.0)

set(ATLASNET_NATIVE_BUILD_DIR "${PROJECT_SOURCE_DIR}/AtlasNet/build" CACHE PATH
    "AtlasNet build directory containing the Node, shard worker, and GObject bindings")
set(ATLASNET_NATIVE_DEPENDENCIES "${ATLASNET_NATIVE_BUILD_DIR}/vcpkg_installed/x64-linux-clang/include"
    CACHE PATH "Headers from the matching AtlasNet native dependency build")


function(add_minecraft_module target module_name capability registration node_only)
    add_library(${target} MODULE MinecraftJvmModule.cpp)
    target_compile_features(${target} PRIVATE cxx_std_23)
    set_target_properties(${target} PROPERTIES CXX_EXTENSIONS OFF)
    target_compile_definitions(${target} PRIVATE
        BOOST_THREAD_PROVIDES_FUTURE BOOST_THREAD_PROVIDES_FUTURE_CONTINUATION
        BOOST_THREAD_PROVIDES_FUTURE_INVALID_AFTER_GET BOOST_THREAD_PROVIDES_EXECUTORS BOOST_THREAD_USES_MOVE
        SPDLOG_FMT_EXTERNAL
        ATLASNET_MINECRAFT_MODULE_NAME="${module_name}"
        ATLASNET_MINECRAFT_CAPABILITY=${capability}
        ATLASNET_MINECRAFT_REGISTER="${registration}"
        ATLASNET_MINECRAFT_NODE_ONLY=${node_only}
        ATLASNET_MINECRAFT_CLASSPATH_FILE="${PROJECT_SOURCE_DIR}/atlasnet-plugin/build/minecraft-host/classpath.txt"
        ATLASNET_MINECRAFT_NATIVE_LIB_DIR="${ATLASNET_NATIVE_BUILD_DIR}/Integrations/GObject")
    target_include_directories(${target} PRIVATE
        "${PROJECT_SOURCE_DIR}/AtlasNet/include"
        "${PROJECT_SOURCE_DIR}/AtlasNet/Integrations/GObject"
        "${ATLASNET_NATIVE_DEPENDENCIES}")
    target_link_libraries(${target} PRIVATE JNI::JVM PkgConfig::MINECRAFT_GOBJECT
        "${ATLASNET_NATIVE_BUILD_DIR}/Integrations/GObject/libatlasnet-gobject.so"
        "${ATLASNET_NATIVE_DEPENDENCIES}/../lib/libfmt.a")

    add_dependencies(${target} minecraft-host-classpath)
endfunction()

add_minecraft_module(atlasnet-minecraft-shard AtlasNetMinecraftShard ShardProvider registerShard 0)
add_minecraft_module(atlasnet-minecraft-endpoint AtlasNetMinecraftEndpoint ClientEndpoint registerEndpoint 1)
add_minecraft_module(atlasnet-minecraft-admission AtlasNetMinecraftAdmission ClientAdmissionHandler registerAdmission 1)
add_minecraft_module(atlasnet-minecraft-spawn AtlasNetMinecraftSpawn ClientSpawnResolver registerSpawn 1)
add_custom_target(atlasnet-minecraft-modules DEPENDS
    atlasnet-minecraft-shard atlasnet-minecraft-endpoint
    atlasnet-minecraft-admission atlasnet-minecraft-spawn)
