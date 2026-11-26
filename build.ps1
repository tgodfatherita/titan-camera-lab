param(
    [string]$ReleaseKeystore,
    [string]$KeyAlias = 'titan-camera-lab',
    [Security.SecureString]$StorePassword
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$toolRoot = Join-Path (Split-Path $project) 'tools'
$jdkRoot = $env:JAVA_HOME
if (Test-Path -LiteralPath "$toolRoot\java") {
    $jdkRoot = (Get-ChildItem "$toolRoot\java" -Directory | Select-Object -First 1).FullName
}
if (!$jdkRoot -or !(Test-Path -LiteralPath "$jdkRoot\bin\javac.exe")) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
$env:JAVA_HOME = $jdkRoot
$env:PATH = "$jdkRoot\bin;" + $env:PATH
$sdkRoot = "$toolRoot\android-sdk"
if (!(Test-Path -LiteralPath $sdkRoot)) {
    $sdkRoot = $env:ANDROID_HOME
    if (!$sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
}
if (!$sdkRoot -or !(Test-Path -LiteralPath "$sdkRoot\platforms\android-35\android.jar")) { throw 'Set ANDROID_HOME to the SDK with platform 35 and Build Tools 35.0.0.' }
$buildTools = "$sdkRoot\build-tools\35.0.0"
$androidJar = "$sdkRoot\platforms\android-35\android.jar"
$out = "$project\build"
$finalApk = "$out\TitanCameraLab.apk"
if ($ReleaseKeystore) {
    $ReleaseKeystore = (Resolve-Path -LiteralPath $ReleaseKeystore).Path
    $manifest = [xml](Get-Content -LiteralPath "$project\AndroidManifest.xml" -Raw)
    $version = $manifest.DocumentElement.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
    if ($version -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$') { throw 'Invalid versionName for the release filename.' }
    $dist = New-Item -ItemType Directory -Force -Path "$project\build\release"
    $finalApk = Join-Path $dist.FullName "TitanCameraLab-$version-arm64.apk"
    $out = Join-Path "$project\build\release-work" ([Guid]::NewGuid().ToString('N'))
    if (!$StorePassword) { $StorePassword = Read-Host 'Keystore password' -AsSecureString }
}
$ffmpegPackage = "$toolRoot\ffmpeg-android\package"
$ffmpegJar = "$ffmpegPackage\classes.jar"
$smartJar = "$toolRoot\ffmpeg-android\smart-exception.jar"
$smartCommonJar = "$toolRoot\ffmpeg-android\smart-common.jar"
New-Item -ItemType Directory -Force -Path "$out\classes","$out\dex","$out\tmp" | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=`"$out\tmp`""
function Check { if ($LASTEXITCODE -ne 0) { throw "Build failed: $LASTEXITCODE" } }
& "$buildTools\aapt2.exe" compile --dir "$project\res" -o "$out\resources.zip"
Check
& "$buildTools\aapt2.exe" link -I $androidJar --manifest "$project\AndroidManifest.xml" -A "$project\assets" "$out\resources.zip" -o "$out\unsigned.apk"
Check
$sources = @(Get-ChildItem "$project\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath "$androidJar;$ffmpegJar;$smartJar" -d "$out\classes" @sources
Check
& "$jdkRoot\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
Check
& "$buildTools\d8.bat" --lib $androidJar --min-api 29 --output "$out\dex" "$out\classes.jar" $ffmpegJar $smartJar $smartCommonJar
Check
& "$jdkRoot\bin\jar.exe" uf "$out\unsigned.apk" -C "$out\dex" classes.dex
Check
New-Item -ItemType Directory -Force -Path "$out\native\lib\arm64-v8a" | Out-Null
Copy-Item "$ffmpegPackage\jni\arm64-v8a\*.so" "$out\native\lib\arm64-v8a\" -Force
& "$jdkRoot\bin\jar.exe" uf "$out\unsigned.apk" -C "$out\native" lib
Check
& "$buildTools\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
Check
if ($ReleaseKeystore) {
    $passwordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($StorePassword)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPtr) | & "$buildTools\apksigner.bat" sign --ks $ReleaseKeystore --ks-key-alias $KeyAlias --ks-pass stdin --out "$out\signed.apk" "$out\aligned.apk"
        Check
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPtr)
    }
} else {
    if (!(Test-Path "$out\debug.keystore")) {
        & "$jdkRoot\bin\keytool.exe" -genkeypair -keystore "$out\debug.keystore" -storepass android -keypass android -alias androiddebugkey -dname 'CN=Titan Camera Lab Debug' -keyalg RSA -keysize 2048 -validity 10000
        Check
    }
    & "$buildTools\apksigner.bat" sign --ks "$out\debug.keystore" --ks-pass pass:android --out "$out\signed.apk" "$out\aligned.apk"
    Check
}
& "$buildTools\apksigner.bat" verify --verbose --print-certs "$out\signed.apk"
Check
Copy-Item -LiteralPath "$out\signed.apk" -Destination $finalApk -Force
if (Test-Path -LiteralPath "$out\signed.apk.idsig") {
    Copy-Item -LiteralPath "$out\signed.apk.idsig" -Destination "$finalApk.idsig" -Force
}
Write-Output "APK: $finalApk"
