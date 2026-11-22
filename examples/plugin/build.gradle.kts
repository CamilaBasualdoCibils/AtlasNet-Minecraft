plugins {
    java
}

dependencies {
    implementation(project(":atlasnet-plugin"))
}

tasks.jar {
    archiveBaseName.set("atlasnet-plugin-example")
}
