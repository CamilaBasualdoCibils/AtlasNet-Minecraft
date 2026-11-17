cmake_minimum_required(VERSION 3.20)

if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
  project(AtlasNetMinecraft LANGUAGES NONE)
endif()

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

function(atlasnet_minecraft_gradle_target target task)
  add_custom_target("${target}"
    COMMAND "${CMAKE_COMMAND}" -E env "JAVA_HOME=${_minecraft_java_home}"
      "ATLASNET_MINECRAFT_CLIENT_LIBRARY=${CMAKE_CURRENT_BINARY_DIR}/native/libAtlasNetMinecraftClient.so"
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

option(ATLASNET_MINECRAFT_SHARD_MODULE "Build the native AtlasNet Minecraft shard plugin" ON)
if(ATLASNET_MINECRAFT_SHARD_MODULE)
  add_subdirectory(native EXCLUDE_FROM_ALL)
  add_dependencies(BuildServer AtlasNetMinecraftShard)
endif()

if(TARGET AtlasNetMinecraftClient)
  add_dependencies(BuildClient AtlasNetMinecraftClient)
endif()
