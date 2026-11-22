plugins {
    java
}

dependencies {
    implementation(project(":atlasnet-mod:server"))
}

base {
    archivesName.set("atlasnet-mod-server-example")
}
