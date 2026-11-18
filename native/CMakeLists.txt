enable_language(CXX)

if(NOT ATLASNET_SDK_SOURCE_DIR)
  if(AtlasNet_SOURCE_DIR)
    set(ATLASNET_SDK_SOURCE_DIR "${AtlasNet_SOURCE_DIR}")
  else()
    set(ATLASNET_SDK_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../AtlasNet" CACHE PATH
        "AtlasNet source used to build the node and module SDK")
  endif()
endif()

if(NOT TARGET AtlasNetModule)
  set(ATLASNET_SDK_BUILD_DIR "${ATLASNET_SDK_SOURCE_DIR}/build" CACHE PATH
      "Existing AtlasNet build used by the local stack")
  load_cache("${ATLASNET_SDK_BUILD_DIR}" READ_WITH_PREFIX SDK_
      VCPKG_TARGET_TRIPLET CMAKE_BUILD_TYPE ATLASNET_STEAMNETSOCK)
  set(sdk_include "${ATLASNET_SDK_BUILD_DIR}/vcpkg_installed/${SDK_VCPKG_TARGET_TRIPLET}/include")
  if(NOT EXISTS "${ATLASNET_SDK_BUILD_DIR}/libAtlasNetModule.so"
     OR NOT IS_DIRECTORY "${sdk_include}")
    message(FATAL_ERROR "Build AtlasNetModule first and set ATLASNET_SDK_SOURCE_DIR and ATLASNET_SDK_BUILD_DIR to the matching checkout and build.")
  endif()
  add_library(AtlasNetModule SHARED IMPORTED)
  set_target_properties(AtlasNetModule PROPERTIES
      IMPORTED_LOCATION "${ATLASNET_SDK_BUILD_DIR}/libAtlasNetModule.so"
      INTERFACE_INCLUDE_DIRECTORIES "${ATLASNET_SDK_SOURCE_DIR}/include;${sdk_include};${ATLASNET_SDK_BUILD_DIR}/_deps/entt-src/src"
      INTERFACE_COMPILE_DEFINITIONS "SPDLOG_COMPILED_LIB;SPDLOG_FMT_EXTERNAL")
  if(SDK_ATLASNET_STEAMNETSOCK)
    target_compile_definitions(AtlasNetModule INTERFACE ATLASNET_STEAMNETSOCK)
    set(ATLASNET_STEAM_HEADERS_DIR "" CACHE PATH "Provisioned GameNetworkingSockets include directory")
    find_path(_steam_headers steam/steam_api_common.h
      HINTS "${ATLASNET_STEAM_HEADERS_DIR}" "${sdk_include}/GameNetworkingSockets"
            "${CMAKE_CURRENT_LIST_DIR}/../AtlasNet/build/vcpkg_installed/${SDK_VCPKG_TARGET_TRIPLET}/include/GameNetworkingSockets"
      NO_DEFAULT_PATH NO_CACHE REQUIRED)
    target_include_directories(AtlasNetModule INTERFACE "${_steam_headers}")
  endif()
  if(SDK_CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(AtlasNetModule INTERFACE ATLASNET_TRACY_ENABLED)
  endif()
endif()

add_library(AtlasNetMinecraftShard MODULE MinecraftShardModule.cpp)
target_compile_features(AtlasNetMinecraftShard PRIVATE cxx_std_23)
target_link_libraries(AtlasNetMinecraftShard PRIVATE AtlasNetModule)
set_target_properties(AtlasNetMinecraftShard PROPERTIES PREFIX "")

if(NOT TARGET AtlasNetXAxisSplit)
  add_subdirectory("${ATLASNET_SDK_SOURCE_DIR}/Examples/Heuristics/XAxisSplit"
                   "${CMAKE_CURRENT_BINARY_DIR}/xsplit" EXCLUDE_FROM_ALL)
  target_compile_features(AtlasNetXAxisSplit PRIVATE cxx_std_23)
endif()
add_dependencies(BuildServer AtlasNetXAxisSplit)

target_include_directories(AtlasNetMinecraftShard PRIVATE
    "${_minecraft_java_home}/include" "${_minecraft_java_home}/include/linux")
target_link_libraries(AtlasNetMinecraftShard PRIVATE "${_minecraft_java_home}/lib/server/libjvm.so")
target_compile_definitions(AtlasNetMinecraftShard PRIVATE
    MINECRAFT_SHARD_PROPERTIES_FILE="${CMAKE_CURRENT_SOURCE_DIR}/../server.properties"
    MINECRAFT_SHARD_LAUNCH_FILE="${CMAKE_CURRENT_SOURCE_DIR}/../fabric/build/atlasnet/shard-launch.txt")

if(TARGET AtlasNetSteamNetSock OR SDK_ATLASNET_STEAMNETSOCK)
  if(NOT TARGET AtlasNetClient)
    get_filename_component(_steam_prefix "${_steam_headers}/../.." ABSOLUTE)
    list(PREPEND CMAKE_PREFIX_PATH "${_steam_prefix}" "${sdk_include}/..")
    set(CMAKE_FIND_PACKAGE_PREFER_CONFIG TRUE)
    find_program(Protobuf_PROTOC_EXECUTABLE protoc REQUIRED)
    find_package(GameNetworkingSockets CONFIG REQUIRED)
    add_library(AtlasNetClient STATIC IMPORTED)
    set_target_properties(AtlasNetClient PROPERTIES
        IMPORTED_LOCATION "${ATLASNET_SDK_BUILD_DIR}/libAtlasNetClient.a")
    target_link_libraries(AtlasNetClient INTERFACE
        "${ATLASNET_SDK_BUILD_DIR}/libAtlasNetCore.a"
        GameNetworkingSockets::GameNetworkingSockets_s atomic)
  endif()
  add_library(AtlasNetMinecraftClient SHARED SteamClientBridge.cpp)
  target_compile_features(AtlasNetMinecraftClient PRIVATE cxx_std_23)
  target_include_directories(AtlasNetMinecraftClient PRIVATE
      "${_minecraft_java_home}/include" "${_minecraft_java_home}/include/linux")
  target_link_libraries(AtlasNetMinecraftClient PRIVATE AtlasNetClient AtlasNetModule)
  target_link_options(AtlasNetMinecraftClient PRIVATE "LINKER:--no-undefined")
endif()
