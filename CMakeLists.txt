cmake_minimum_required(VERSION 3.25)
project(AtlasNetMinecraft LANGUAGES NONE)

if(CMAKE_HOST_WIN32)
  set(gradle_wrapper cmd /d /c gradlew.bat)
else()
  set(gradle_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/gradlew")
endif()

function(add_gradle_target target)
  cmake_parse_arguments(PARSE_ARGV 1 gradle "ALL" "" "")
  if(gradle_ALL)
    set(include_in_default ALL)
  endif()
  add_custom_target("${target}" ${include_in_default}
    COMMAND ${gradle_wrapper} --console=plain ${gradle_UNPARSED_ARGUMENTS}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    USES_TERMINAL
    VERBATIM)
endfunction()

add_gradle_target(atlasnet-plugin :atlasnet-plugin:build)
add_gradle_target(atlasnet-endpoint :atlasnet-endpoint:build)
add_gradle_target(atlasnet-admissionHandler :atlasnet-admissionHandler:build)
add_gradle_target(atlasnet-spawnResolver :atlasnet-spawnResolver:build)
add_gradle_target(atlasnet-mod-client :atlasnet-mod:client:build)
add_gradle_target(atlasnet-mod-server :atlasnet-mod:server:build)
add_gradle_target(example-plugin :examples:plugin:build)
add_gradle_target(example-mod-client :examples:mod:client:build)
add_gradle_target(example-mod-server :examples:mod:server:build)
add_gradle_target(atlasnet-sdk ALL build)
