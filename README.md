# Nostr Game Engine platform libraries

Platform specific code used by the Nostr Game Engine and its libraries.


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
