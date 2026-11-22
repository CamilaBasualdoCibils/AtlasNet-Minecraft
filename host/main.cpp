#include <cstdio>
#include <string>
#include <vector>
#include <unistd.h>

int main(int argc, char** argv)
{
  std::string node = ATLASNET_MINECRAFT_NODE;
  std::string option = "--module";
  std::string module = ATLASNET_MINECRAFT_MODULE;
  std::vector<char*> args{node.data(), option.data(), module.data()};
  args.insert(args.end(), argv + 1, argv + argc);
  args.push_back(nullptr);
  execv(node.c_str(), args.data());
  std::perror("Could not launch the AtlasNet Minecraft Node");
  return 1;
}
