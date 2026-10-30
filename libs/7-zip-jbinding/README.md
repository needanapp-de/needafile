# 7-Zip-JBinding, built from source

needafile creates and extracts 7z (and extracts tar, gz, bz2, rar, zip …) with
[7-Zip-JBinding-4Android](https://github.com/omicronapps/7-Zip-JBinding-4Android), the Android port of
[7-Zip-JBinding](https://github.com/borisbrodski/sevenzipjbinding) with the 7-Zip engine p7zip 16.02. Instead of the
prebuilt library from JitPack it is built here from source as the Gradle module `:sevenzipjbinding`.

| | |
|---|---|
| Source | `https://github.com/omicronapps/7-Zip-JBinding-4Android`, tag `Release-16.02-2.03` (commit `875f38a`) |
| License | LGPL-2.1 (`LICENSE`, `src/main/cpp/LGPL`); 7-Zip: GNU LGPL + unRAR restriction (`src/main/cpp/p7zip/DOC`) |
| Build | CMake 3.22.1, NDK 28.2.13676358 (versions in `gradle/libs.versions.toml`), 4 ABIs, 16 KB pages |

## Taken over

From the module `sevenzipjbinding/`: `CMakeLists.txt`, `src/main/AndroidManifest.xml`, `src/main/assets`,
`src/main/java`, `src/main/cpp/jbinding-cpp`, the license and info files of `src/main/cpp`, and of `src/main/cpp/p7zip`
only `C`, `CPP` (without `CPP/ANDROID`, p7zip's own Android makefiles), `DOC`, `README` and `ChangeLog`. Left out: the
unused copy of the official 7-Zip sources (`src/main/cpp/7zip`), tests, documentation, scripts and p7zip's makefiles,
GUI and tools. The repository's `README.md` is `README.upstream.md` here.

## needafile's changes (`needafile.patch`)

- **All archive settings in one call.** 7-Zip resets every property at the start of each `SetProperties` call
  (`InitProps()` in `7zHandlerOut.cpp`, `ZipHandlerOut.cpp`, `HandlerOut.cpp`), but the library set level, header
  encryption, threads and solid mode in separate calls, so only the last one took effect. A 7z archive with thread count
  set after header encryption kept its file names readable. `OutArchiveImpl.applyFeatures()` now collects the features
  (`collectFeatures`, level first) and hands them over with the one native method `nativeSetProperties`, which replaces
  `nativeSetLevel`, `nativeSetHeaderEncryption`, `nativeSetSolidSpec` and `nativeSetMultithreading`. The meaning of the
  settings is unchanged (e.g. thread count 0 = all processors, not set = 7-Zip's default). Same idea as
  [porum's fix](https://github.com/porum/7-Zip-JBinding-4Android/commit/f72d023), written anew.
- **Native code always like a release build** (`-DNDEBUG`): with `_DEBUG` the library turns on tracing and its native
  JUnit support, which looks up test classes when it loads and aborts the app without them.
- The C tests (`src/main/cpp/test`) aren't taken over, so CMake no longer adds them to debug builds.

## Updating

Check out the new release of the source repository, copy the parts listed above over this folder, apply the patch
with `patch -p2 -d libs/7-zip-jbinding < libs/7-zip-jbinding/needafile.patch` (or by hand where it doesn't fit), build,
and test creating 7z archives with level, solid mode and encrypted file names (`7z l -slt` shows method and solid mode;
`7z l` without password must fail for encrypted names).
