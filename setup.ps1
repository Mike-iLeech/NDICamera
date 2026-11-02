$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force tools | Out-Null
function Fetch($url,$file){& curl.exe -fL --retry 3 -o $file $url;if($LASTEXITCODE -ne 0){throw "Не удалось скачать $url"}}
if(!$env:JAVA_HOME){
 if(!(Test-Path tools\jdk-21.0.9+10)){Fetch 'https://aka.ms/download-jdk/microsoft-jdk-21.0.9-windows-x64.zip' 'tools/jdk.zip';tar -xf tools/jdk.zip -C tools}
 $env:JAVA_HOME="$PWD\tools\jdk-21.0.9+10"
}
if(!$env:ANDROID_HOME){$env:ANDROID_HOME="$PWD\tools\android-sdk"}
if(!(Test-Path "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat")){
 Fetch 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' 'tools/cmdtools.zip'
 New-Item -ItemType Directory -Force tools\sdk-unpack,"$env:ANDROID_HOME\cmdline-tools" | Out-Null
 tar -xf tools/cmdtools.zip -C tools/sdk-unpack
 Copy-Item tools\sdk-unpack\cmdline-tools "$env:ANDROID_HOME\cmdline-tools\latest" -Recurse -Force
}
$env:ANDROID_USER_HOME="$PWD\tools\android-user"
1..100 | ForEach-Object {'y'} | & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358'
if($LASTEXITCODE -ne 0){throw 'Не удалось установить Android SDK/NDK'}
if(!(Test-Path 'tools/NDI SDK for Android/include/Processing.NDI.Lib.h')){
 Fetch 'https://downloads.ndi.tv/SDK/NDI_SDK_Android/Install_NDI_SDK_v6_Android.tar.gz' 'tools/ndi.tar.gz'
 tar -xf tools/ndi.tar.gz -C tools
 $bytes=[IO.File]::ReadAllBytes("$PWD\tools\Install_NDI_SDK_v6_Android.sh")
 $offset=0;while($offset -lt $bytes.Length-2 -and -not($bytes[$offset]-eq31 -and $bytes[$offset+1]-eq139 -and $bytes[$offset+2]-eq8)){$offset++}
 if($offset -ge $bytes.Length-2){throw 'Не найден архив Android NDI SDK'}
 [IO.File]::WriteAllBytes("$PWD\tools\payload.tar.gz",$bytes[$offset..($bytes.Length-1)])
 tar -xf tools/payload.tar.gz -C tools
}
Write-Host 'SDK готовы. Применяются условия лицензий Android SDK и NDI SDK в tools/.'
