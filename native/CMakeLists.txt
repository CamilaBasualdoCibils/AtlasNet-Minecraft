set(JAVA_HOME "${_minecraft_java_home}")
find_package(JNI REQUIRED COMPONENTS JVM)

add_library(AtlasNetMinecraftShard MODULE MinecraftShardModule.cpp)
target_compile_features(AtlasNetMinecraftShard PRIVATE cxx_std_23)
target_link_libraries(AtlasNetMinecraftShard PRIVATE AtlasNetModule JNI::JVM)
set_target_properties(AtlasNetMinecraftShard PROPERTIES PREFIX "")
target_compile_definitions(AtlasNetMinecraftShard PRIVATE
    MINECRAFT_SHARD_PROPERTIES_FILE="${CMAKE_CURRENT_SOURCE_DIR}/../server.properties"
    MINECRAFT_SHARD_LAUNCH_FILE="${CMAKE_CURRENT_SOURCE_DIR}/../fabric/build/atlasnet/shard-launch.txt")

add_library(AtlasNetMinecraftClient SHARED SteamClientBridge.cpp)
target_compile_features(AtlasNetMinecraftClient PRIVATE cxx_std_23)
target_link_libraries(AtlasNetMinecraftClient PRIVATE AtlasNetClient AtlasNetModule JNI::JNI)
target_link_options(AtlasNetMinecraftClient PRIVATE "LINKER:--no-undefined")
