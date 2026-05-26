# NX Un-Apk

An on-device Android tool that reverses any APK back into a readable, open-source style project. NX Un-Apk decompiles DEX files into Java sources, decodes resources, decodes the binary AndroidManifest, and exposes the original asset tree, all without leaving the device and without freezing it.

## Features

- Reverse any installed or downloaded APK on the device
- Java decompilation through jadx
- Smali fallback when Java decompilation cannot recover a method
- Binary AndroidManifest.xml and resources.arsc decoded into readable XML
- Original assets, native libraries, signature files preserved as-is
- Foreground service so the work survives screen rotations and app switches
- Settings screen with a toggle to allow or block background execution
- Output packaged as a single ZIP archive ready to share or open
- Pure on-device pipeline, no remote servers, no external binaries

## How it works

1. Pick an APK from storage using the system file picker
2. NX Un-Apk copies it to private cache and starts a foreground job
3. The decompiler reads the DEX classes, decodes the resource table, and rewrites every binary XML
4. The reconstructed project is written to the app output directory and zipped
5. A notification and the in-app view link to the result

## Build requirements

- Android Gradle Plugin 8.7.x
- Gradle 8.10.x
- JDK 17
- compileSdk 35, minSdk 21, targetSdk 35

## License

Licensed under the GNU Affero General Public License v3.0. See the [LICENSE](LICENSE) file for the full text.

Any derivative work, including network-deployed copies, must be released under the same license and provide full source code to end users.
