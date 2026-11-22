plugins {
    `java-library`
}

dependencies {
    api(project(":common:mod"))
}

base {
    archivesName.set("atlasnet-mod-client")
}
