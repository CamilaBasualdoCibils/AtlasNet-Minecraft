cmake_minimum_required(VERSION 3.25)

option(ATLASNET_EXAMPLES "Build AtlasNet examples" OFF)
option(ATLASNET_TESTS "Build AtlasNet tests" OFF)
option(ATLASNET_DEV "Build AtlasNet development programs" OFF)
option(ATLASNET_STEAMNETSOCK "Build SteamNetworkingSockets support" OFF)
option(ATLASNET_JAVABINDINGS "Build AtlasNet Java bindings" ON)
option(ATLASNET_MINECRAFT_HOST "Build the Linux AtlasNet module that hosts the Minecraft JVM" ON)

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/AtlasNet/CMakeLists.txt")
  message(FATAL_ERROR "Initialize the AtlasNet submodule: git submodule update --init --recursive")
endif()
if(NOT ATLASNET_JAVABINDINGS)
  message(FATAL_ERROR "The Minecraft CMake build requires ATLASNET_JAVABINDINGS=ON")
endif()

# vcpkg resolves features when the top-level project initializes its toolchain.
set(VCPKG_MANIFEST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/AtlasNet" CACHE PATH "AtlasNet dependency manifest")
if(ATLASNET_EXAMPLES)
  list(APPEND VCPKG_MANIFEST_FEATURES examples)
endif()
if(ATLASNET_TESTS)
  list(APPEND VCPKG_MANIFEST_FEATURES testing)
endif()
if(ATLASNET_STEAMNETSOCK)
  list(APPEND VCPKG_MANIFEST_FEATURES steamnetsock)
endif()
list(REMOVE_DUPLICATES VCPKG_MANIFEST_FEATURES)
project(AtlasNetMinecraft LANGUAGES C CXX)

if(NOT JAVA_HOME AND "$ENV{JAVA_HOME}" STREQUAL "" AND CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
  file(GLOB minecraft_jdk_candidates LIST_DIRECTORIES true
    "${CMAKE_CURRENT_SOURCE_DIR}/.tools/jdk-25*"
    "/usr/lib/jvm/java-25*" "/usr/lib/jvm/jdk-25*")
  foreach(jdk IN LISTS minecraft_jdk_candidates)
    if(EXISTS "${jdk}/bin/javac")
      set(JAVA_HOME "${jdk}")
      break()
    endif()
  endforeach()
endif()
find_package(Java 25 REQUIRED COMPONENTS Development)
file(REAL_PATH "${Java_JAVAC_EXECUTABLE}" minecraft_javac)
get_filename_component(minecraft_jdk_bin "${minecraft_javac}" DIRECTORY)
get_filename_component(minecraft_java_home "${minecraft_jdk_bin}" DIRECTORY)
set(JAVA_HOME "${minecraft_java_home}")

# IDE cache resets must be able to rediscover provisioned workspace tools.
set(minecraft_build_tools "${CMAKE_CURRENT_SOURCE_DIR}/.tools/minecraft-build-deps")
find_program(ATLASNET_G_IR_SCANNER NAMES g-ir-scanner
  HINTS "${minecraft_build_tools}/introspection/usr/bin")
find_program(ATLASNET_G_IR_COMPILER NAMES g-ir-compiler
  HINTS "${minecraft_build_tools}/introspection/usr/bin")
find_program(ATLASNET_JAVA_GI_EXECUTABLE NAMES java-gi
  HINTS "${minecraft_build_tools}/java-gi-1.0.0-RC3/bin")
set(minecraft_glib_jar "${minecraft_build_tools}/glib-1.0.0-RC3.jar")
set(minecraft_jspecify_jar "${minecraft_build_tools}/jspecify-1.0.1.jar")
if(NOT ATLASNET_JAVA_RUNTIME_JARS AND EXISTS "${minecraft_glib_jar}" AND EXISTS "${minecraft_jspecify_jar}")
  set(ATLASNET_JAVA_RUNTIME_JARS "${minecraft_glib_jar};${minecraft_jspecify_jar}" CACHE STRING
    "Semicolon-separated preprovisioned Java-GI runtime jars" FORCE)
endif()

add_subdirectory(AtlasNet EXCLUDE_FROM_ALL)
get_target_property(atlasnet_java_dir AtlasNetJava BINARY_DIR)
set(atlasnet_gradle_properties "-PatlasnetJavaSdkJar=${atlasnet_java_dir}/atlasnet-1.0.jar")
foreach(jar IN LISTS ATLASNET_JAVA_RUNTIME_JARS)
  get_filename_component(jar_name "${jar}" NAME)
  if(jar_name MATCHES "^glib-")
    list(APPEND atlasnet_gradle_properties "-PatlasnetJavaGiGlibJar=${jar}")
  elseif(jar_name MATCHES "^jspecify-")
    list(APPEND atlasnet_gradle_properties "-PatlasnetJavaJSpecifyJar=${jar}")
  endif()
endforeach()

if(CMAKE_HOST_WIN32)
  set(gradle_wrapper cmd /d /c gradlew.bat --console=plain)
else()
  set(gradle_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/gradlew" --console=plain)
endif()

function(add_gradle_target target)
  cmake_parse_arguments(PARSE_ARGV 1 gradle "ALL;JVM25" "" "")
  if(gradle_JVM25)
    if(NOT CMAKE_HOST_WIN32)
      set(gradle_wrapper "${CMAKE_CURRENT_SOURCE_DIR}/scripts/minecraft-build.sh" gradle)
    endif()
    set(gradle_environment "${CMAKE_COMMAND}" -E env "JAVA_HOME=${minecraft_java_home}")
    set(gradle_properties ${atlasnet_gradle_properties})
  endif()
  if(gradle_ALL)
    set(include_in_default ALL)
  endif()
  add_custom_target("${target}" ${include_in_default}
    COMMAND ${gradle_environment} ${gradle_wrapper} ${gradle_properties} ${gradle_UNPARSED_ARGUMENTS}
    WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
    USES_TERMINAL
    VERBATIM)
  if(gradle_JVM25)
    add_dependencies("${target}" AtlasNetJava)
  endif()
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

if(ATLASNET_MINECRAFT_HOST)
  add_gradle_target(minecraft-host-classpath JVM25 :atlasnet-plugin:prepareMinecraftHost)
  add_subdirectory(host)
  if(NOT TARGET AtlasNetXAxisSplit)
    add_subdirectory(AtlasNet/Examples/Heuristics/XAxisSplit
                     AtlasNet/Examples/Heuristics/XAxisSplit EXCLUDE_FROM_ALL)
    target_compile_features(AtlasNetXAxisSplit PRIVATE cxx_std_23)
  endif()
  add_custom_target(atlasnet-minecraft-stack ALL DEPENDS
    atlasnet-minecraft-modules AtlasNetNode AtlasNetShardWorker
    AtlasNetXAxisSplit Valkey-Server-Compile)
endif()
