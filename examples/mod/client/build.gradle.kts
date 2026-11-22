plugins {
    java
}

dependencies {
    implementation(project(":atlasnet-mod:client"))
}

base {
    archivesName.set("atlasnet-mod-client-example")
}
