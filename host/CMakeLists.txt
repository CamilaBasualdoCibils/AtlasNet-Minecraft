find_package(JNI 25 REQUIRED COMPONENTS JVM)

function(add_minecraft_module target module_name capability registration node_only)
    add_library(${target} MODULE MinecraftJvmModule.cpp)
    target_compile_features(${target} PRIVATE cxx_std_23)
    set_target_properties(${target} PROPERTIES CXX_EXTENSIONS OFF)
    target_compile_definitions(${target} PRIVATE
        ATLASNET_MINECRAFT_MODULE_NAME="${module_name}"
        ATLASNET_MINECRAFT_CAPABILITY=${capability}
        ATLASNET_MINECRAFT_REGISTER="${registration}"
        ATLASNET_MINECRAFT_NODE_ONLY=${node_only}
        ATLASNET_MINECRAFT_CLASSPATH_FILE="${PROJECT_SOURCE_DIR}/atlasnet-plugin/build/minecraft-host/classpath.txt"
        ATLASNET_MINECRAFT_NATIVE_LIB_DIR="$<TARGET_FILE_DIR:AtlasNetGObject>")
    target_link_libraries(${target} PRIVATE JNI::JVM AtlasNetGObject)

    add_dependencies(${target} minecraft-host-classpath)
endfunction()

add_minecraft_module(atlasnet-minecraft-shard AtlasNetMinecraftShard ShardProvider registerShard 0)
add_minecraft_module(atlasnet-minecraft-endpoint AtlasNetMinecraftEndpoint ClientEndpoint registerEndpoint 1)
add_minecraft_module(atlasnet-minecraft-admission AtlasNetMinecraftAdmission ClientAdmissionHandler registerAdmission 1)
add_minecraft_module(atlasnet-minecraft-spawn AtlasNetMinecraftSpawn ClientSpawnResolver registerSpawn 1)
add_custom_target(atlasnet-minecraft-modules DEPENDS
    atlasnet-minecraft-shard atlasnet-minecraft-endpoint
    atlasnet-minecraft-admission atlasnet-minecraft-spawn)
