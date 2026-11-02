param([string]$ToolRoot="$PSScriptRoot\..\tools",[string]$Device='emulator-5558',[switch]$ReleaseSigning)
$ErrorActionPreference='Stop'
Set-Location "$PSScriptRoot\.."
$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $ToolRoot android-sdk}
$bt="$sdk\build-tools\35.0.0"
$jdk=if(Test-Path (Join-Path $ToolRoot 'jdk-21.0.9+10')){Join-Path $ToolRoot 'jdk-21.0.9+10'}else{$env:JAVA_HOME}
$env:JAVA_HOME=$jdk
function Check {if($LASTEXITCODE -ne 0){throw "Test command failed: $LASTEXITCODE"}}
New-Item -ItemType Directory -Force build\test-classes,build\test-dex | Out-Null
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$sdk\platforms\android-35\android.jar;$bt\core-lambda-stubs.jar" -classpath build\classes -d build\test-classes verification\SmokeInstrumentation.java; Check
& "$jdk\bin\jar.exe" cf build\test.jar -C build\test-classes .; Check
& "$bt\d8.bat" --lib "$sdk\platforms\android-35\android.jar" --classpath build\classes.jar --min-api 29 --output build\test-dex build\test.jar; Check
& "$bt\aapt2.exe" link -o build\test-unsigned.apk -I "$sdk\platforms\android-35\android.jar" --manifest verification\TestManifest.xml; Check
& "$jdk\bin\jar.exe" uf build\test-unsigned.apk -C build\test-dex classes.dex; Check
& "$bt\zipalign.exe" -f 4 build\test-unsigned.apk build\test-aligned.apk; Check
if($ReleaseSigning){. "$PWD\signing\local.ps1";& "$bt\apksigner.bat" sign --ks signing\release.jks --ks-key-alias ndicamera --ks-pass env:NDICAMERA_SIGNING_PASSWORD --out build\test.apk build\test-aligned.apk}else{& "$bt\apksigner.bat" sign --ks signing\debug.jks --ks-key-alias androiddebugkey --ks-pass pass:android --out build\test.apk build\test-aligned.apk}; Check
$adb="$sdk\platform-tools\adb.exe"
& $adb -s $Device install -r build\test.apk; Check
& $adb -s $Device shell am instrument -w ru.ileech.ndicamera.test/ru.ileech.ndicamera.SmokeInstrumentation | Tee-Object verification\smoke-result.txt
Check
if(!(Select-String -LiteralPath verification\smoke-result.txt -Pattern 'verdict=PASS' -Quiet)){throw 'Integration test did not pass'}
foreach($name in @('portrait','landscape')){& $adb -s $Device pull "/sdcard/Android/data/ru.ileech.ndicamera/files/$name.png" "verification/$name.png"; Check}
