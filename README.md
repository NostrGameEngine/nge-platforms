# Nostr Game Engine platform libraries

Platform specific code used by the Nostr Game Engine and its libraries.


## Usage

```gradle
repositories {
    mavenCentral()
    // Uncomment this if you want to use a -SNAPSHOT version
    //maven { 
    //    url = uri("https://central.sonatype.com/repository/maven-snapshots")
    //}
    maven {
        url = "https://maven.rblb.it/NostrGameEngine/libdatachannel-java"
    }
}

dependencies {
    implementation 'org.ngengine:nge-platform-common:<version>'
    implementation 'org.ngengine:nge-platform-jvm:<version>'
}
```

as `<version>` use one of the versions listed in the [releases page](/releases) or `0.0.0-SNAPSHOT` for the latest snapshot.