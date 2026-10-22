# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89
FROM eclipse-temurin:21.0.11_10-jdk-noble@sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

COPY tools/reproducible-builds/TOOLCHAIN.env /tmp/TOOLCHAIN.env
COPY tools/reproducible-builds/sdk-metadata /tmp/sdk-metadata

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk

RUN source /tmp/TOOLCHAIN.env \
    && mkdir -p "$ANDROID_HOME/build-tools" "$ANDROID_HOME/licenses" "$ANDROID_HOME/platforms" /tmp/android-packages \
    && printf '%s\n' "$ANDROID_SDK_LICENSE_SHA1" > "$ANDROID_HOME/licenses/android-sdk-license" \
    && curl -fsSL \
        "https://dl.google.com/android/repository/${ANDROID_BUILD_TOOLS_ARCHIVE}" \
        -o "/tmp/${ANDROID_BUILD_TOOLS_ARCHIVE}" \
    && echo "${ANDROID_BUILD_TOOLS_SHA256}  /tmp/${ANDROID_BUILD_TOOLS_ARCHIVE}" | sha256sum -c - \
    && mkdir -p /tmp/android-packages/build-tools \
    && cd /tmp/android-packages/build-tools \
    && jar xf "/tmp/${ANDROID_BUILD_TOOLS_ARCHIVE}" \
    && mv "/tmp/android-packages/build-tools/android-${ANDROID_PLATFORM_VERSION}" \
        "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}" \
    && cp /tmp/sdk-metadata/build-tools-37.0.0.xml \
        "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/package.xml" \
    && find "$ANDROID_HOME/build-tools/${ANDROID_BUILD_TOOLS_VERSION}" \
        -maxdepth 1 -type f -exec chmod 755 {} + \
    && curl -fsSL \
        "https://dl.google.com/android/repository/${ANDROID_PLATFORM_ARCHIVE}" \
        -o "/tmp/${ANDROID_PLATFORM_ARCHIVE}" \
    && echo "${ANDROID_PLATFORM_SHA256}  /tmp/${ANDROID_PLATFORM_ARCHIVE}" | sha256sum -c - \
    && cd "$ANDROID_HOME/platforms" \
    && jar xf "/tmp/${ANDROID_PLATFORM_ARCHIVE}" \
    && cp /tmp/sdk-metadata/platform-37.0.xml \
        "$ANDROID_HOME/platforms/android-${ANDROID_PLATFORM_VERSION}/package.xml" \
    && test -f "$ANDROID_HOME/platforms/android-${ANDROID_PLATFORM_VERSION}/android.jar" \
    && curl -fsSL \
        "https://dl.google.com/android/repository/${ANDROID_PLATFORM_TOOLS_ARCHIVE}" \
        -o "/tmp/${ANDROID_PLATFORM_TOOLS_ARCHIVE}" \
    && echo "${ANDROID_PLATFORM_TOOLS_SHA256}  /tmp/${ANDROID_PLATFORM_TOOLS_ARCHIVE}" | sha256sum -c - \
    && cd "$ANDROID_HOME" \
    && jar xf "/tmp/${ANDROID_PLATFORM_TOOLS_ARCHIVE}" \
    && cp /tmp/sdk-metadata/platform-tools-37.0.0.xml \
        "$ANDROID_HOME/platform-tools/package.xml" \
    && find "$ANDROID_HOME/platform-tools" -maxdepth 1 -type f -exec chmod 755 {} + \
    && rm -rf /tmp/android-packages \
        "/tmp/${ANDROID_BUILD_TOOLS_ARCHIVE}" \
        "/tmp/${ANDROID_PLATFORM_ARCHIVE}" \
        "/tmp/${ANDROID_PLATFORM_TOOLS_ARCHIVE}"

ENV PATH=/opt/android-sdk/build-tools/37.0.0:${PATH}

ENV TZ=UTC
ENV LC_ALL=C.UTF-8

WORKDIR /workspace

ENTRYPOINT ["/bin/bash", "tools/reproducible-builds/build-release.sh"]
