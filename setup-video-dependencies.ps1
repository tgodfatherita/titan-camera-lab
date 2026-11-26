$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$dest=Join-Path (Split-Path $PSScriptRoot) 'tools\ffmpeg-android'
New-Item -ItemType Directory -Force $dest | Out-Null
$dependencies=@(
    @{Name='ffmpeg-kit.aar'; Url='https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full-gpl/8.1.7/ffmpeg-kit-full-gpl-8.1.7.aar'; Hash='374D3734755FD4A4F2241DA23B93AB0B3BCAE9BD39F01CF3BF29A5731914658B'},
    @{Name='smart-exception.jar'; Url='https://repo.maven.apache.org/maven2/com/arthenica/smart-exception-java/0.2.1/smart-exception-java-0.2.1.jar'; Hash='5B96AAA5F191DEDBEF72FB0C38F1A2B01807920AFC0D92A75A2ACD6E0CC7703C'},
    @{Name='smart-common.jar'; Url='https://repo.maven.apache.org/maven2/com/arthenica/smart-exception-common/0.2.1/smart-exception-common-0.2.1.jar'; Hash='1CAD0FB4DFA01755A014331B5ED199281D2C3FAB5ACA5C9D7ABD0B41D0EC3F7B'}
)
foreach($dependency in $dependencies){
    $file=Join-Path $dest $dependency.Name
    if(!(Test-Path -LiteralPath $file)){Invoke-WebRequest -Uri $dependency.Url -OutFile $file}
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $dependency.Hash){throw "Checksum non corrispondente: $($dependency.Name)"}
}
Copy-Item "$dest\ffmpeg-kit.aar" "$dest\ffmpeg-kit.zip" -Force
Expand-Archive "$dest\ffmpeg-kit.zip" -DestinationPath "$dest\package" -Force
Write-Output 'Dipendenze video verificate e pronte.'
