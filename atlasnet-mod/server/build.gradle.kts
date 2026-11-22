plugins {
    `java-library`
}

dependencies {
    api(project(":atlasnet-plugin"))
    api(project(":common:mod"))
}

base {
    archivesName.set("atlasnet-mod-server")
}
