# syntax=docker/dockerfile:1@sha256:87999aa3d42bdc6bea60565083ee17e86d1f3339802f543c0d03998580f9cb89
FROM rust:1.95.0-slim-bookworm@sha256:d7482085ff5b415f84dba5647ae71606650bdef00db7aeb69f4b3d170c3e4082

SHELL ["/bin/bash", "-euo", "pipefail", "-c"]

COPY tools/arti-build/TOOLCHAIN.env /tmp/TOOLCHAIN.env

RUN source /tmp/TOOLCHAIN.env \
    && printf '%s\n' \
        'Types: deb' \
        "URIs: https://snapshot.debian.org/archive/debian/${DEBIAN_SNAPSHOT}" \
        'Suites: bookworm bookworm-updates' \
        'Components: main' \
        'Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg' \
        'Check-Valid-Until: no' \
        '' \
        'Types: deb' \
        "URIs: https://snapshot.debian.org/archive/debian-security/${DEBIAN_SNAPSHOT}" \
        'Suites: bookworm-security' \
        'Components: main' \
        'Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg' \
        'Check-Valid-Until: no' \
        > /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        binutils \
        ca-certificates \
        curl \
        git \
        unzip \
    && rm -rf /var/lib/apt/lists/*

RUN source /tmp/TOOLCHAIN.env \
    && curl -fsSL \
        "https://dl.google.com/android/repository/${ANDROID_NDK_ARCHIVE}" \
        -o "/tmp/${ANDROID_NDK_ARCHIVE}" \
    && echo "${ANDROID_NDK_SHA256}  /tmp/${ANDROID_NDK_ARCHIVE}" | sha256sum -c - \
    && unzip -q "/tmp/${ANDROID_NDK_ARCHIVE}" -d /opt \
    && rm "/tmp/${ANDROID_NDK_ARCHIVE}" \
    && test "$(sed -n 's/^Pkg.Revision *= *//p' /opt/android-ndk-r27d/source.properties | tr -d '\r')" = "$ANDROID_NDK_VERSION"

RUN source /tmp/TOOLCHAIN.env \
    && test "$(rustc --version | awk '{print $2}')" = "$RUST_VERSION" \
    && cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked \
    && rustup target add \
        aarch64-linux-android \
        armv7-linux-androideabi \
        i686-linux-android \
        x86_64-linux-android

ENV ANDROID_NDK_HOME=/opt/android-ndk-r27d
ENV PATH=/usr/local/cargo/bin:${PATH}
ENV TZ=UTC
ENV LC_ALL=C.UTF-8

WORKDIR /workspace

ENTRYPOINT ["/bin/bash", "tools/arti-build/build-arti.sh", "--clean"]
