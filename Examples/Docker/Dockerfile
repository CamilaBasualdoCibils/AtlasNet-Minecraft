# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS java

FROM atlasnet-dependencies AS build
ARG BUILD_JOBS=2
RUN apt-get update && apt-get install -y --no-install-recommends \
    gobject-introspection libgirepository1.0-dev libglib2.0-dev openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java /opt/java/openjdk/ /opt/java/openjdk/
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
COPY --from=minecraft-inputs / /minecraft-inputs/
ENV ORG_GRADLE_PROJECT_minecraftAdmissionJar=/minecraft-inputs/minecraft-common.jar \
    ORG_GRADLE_PROJECT_minecraftAdmissionLibraries=/minecraft-inputs/libraries
WORKDIR /minecraft
COPY . .
RUN triplet="$(cat /atlasnet/triplet)" \
    && cp AtlasNet/dev/docker/arm64-linux-gcc.cmake AtlasNet/vcpkg-triplets/ \
    && cmake -S . -B /minecraft/build -G Ninja \
       -DCMAKE_BUILD_TYPE=Release \
       -DCMAKE_TOOLCHAIN_FILE="$VCPKG_ROOT/scripts/buildsystems/vcpkg.cmake" \
       -DVCPKG_OVERLAY_TRIPLETS=/minecraft/AtlasNet/vcpkg-triplets \
       -DVCPKG_TARGET_TRIPLET="$triplet" -DVCPKG_HOST_TRIPLET="$triplet" \
       -DVCPKG_INSTALLED_DIR=/opt/vcpkg-installed -DVCPKG_MANIFEST_INSTALL=OFF \
       -DATLASNET_STEAMNETSOCK=ON \
       -DATLASNET_JAVA_GI_EXECUTABLE=/minecraft-inputs/java-gi/bin/java-gi \
       '-DATLASNET_JAVA_RUNTIME_JARS=/minecraft-inputs/glib-1.0.0-RC3.jar;/minecraft-inputs/jspecify-1.0.1.jar' \
    && cmake --build /minecraft/build --parallel "$BUILD_JOBS" --target \
       atlasnet-minecraft-modules AtlasNetXAxisSplit
RUN set -eu; \
    host=/minecraft/atlasnet-plugin/build/minecraft-host; \
    native=/minecraft/build/AtlasNet/Integrations/GObject; \
    mkdir -p "/runtime$host" "/runtime$native" /runtime/atlasnet /runtime/opt/minecraft; \
    cp "$host/terrain-agent.jar" "/runtime$host/"; \
    cp "$native/libatlasnet-gobject.so" "/runtime$native/"; \
    cp /minecraft/build/host/libatlasnet-minecraft-*.so \
       /minecraft/build/AtlasNet/Examples/Heuristics/XAxisSplit/AtlasNetXAxisSplit.so \
       /runtime/atlasnet/; \
    i=0; \
    { cat "$host/classpath.txt"; printf '\n'; } | tr ':' '\n' | while IFS= read -r entry; do \
        if [ -d "$entry" ] || [ -f "$entry" ]; then \
            i=$((i + 1)); \
            destination="/opt/minecraft/$i/${entry##*/}"; \
            mkdir -p "/runtime/opt/minecraft/$i"; \
            cp -aL "$entry" "/runtime$destination"; \
            if [ "$i" -gt 1 ]; then printf ':'; fi; \
            printf '%s' "$destination"; \
        fi; \
    done > "/runtime$host/classpath.txt"; \
    test -s "/runtime$host/classpath.txt"

FROM atlasnet-node
RUN apt-get update && apt-get install -y --no-install-recommends \
    libglib2.0-0t64 socat tini \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java /opt/java/openjdk/ /opt/java/openjdk/
COPY --from=build /runtime/ /
COPY --chmod=0755 Examples/Docker/start-node.sh /usr/local/bin/start-node
ENV LD_LIBRARY_PATH=/atlasnet:/opt/java/openjdk/lib/server:/minecraft/build/AtlasNet/Integrations/GObject
RUN set -eu; \
    ldd /atlasnet/libatlasnet-minecraft-*.so /atlasnet/AtlasNetXAxisSplit.so \
        /minecraft/build/AtlasNet/Integrations/GObject/libatlasnet-gobject.so > /tmp/module-ldd; \
    if grep -F 'not found' /tmp/module-ldd; then exit 1; fi; \
    rm /tmp/module-ldd
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/start-node"]
