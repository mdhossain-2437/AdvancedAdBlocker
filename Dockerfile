FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
# Install dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    wget \
    unzip \
    git \
    ca-certificates \
    python3 \
    python3-pip \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install Android SDK command-line tools
ENV ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip -O /tmp/cmdline.zip && \
    unzip -q /tmp/cmdline.zip -d /tmp/cmdline && \
    mkdir -p $ANDROID_SDK_ROOT/cmdline-tools/latest && \
    mv /tmp/cmdline/cmdline-tools/* $ANDROID_SDK_ROOT/cmdline-tools/latest/ && \
    rm -rf /tmp/cmdline* 

ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Accept licenses and install required SDKs & NDK
RUN yes | sdkmanager --sdk_root=$ANDROID_SDK_ROOT --licenses || true
RUN sdkmanager --sdk_root=$ANDROID_SDK_ROOT "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;25.2.9519653" "cmake;3.22.1" || true

WORKDIR /workspace
COPY . /workspace

# Install Gradle and build using system Gradle (wrapper not present)
ENV GRADLE_VERSION=8.8
RUN wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt/gradle && \
    ln -sf /opt/gradle/gradle-${GRADLE_VERSION}/bin/gradle /usr/bin/gradle && \
    rm -f /tmp/gradle.zip

# Build debug APK
RUN gradle assembleDebug --no-daemon --stacktrace

# Output location
VOLUME ["/out"]
CMD ["/bin/bash", "-lc", "cp app/build/outputs/apk/debug/*.apk /out || true; echo Built APKs copied to /out"]
