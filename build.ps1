param([string]$ToolRoot="$PSScriptRoot\tools",[switch]$DebugSigning)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if(Test-Path (Join-Path $ToolRoot 'jdk-21.0.9+10')){$env:JAVA_HOME=Join-Path $ToolRoot 'jdk-21.0.9+10'}
if(!$env:JAVA_HOME){throw 'JAVA_HOME is not configured. Run setup.ps1 first.'}
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $ToolRoot 'android-sdk'}
$bt="$sdk\build-tools\35.0.0"
$ndk="$sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin"
$ndi=Join-Path $ToolRoot 'NDI SDK for Android'
function Check { if($LASTEXITCODE -ne 0){throw "Команда завершилась с кодом $LASTEXITCODE"} }
# Always compile clean classes to prevent stale removed classes entering the APK.
if(Test-Path build\classes){Get-ChildItem build\classes -Recurse -File | Remove-Item}
New-Item -ItemType Directory -Force build\generated,build\classes,build\dex,build\package\lib,build\package\assets,dist,signing | Out-Null
foreach($item in @(@('arm64-v8a','aarch64-linux-android29'),@('armeabi-v7a','armv7a-linux-androideabi29'),@('x86_64','x86_64-linux-android29'))){
 $abi=$item[0];New-Item -ItemType Directory -Force "build\package\lib\$abi" | Out-Null
 & "$ndk\clang++.exe" "--target=$($item[1])" -shared -fPIC -O2 -std=c++17 -static-libstdc++ '-Wl,-z,max-page-size=16384' '-Wl,-soname,libndicast.so' "-I$ndi\include" native\ndicast.cpp "-L$ndi\lib\$abi" -lndi -o "build\package\lib\$abi\libndicast.so"; Check
 Copy-Item "$ndi\lib\$abi\libndi.so" "build\package\lib\$abi\libndi.so"
}
Copy-Item "$ndi\NDI SDK License Agreement.txt" build\package\assets\NDI-License.txt
Copy-Item "$ndi\licenses\*" build\package\assets
& "$bt\aapt2.exe" compile --dir res -o build\resources.zip; Check
& "$bt\aapt2.exe" link -o build\unsigned.apk -I "$sdk\platforms\android-35\android.jar" --manifest AndroidManifest.xml --java build\generated build\resources.zip; Check
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$sdk\platforms\android-35\android.jar;$bt\core-lambda-stubs.jar" -d build\classes (Get-ChildItem src,build\generated -Filter *.java -Recurse).FullName; Check
& "$env:JAVA_HOME\bin\jar.exe" cf build\classes.jar -C build\classes .; Check
& "$bt\d8.bat" --lib "$sdk\platforms\android-35\android.jar" --min-api 29 --output build\dex build\classes.jar; Check
Copy-Item build\dex\classes.dex build\package\classes.dex
& "$env:JAVA_HOME\bin\jar.exe" uf build\unsigned.apk -C build\package .; Check
& "$bt\zipalign.exe" -f -P 16 4 build\unsigned.apk build\aligned.apk; Check
if(Test-Path signing\local.ps1){. "$PSScriptRoot\signing\local.ps1"}
if($DebugSigning){$key='signing\debug.jks';$alias='androiddebugkey';$env:NDICAMERA_BUILD_PASSWORD='android'}else{
 $key='signing\release.jks';$alias='ndicamera'
 if(!$env:NDICAMERA_SIGNING_PASSWORD){throw 'Задайте NDICAMERA_SIGNING_PASSWORD (или используйте -DebugSigning для локального теста)'}
 $env:NDICAMERA_BUILD_PASSWORD=$env:NDICAMERA_SIGNING_PASSWORD
}
if(!(Test-Path $key)){
 & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $key -storepass:env NDICAMERA_BUILD_PASSWORD -keypass:env NDICAMERA_BUILD_PASSWORD -alias $alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=NDI Camera, OU=Personal, O=iLeech, C=RU'; Check
}
$suffix=if($DebugSigning){'-debug'}else{''}
$apk="dist\NDICamera-0.1.0$suffix.apk"
& "$bt\apksigner.bat" sign --ks $key --ks-key-alias $alias --ks-pass env:NDICAMERA_BUILD_PASSWORD --out $apk build\aligned.apk; Check
& "$bt\apksigner.bat" verify --verbose $apk; Check
& "$bt\zipalign.exe" -c -P 16 4 $apk; Check
Get-FileHash $apk -Algorithm SHA256 | Format-List
