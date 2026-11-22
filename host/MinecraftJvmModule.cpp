#include "adapter.hpp"

#include <jni.h>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>

namespace
{
bool IsWorker()
{
  return std::filesystem::read_symlink("/proc/self/exe").filename() == "AtlasNetShardWorker";
}

void Register(AtlasNet::Module::ModuleRegistry& registry)
{
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
  JavaVMOption options[] = {{classpathOption.data()}, {libraryOption.data()}, {heapOption.data()},
                            {nativeAccess.data()}, {reduceSignals.data()}};
  JavaVMInitArgs args{JNI_VERSION_1_8, static_cast<jint>(std::size(options)), options, JNI_FALSE};
  JavaVM* vm = nullptr;
  void* rawEnv = nullptr;
  if (JNI_CreateJavaVM(&vm, &rawEnv, &args) != JNI_OK)
    throw std::runtime_error("Could not initialize Minecraft's Java 25 VM");
  auto* env = static_cast<JNIEnv*>(rawEnv);
  auto* host = env->FindClass("net/atlasnet/minecraft/host/MinecraftModuleHost");
  auto registerMethod = host
      ? env->GetStaticMethodID(host, "register", "(JZLjava/lang/String;)V") : nullptr;
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
  env->CallStaticVoidMethod(host, registerMethod, reinterpret_cast<jlong>(wrapper),
                            static_cast<jboolean>(IsWorker()), directory);
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
      .name = "AtlasNetMinecraft",
      .version = "0.1.0",
      .capabilities = IsWorker() ? Capability::ShardProvider
          : Capability::ShardProvider | Capability::ClientEndpoint |
            Capability::ClientAdmissionHandler | Capability::ClientSpawnResolver,
      .Register = Register};
  return &descriptor;
}
