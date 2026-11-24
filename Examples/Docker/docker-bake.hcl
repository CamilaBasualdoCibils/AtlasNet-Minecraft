variable "MINECRAFT_RUNTIME_INPUTS" {
  default = ".tools/docker-minecraft-inputs"
}

variable "MINECRAFT_NODE_IMAGE" {
  default = "atlasnet-minecraft-node:latest"
}

variable "ATLASNET_VALKEY_IMAGE" {
  default = "atlasnet-node-valkeymodule:latest"
}

group "default" {
  targets = ["minecraft-node", "valkey"]
}

target "production" {
  context    = "./AtlasNet"
  dockerfile = "dev/docker/Production.DockerFile"
}

target "node-runtime" {
  inherits = ["production"]
  target   = "atlasnet-node"
}

target "native-dependencies" {
  inherits = ["production"]
  target   = "dependencies"
}

target "valkey" {
  inherits = ["production"]
  target   = "atlasnet-node-valkeymodule"
  tags     = [ATLASNET_VALKEY_IMAGE]
}

target "minecraft-node" {
  context    = "."
  dockerfile = "Examples/Docker/Dockerfile"
  contexts = {
    atlasnet-node         = "target:node-runtime"
    atlasnet-dependencies = "target:native-dependencies"
    minecraft-inputs      = MINECRAFT_RUNTIME_INPUTS
  }
  tags = [MINECRAFT_NODE_IMAGE]
}
