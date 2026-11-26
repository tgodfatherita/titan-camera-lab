# Third-party software in Titan Camera Lab

Titan Camera Lab is licensed under GPL-3.0-only, copyright 2026 Carmine Petruziello. The following components have their own copyright holders and licenses. Copies are shipped in the APK under `assets/licenses` and in this repository. [License files and source index](assets/licenses/)

## FFmpegKit and FFmpeg

The app uses `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7` and ships its arm64-v8a libraries. The upstream AAR has SHA-256 `374d3734755fd4a4f2241da23b93ab0b3bcae9bd39f01cf3bf29a5731914658b`. It includes FFmpeg n8.1.2 with GPL components including vid.stab and x264. The GPLv3 text and all 38 component notices from the distributed AAR are included unchanged. [Upstream binary release](https://github.com/ffmpegkit-maintained/ffmpeg/releases/tag/v8.1.7-lts-android) · [Full GPL build recipe](https://github.com/ffmpegkit-maintained/ffmpeg/blob/f81a7d4fd8344eb819aa55f54168402ff83a9280/.github/workflows/build-81-free-full-gpl.yml) · [Packaged license texts](assets/licenses/ffmpegkit/)

The release carries the app source, upstream wrapper, dependency source archives and their checksums. The wrapper archive contains the source revisions, patches and build scripts for the `android-8.1-lts` directory. The app build downloads the verified AAR and includes the corresponding Java classes and arm64 libraries. [Download the release with its sources](https://github.com/tgodfatherita/titan-camera-lab/releases/tag/v0.16) · [Third-party source index](assets/licenses/SOURCES.txt)

## Smart Exception

The `smart-exception-java` and `smart-exception-common` modules are from version 0.2.1 by Taner Sener. Both are BSD-3-Clause. The setup script verifies each Maven artifact. [Source at v0.2.1](https://github.com/tanersener/smart-exception/tree/e77d0004790d39e5e57888ae8e7292f85711d12f) · [License](assets/licenses/Smart-Exception-BSD.txt)

## Android C++ runtime

The native payload includes `libc++_shared.so` from Android NDK r27c. LLVM's Apache 2.0 license with LLVM exceptions and the additional notices are bundled with the APK. [libc++ source](https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262/libcxx/) · [License](assets/licenses/LLVM-libcxx.txt)
