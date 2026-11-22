plugins {
    `java-library`
}

dependencies {
    api(project(":common:core"))
}

base {
    archivesName.set("atlasnet-minecraft-mod-common")
}
