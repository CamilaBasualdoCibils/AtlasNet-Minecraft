cmake_minimum_required(VERSION 3.25)

option(ATLASNET_MINECRAFT_SHARD_MODULE "Build the native AtlasNet Minecraft integration" ON)
option(ATLASNET_EXAMPLES "Build AtlasNet examples" OFF)
option(ATLASNET_TESTS "Build AtlasNet tests" OFF)
option(ATLASNET_DEV "Build AtlasNet development programs" OFF)
option(ATLASNET_STEAMNETSOCK "Build SteamNetworkingSockets support" ON)

if(ATLASNET_MINECRAFT_SHARD_MODULE)
  if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/AtlasNet/CMakeLists.txt")
    message(FATAL_ERROR "Initialize the AtlasNet submodule: git submodule update --init --recursive")
  endif()
  if(NOT ATLASNET_STEAMNETSOCK)
    message(FATAL_ERROR "The Minecraft demo requires ATLASNET_STEAMNETSOCK=ON")
  endif()
  # Resolve the submodule's manifest before the first project() initializes vcpkg.
  set(VCPKG_MANIFEST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/AtlasNet" CACHE PATH "Dependency manifest")
  set(VCPKG_OVERLAY_TRIPLETS "${CMAKE_CURRENT_SOURCE_DIR}/AtlasNet/vcpkg-triplets" CACHE STRING "vcpkg triplets")
  if(NOT CMAKE_TOOLCHAIN_FILE AND DEFINED ENV{VCPKG_ROOT})
    set(CMAKE_TOOLCHAIN_FILE "$ENV{VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake" CACHE FILEPATH "vcpkg toolchain")
  endif()
  if(ATLASNET_STEAMNETSOCK)
    list(APPEND VCPKG_MANIFEST_FEATURES steamnetsock)
  endif()
  if(ATLASNET_EXAMPLES)
    list(APPEND VCPKG_MANIFEST_FEATURES examples)
  endif()
  if(ATLASNET_TESTS)
    list(APPEND VCPKG_MANIFEST_FEATURES testing)
  endif()
  list(REMOVE_DUPLICATES VCPKG_MANIFEST_FEATURES)
  project(AtlasNetMinecraft LANGUAGES C CXX)
else()
  project(AtlasNetMinecraft LANGUAGES NONE)
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(ATLASNET_MINECRAFT_JAVA_HOME "" CACHE PATH "Path to a JDK 21 for the Minecraft demo")
if(ATLASNET_MINECRAFT_JAVA_HOME)
  set(_minecraft_java_home "${ATLASNET_MINECRAFT_JAVA_HOME}")
else()
  # Keep FindJava's shared cache and any parent's Java 25 selection out of this lookup.
  find_program(_minecraft_javac NAMES javac HINTS "$ENV{JAVA_HOME}/bin" NO_CACHE)
  if(NOT _minecraft_javac)
    message(FATAL_ERROR "Install JDK 21 or set ATLASNET_MINECRAFT_JAVA_HOME.")
  endif()
  file(REAL_PATH "${_minecraft_javac}" _minecraft_javac)
  get_filename_component(_minecraft_java_bin "${_minecraft_javac}" DIRECTORY)
  get_filename_component(_minecraft_java_home "${_minecraft_java_bin}" DIRECTORY)
endif()

if(CMAKE_HOST_WIN32)
  set(_minecraft_executable_suffix ".exe")
  set(_minecraft_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/gradlew.bat")
else()
  set(_minecraft_executable_suffix "")
  set(_minecraft_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/gradlew")
endif()

execute_process(
  COMMAND "${_minecraft_java_home}/bin/javac${_minecraft_executable_suffix}" -version
  RESULT_VARIABLE _minecraft_java_result
  OUTPUT_VARIABLE _minecraft_java_version
  ERROR_VARIABLE _minecraft_java_version
)
if(NOT _minecraft_java_result STREQUAL "0"
   OR NOT _minecraft_java_version MATCHES "^javac 21([.+-]|[ \r\n]|$)"
   OR NOT EXISTS "${_minecraft_java_home}/bin/java${_minecraft_executable_suffix}")
  message(FATAL_ERROR "Minecraft requires JDK 21. Set ATLASNET_MINECRAFT_JAVA_HOME to its installation directory.")
endif()

if(ATLASNET_MINECRAFT_SHARD_MODULE)
  add_subdirectory(AtlasNet EXCLUDE_FROM_ALL)
  # Include just this heuristic when AtlasNet's other examples are disabled.
  if(NOT TARGET AtlasNetXAxisSplit)
    add_subdirectory(AtlasNet/Examples/Heuristics/XAxisSplit
                     AtlasNet/Examples/Heuristics/XAxisSplit EXCLUDE_FROM_ALL)
  endif()
  add_subdirectory(native EXCLUDE_FROM_ALL)
endif()

function(atlasnet_minecraft_gradle_target target task)
  set(client_environment)
  if(target STREQUAL "RunClient" AND TARGET AtlasNetMinecraftClient)
    list(APPEND client_environment "ATLASNET_MINECRAFT_CLIENT_LIBRARY=$<TARGET_FILE:AtlasNetMinecraftClient>")
  endif()
  add_custom_target("${target}"
    COMMAND "${CMAKE_COMMAND}" -E env "JAVA_HOME=${_minecraft_java_home}"
      ${client_environment}
      "${_minecraft_wrapper}" --console=plain "${task}" ${ARGN}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    USES_TERMINAL
    VERBATIM
  )
endfunction()

atlasnet_minecraft_gradle_target(minecraft-build :fabric:build)
atlasnet_minecraft_gradle_target(RunClient :fabric:runClient)
atlasnet_minecraft_gradle_target(RunServer :fabric:runServer)

add_custom_target(BuildClient DEPENDS minecraft-build)
atlasnet_minecraft_gradle_target(BuildServer :fabric:build :fabric:prepareShardServer)
add_dependencies(RunClient BuildClient)
add_dependencies(RunServer BuildServer)

add_custom_target(minecraft-client DEPENDS RunClient)
add_custom_target(minecraft-server DEPENDS RunServer)

if(ATLASNET_MINECRAFT_SHARD_MODULE)
  add_dependencies(BuildServer AtlasNetMinecraftShard AtlasNetNode AtlasNetShardWorker
                   AtlasNetSteamNetSock AtlasNetXAxisSplit Valkey-Server-Compile)
endif()

if(TARGET AtlasNetMinecraftClient)
  add_dependencies(BuildClient AtlasNetMinecraftClient)
endif()
