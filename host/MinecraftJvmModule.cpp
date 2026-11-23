#include "adapter.hpp"

#include <jni.h>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace
{
bool IsWorker()
{
  return std::filesystem::read_symlink("/proc/self/exe").filename() == "AtlasNetShardWorker";
}

JNIEnv* GetEnvironment()
{
  JavaVM* vm = nullptr;
  jsize count = 0;
  if (JNI_GetCreatedJavaVMs(&vm, 1, &count) != JNI_OK)
    throw std::runtime_error("Could not inspect the Minecraft JVM");
  void* rawEnv = nullptr;
  if (count != 0)
  {
    if (vm->GetEnv(&rawEnv, JNI_VERSION_1_8) != JNI_OK)
      throw std::runtime_error("AtlasNet module registration must run on the JVM startup thread");
    return static_cast<JNIEnv*>(rawEnv);
  }
  std::ifstream file(ATLASNET_MINECRAFT_CLASSPATH_FILE);
  std::string classpath;
  if (!std::getline(file, classpath) || classpath.empty())
    throw std::runtime_error("Build the Minecraft JVM host classpath before launching the Node");

  std::string classpathOption = "-Djava.class.path=" + classpath;
  std::string libraryOption = "-Djava.library.path=" ATLASNET_MINECRAFT_NATIVE_LIB_DIR;
  std::string heapOption = "-Xmx2G";
  std::string nativeAccess = "--enable-native-access=ALL-UNNAMED";
  // Keep SIGINT and SIGTERM under the AtlasNet Node and worker lifecycle.
  std::string reduceSignals = "-Xrs";
  std::string terrainAgent;
  std::vector<JavaVMOption> options{{classpathOption.data()}, {libraryOption.data()}, {heapOption.data()},
                                    {nativeAccess.data()}, {reduceSignals.data()}};
  if (IsWorker())
  {
    const auto agentPath = std::filesystem::path(ATLASNET_MINECRAFT_CLASSPATH_FILE)
                               .replace_filename("terrain-agent.jar");
    terrainAgent = "-javaagent:" + agentPath.string();
    options.push_back({terrainAgent.data()});
  }
  JavaVMInitArgs args{JNI_VERSION_1_8, static_cast<jint>(options.size()), options.data(), JNI_FALSE};
  if (JNI_CreateJavaVM(&vm, &rawEnv, &args) != JNI_OK)
    throw std::runtime_error("Could not initialize Minecraft's Java 25 VM");
  return static_cast<JNIEnv*>(rawEnv);
}

void Register(AtlasNet::Module::ModuleRegistry& registry)
{
  if (ATLASNET_MINECRAFT_NODE_ONLY && IsWorker()) return;
  auto* env = GetEnvironment();
  auto* host = env->FindClass("net/atlasnet/minecraft/host/MinecraftModuleHost");
  auto registerMethod = host
      ? env->GetStaticMethodID(host, ATLASNET_MINECRAFT_REGISTER, "(JLjava/lang/String;)V") : nullptr;
  if (!registerMethod)
  {
    env->ExceptionDescribe();
    throw std::runtime_error("Could not find the Minecraft JVM host entry point");
  }

  // Scoped borrowed registry wrapper. The native registry retains Java
  // callbacks registered during this call.
  auto* wrapper = AtlasNet::GObjectIntegration::wrap_registry(registry);
  const char* worldPath = std::getenv("ATLASNET_MINECRAFT_WORLD_DIR");
  auto directory = env->NewStringUTF(worldPath ? worldPath : "minecraft-shard");
  env->CallStaticVoidMethod(host, registerMethod, reinterpret_cast<jlong>(wrapper), directory);
  g_object_unref(wrapper);
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    throw std::runtime_error("Minecraft provider registration failed");
  }
  env->DeleteLocalRef(directory);
  env->DeleteLocalRef(host);
  // AtlasNet may call Java providers until process shutdown. Do not destroy
  // the VM while the staged registry or active endpoint can hold callbacks.
}
}

extern "C" const AtlasNet::Module::ModuleDescriptor* AtlasNetGetModule()
{
  using AtlasNet::Module::Capability;
  static const AtlasNet::Module::ModuleDescriptor descriptor{
      .name = ATLASNET_MINECRAFT_MODULE_NAME,
      .version = "0.1.0",
      .capabilities = ATLASNET_MINECRAFT_NODE_ONLY && IsWorker()
          ? Capability::None : Capability::ATLASNET_MINECRAFT_CAPABILITY,
      .Register = Register};
  return &descriptor;
}
