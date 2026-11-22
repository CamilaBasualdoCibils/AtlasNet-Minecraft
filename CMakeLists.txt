cmake_minimum_required(VERSION 3.25)
project(AtlasNetMinecraft LANGUAGES NONE)

if(CMAKE_HOST_WIN32)
  set(gradle_wrapper cmd /d /c gradlew.bat --console=plain)
else()
  set(gradle_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/gradlew" --console=plain)
endif()

function(add_gradle_target target)
  cmake_parse_arguments(PARSE_ARGV 1 gradle "ALL;JVM25" "" "")
  if(gradle_JVM25 AND NOT CMAKE_HOST_WIN32)
    set(gradle_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/scripts/minecraft-build.sh" gradle)
  endif()
  if(gradle_ALL)
    set(include_in_default ALL)
  endif()
  add_custom_target("${target}" ${include_in_default}
    COMMAND ${gradle_wrapper} ${gradle_UNPARSED_ARGUMENTS}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    USES_TERMINAL
    VERBATIM)
endfunction()

add_gradle_target(atlasnet-plugin JVM25 :atlasnet-plugin:build)
add_gradle_target(atlasnet-endpoint JVM25 :atlasnet-endpoint:build)
add_gradle_target(atlasnet-admissionHandler JVM25 :atlasnet-admissionHandler:build)
add_gradle_target(atlasnet-spawnResolver JVM25 :atlasnet-spawnResolver:build)
add_gradle_target(atlasnet-mod-client :atlasnet-mod:client:build)
add_gradle_target(atlasnet-mod-server JVM25 :atlasnet-mod:server:build)
add_gradle_target(example-plugin JVM25 :examples:plugin:build)
add_gradle_target(example-mod-client :examples:mod:client:build)
add_gradle_target(example-mod-server JVM25 :examples:mod:server:build)
add_gradle_target(atlasnet-sdk ALL JVM25 build)

option(ATLASNET_MINECRAFT_HOST "Build the Linux AtlasNet module that hosts the Minecraft JVM" OFF)
if(ATLASNET_MINECRAFT_HOST)
  enable_language(CXX)
  add_gradle_target(minecraft-host-classpath JVM25 :atlasnet-plugin:prepareMinecraftHost)
  add_subdirectory(host)
endif()
