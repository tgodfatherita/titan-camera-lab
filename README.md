# Titan Camera Lab

A camera app for the **Unihertz Titan 2** by Carmine Petruziello. I built it to get sharper indoor photos and reduce the jolts in walking videos on my Titan. The interface is in Italian. [Guida in italiano](README.it.md) · [☕ Buy me a coffee](https://buymeacoffee.com/tgodfather)

## Download

Open [release v0.16](https://github.com/tgodfatherita/titan-camera-lab/releases/tag/v0.16) on your phone and download **TitanCameraLab-0.16-arm64.apk**. Open the file and follow Android’s install prompt. If Android asks, allow the browser or file manager you used to install this app. The release also includes a SHA-256 checksum.

## Taking photos and videos

- Tap a subject in **Foto** to focus with the rear camera. On the fixed-focus front camera, a tap selects where to meter the light. Slide the sun on the right to adjust brightness in **Foto** or **Video**.
- **Bilanciata** can compare a short burst and save the most detailed shot in low light. **Rapida** favors a short exposure. **Automatica** takes a single exposure.
- Press the physical **space bar** or hold the shutter to choose a **2, 5 or 10 second** self-timer. Tapping a duration starts the countdown.
- In **Video**, **Giroscopio** uses motion recorded during the take to stabilize the saved video. **Marcata** estimates motion from the image. Processing runs on the phone after recording. The gallery receives one stabilized copy.
- Switch between front and rear cameras, choose a format, adjust zoom, or use the rear flash and video torch. The app remembers your settings.

The app uses the camera and microphone for capture. The gallery folder is `DCIM/TitanCameraLab`. The app has no Internet permission. Version 0.16 was tested on a Titan 2 running Android 16. The APK is for 64-bit ARM devices.

## Feedback

Please include your Titan model, Android version and the app version shown at the bottom of **Impostazioni**. For video, mention the resolution and whether you used **Giroscopio** or **Marcata**. [Report an issue](https://github.com/tgodfatherita/titan-camera-lab/issues)

## Build on Windows

Install a JDK 17 and Android SDK Platform 35 with Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME` to those installations, then run the following from this directory in PowerShell.

```powershell
.\setup-video-dependencies.ps1
.\build.ps1
```

The setup script downloads the pinned Maven artifacts into `../tools/ffmpeg-android` and checks their SHA-256 digests. The build produces a locally signed test APK in `build/TitanCameraLab.apk`. For a release using your own PKCS#12 keystore, run the command below and enter its password at the local prompt. Use your key alias in place of `my-key`.

```powershell
.\build.ps1 -ReleaseKeystore 'D:\private\camera.p12' -KeyAlias 'my-key'
```

## License

Copyright 2026 Carmine Petruziello. Titan Camera Lab is released under the GNU General Public License version 3 only. Bundled libraries retain their own licenses. The release includes the third-party source archive and the APK contains copies of the license notices. [App license](LICENSE) · [Third-party sources and licenses](THIRD-PARTY.md)

