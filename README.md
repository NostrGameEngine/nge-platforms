# NGE platforms

### A collection of platform-specific code exposed through a single API

When writing Java code for non-HotSpot runtimes such as Android or TeaVM, some functionality requires alternative implementations, workarounds, or platform-specific bindings. Maintaining separate modules for each target quickly becomes a headache.

This library aims to reduce that complexity by providing a unified abstraction for utilities that would otherwise need to be implemented per platform. Libraries built on top of it can stay platform-agnostic while still supporting different runtimes.

The project is somewhat eclectic and evolves based on real needs rather than a strict roadmap. It is mainly developed in sync with the requirements of [Nostr Game Engine](https://github.com/NostrGameEngine/ngengine) and [Nostr4j](https://github.com/NostrGameEngine/nostr4j), and new stuff is added as needed.

Currently implemented utilities include:

- Websocket client
- WebRTC datachannels
- HTTP client
- Encryption and signing utilities
- Storage utilities (virtual filesystem)
- Safe type conversions
- Memory limits
- Hardened native memory allocator
- Promise-like async tasks
- Async executor aka thread pools
- Concurrent queues and execution queues 
- Clipboard access




## Usage

Add the repositories

```gradle
repositories {
    mavenCentral()
    // Uncomment this if you want to use a -SNAPSHOT version
    //maven { 
    //    url = uri("https://central.sonatype.com/repository/maven-snapshots")
    //}
}
```

Include the common platform library (required for all platforms)

```gradle
dependencies {
    implementation 'org.ngengine:nge-platform-common:<version>'
}
```

Include the platform library specific to your target platform.

```gradle
dependencies {
    // For desktop (LWJGL)
    implementation 'org.ngengine:nge-platform-jvm:<version>'
    // For Android
    // implementation 'org.ngengine:nge-platform-android:<version>'
    // For HTML5 (GWT)
    // implementation 'org.ngengine:nge-platform-teavm:<version>'
}
```

as `<version>` use one of the versions listed in the [releases page](/releases) or `0.0.0-SNAPSHOT` for the latest snapshot.

> [!IMPORTANT]  
> You must include only one of the platform libraries above, depending on your target platform. If you need to target multiple platforms, you must create a gradle submodule for each platform each including one of the platform libraries above.


## Compatibility

| module | platform | jvm |
|--------|----------|---------|
| nge-platform-common | all | java 11+ |
| nge-platform-jvm | desktop | java 21+ |
| nge-platform-android | android | android api 33+ |
| nge-platform-teavm | browser webgl2 | java 21+ / teavm 0.11.0+ |

## Testing

This repo has some unit and integration tests for each platform, and for platform interoperability.


### Prerequisites

- Android SDK + emulator installed (with exported `ANDROID_SDK_ROOT`, `ANDROID_HOME`)
- At least one Android AVD available (defaults to `Generic_AOSP` if present)
- Chrome/Chromium installed for Puppeteer (`CHROME_BIN` can be used to override the path)


### Running tests

Unit tests:

```bash
./gradlew test
```


Interoperability tests:

```bash
./gradlew interopMatrix
```




