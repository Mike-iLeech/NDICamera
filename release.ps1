param([Parameter(Mandatory)][string]$Tag,[Parameter(Mandatory)][string]$Target,[switch]$Publish)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
[xml]$manifest=Get-Content AndroidManifest.xml
$version=$manifest.manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
$versionCode=[long]$manifest.manifest.GetAttribute('versionCode','http://schemas.android.com/apk/res/android')
if($Tag -ne "v$version"){throw 'Tag must match AndroidManifest versionName'}
$apk="dist/NDICamera-$version.apk"
if(!(Test-Path $apk)){throw 'Build the release-signed APK first'}
$hash=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
@{versionCode=$versionCode;versionName=$version;apkUrl="https://github.com/Mike-iLeech/NDICamera/releases/download/$Tag/NDICamera-$version.apk";sha256=$hash} | ConvertTo-Json | Set-Content dist/update.json -Encoding utf8
Write-Host "Prepared $apk and dist/update.json"
if($Publish){
 & gh release create $Tag -R Mike-iLeech/NDICamera --target $Target --title "NDI Camera $version" --notes-file RELEASE-NOTES.md $apk dist/update.json
 if($LASTEXITCODE -ne 0){throw 'GitHub release failed'}
}
