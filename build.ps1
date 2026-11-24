# Builds a camera app (Android 2.3 / API 10) with the SDK command-line tools, no Gradle.
# Usage: powershell -ExecutionPolicy Bypass -File film-app\build.ps1 hello [-Release]
#   -Release signs with the release key in %USERPROFILE%\.filmlut-release (created on first use, with a
#   random password in pass.txt next to it). BACK THAT FOLDER UP: updates must be signed with the same key.
param([Parameter(Mandatory = $true, Position = 0)][string]$App, [switch]$Release)
$ErrorActionPreference = "Stop"

$Here  = $PSScriptRoot
$Proj  = Join-Path $Here $App
$Out   = Join-Path $Here "build\$App"
$Sdk   = "$env:LOCALAPPDATA\Android\Sdk"
$Bt    = "$Sdk\build-tools\35.0.1"
$Jar   = "$Sdk\platforms\android-10\android.jar"
$env:JAVA_HOME = (Get-ChildItem "$env:LOCALAPPDATA\Programs" -Directory -Filter "jdk-17*" | Sort-Object Name | Select-Object -Last 1).FullName
$Jdk   = "$env:JAVA_HOME\bin"
$Py    = Join-Path (Split-Path -Parent $Here) ".venv\Scripts\python.exe"
$Key   = Join-Path $Here "keys\filmlut-debug.jks"

function Run($exe) { & $exe @args; if ($LASTEXITCODE -ne 0) { throw "failed: $exe $args" } }

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force "$Out\classes", "$Out\dex" | Out-Null

# 0. Native code (if the app has jni/): NDK r15c, the last NDK with Android 2.3 (android-9) headers.
$Ndk = "$Sdk\ndk-r15c"
if (Test-Path "$Proj\jni") {
    $fw = { param($p) $p -replace '\\', '/' }
    # r15c still ships android-9 but defaults its floor to 14; lower it so APP_PLATFORM := android-9 is honoured.
    if (Test-Path "$Out\obj") { Remove-Item -Recurse -Force "$Out\obj" }
    Run "$Ndk\ndk-build.cmd" -s NDK_MIN_PLATFORM_LEVEL=9 "NDK_PROJECT_PATH=$(& $fw $Proj)" "APP_BUILD_SCRIPT=$(& $fw "$Proj\jni\Android.mk")" `
        "NDK_APPLICATION_MK=$(& $fw "$Proj\jni\Application.mk")" "NDK_OUT=$(& $fw "$Out\obj")" `
        "NDK_LIBS_OUT=$(& $fw "$Out\lib")" "FILMLUT_NATIVE=$(& $fw "$Here\native")"

    # Every symbol the .so needs must exist in Android 2.3's system libraries (weak refs are optional).
    $nm = (Get-ChildItem "$Ndk\toolchains\arm-linux-androideabi-4.9\prebuilt\windows-x86_64\bin" -Filter "*-nm.exe")[0].FullName
    $plat = "$Ndk\platforms\android-9\arch-arm\usr\lib"
    $have = @{}
    foreach ($l in "libc.so", "libm.so", "libdl.so", "libstdc++.so") {
        & $nm -D --defined-only "$plat\$l" 2>$null | ForEach-Object { $have[($_ -split '\s+')[-1]] = 1 }
    }
    foreach ($so in Get-ChildItem "$Out\lib" -Recurse -Filter *.so) {
        $missing = & $nm -D --undefined-only $so.FullName | Where-Object { $_ -match '^\s+U ' } |
            ForEach-Object { ($_ -split '\s+')[-1] } | Where-Object { -not $have.ContainsKey($_) }
        if ($missing) { throw "$($so.Directory.Name)/$($so.Name) needs symbols missing on Android 2.3: $($missing -join ', ')" }
        Write-Host "  android-2.3 symbol check OK: $($so.Directory.Name)/$($so.Name)"
    }
}

# 1. Resources + manifest (+ assets) -> unsigned base apk (also generates R.java)
Run "$Bt\aapt2.exe" compile --dir "$Proj\res" -o "$Out\res.zip"
$assets = @(); if (Test-Path "$Proj\assets") { $assets = @("-A", "$Proj\assets") }
Run "$Bt\aapt2.exe" link -I $Jar --manifest "$Proj\AndroidManifest.xml" --min-sdk-version 10 --target-sdk-version 10 `
    --java "$Out\gen" @assets -o "$Out\base.apk" "$Out\res.zip"

# 2. Java -> classes (Java 8 language level, compiled against the API 10 android.jar).
#    stubs/ = compile-only stand-ins for Sony classes; on the classpath, never dexed or packaged.
$cp = @()
if (Test-Path "$Proj\stubs") {
    New-Item -ItemType Directory -Force "$Out\stubs" | Out-Null
    $stubs = @(Get-ChildItem -Recurse "$Proj\stubs" -Filter *.java | ForEach-Object { $_.FullName })
    Run "$Jdk\javac.exe" -source 8 -target 8 -bootclasspath $Jar -encoding UTF-8 -nowarn -d "$Out\stubs" @stubs
    Run "$Jdk\jar.exe" --create --file "$Out\stubs.jar" -C "$Out\stubs" .
    $cp = @("-cp", "$Out\stubs.jar")
}
$srcs = @(Get-ChildItem -Recurse "$Proj\src", "$Out\gen" -Filter *.java | ForEach-Object { $_.FullName })
Run "$Jdk\javac.exe" -source 8 -target 8 -bootclasspath $Jar @cp -encoding UTF-8 "-Xlint:-options" -d "$Out\classes" @srcs

# 3. classes -> classes.dex for Dalvik on Android 2.3
$classes = @(Get-ChildItem -Recurse "$Out\classes" -Filter *.class | ForEach-Object { $_.FullName })
$d8cp = @(); if (Test-Path "$Out\stubs.jar") { $d8cp = @("--classpath", "$Out\stubs.jar") }
Run "$Bt\d8.bat" --release --min-api 10 --lib $Jar @d8cp --output "$Out\dex" @classes

# 4. Add the dex (+ lib/<abi>/*.so), align, sign (v1 JAR signature: the only scheme Android 2.3 checks)
$addPy = @'
import os, sys, zipfile
# Rewrite into a fresh zip (appending to aapt2's output left inconsistent local headers).
src = zipfile.ZipFile(sys.argv[1])
entries = [(i, src.read(i.filename)) for i in src.infolist()]
src.close()
z = zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED)
for i, data in entries:
    z.writestr(i.filename, data, compress_type=i.compress_type)
z.write(sys.argv[2], 'classes.dex')
libs = sys.argv[3]
if os.path.isdir(libs):
    for abi in sorted(os.listdir(libs)):
        for f in sorted(os.listdir(os.path.join(libs, abi))):
            if f.endswith('.so'):
                z.write(os.path.join(libs, abi, f), 'lib/%s/%s' % (abi, f))
                print('  + lib/%s/%s' % (abi, f))
z.close()
'@
Run $Py -I -c $addPy "$Out\base.apk" "$Out\dex\classes.dex" "$Out\lib"
Run "$Bt\zipalign.exe" -f 4 "$Out\base.apk" "$Out\aligned.apk"

if ($Release) {
    $RelDir = Join-Path $env:USERPROFILE ".filmlut-release"
    $Key = Join-Path $RelDir "release.jks"
    $PassFile = Join-Path $RelDir "pass.txt"
    if (-not (Test-Path $Key)) {
        New-Item -ItemType Directory -Force $RelDir | Out-Null
        $bytes = New-Object byte[] 24
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        [IO.File]::WriteAllText($PassFile, [Convert]::ToBase64String($bytes).Replace('/', 'x').Replace('+', 'y'))
        # keytool reports progress on stderr, which PowerShell 5.1 turns into an error under "Stop".
        $ErrorActionPreference = "Continue"
        & "$Jdk\keytool.exe" -genkeypair -keystore $Key -storepass:file $PassFile -keypass:file $PassFile `
            -alias filmlut -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=Film Drawer" 2>&1 | Out-Host
        $kc = $LASTEXITCODE
        $ErrorActionPreference = "Stop"
        if ($kc -ne 0 -or -not (Test-Path $Key)) { throw "keytool failed ($kc)" }
        Write-Host "Created release key $Key - back up $RelDir (needed to sign every future update)."
    }
    $KsPass = "file:$PassFile"
} else {
    if (-not (Test-Path $Key)) {
        New-Item -ItemType Directory -Force (Split-Path $Key) | Out-Null
        Run "$Jdk\keytool.exe" -genkeypair -keystore $Key -storepass filmlut -keypass filmlut -alias filmlut `
            -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Film LUT local build"
    }
    $KsPass = "pass:filmlut"
}
# Release builds go to <app>-release.apk with no install record, so install-built never puts a
# release-signed APK over the debug-signed app on the dev camera (Android refuses a signature change).
$Apk = Join-Path $Here ("build\$App" + $(if ($Release) { "-release" } else { "" }) + ".apk")
Run "$Bt\apksigner.bat" sign --ks $Key --ks-pass $KsPass --ks-key-alias filmlut `
    --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false `
    --out $Apk "$Out\aligned.apk"
Run "$Bt\apksigner.bat" verify --min-sdk-version 10 $Apk

$h = (Get-FileHash -Algorithm SHA256 $Apk).Hash.ToLower()
$pkg = ([xml](Get-Content "$Proj\AndroidManifest.xml" -Raw)).manifest.package
# Build record checked by src\pmca.ps1 install-built (installs only the exact file built here).
# Debug builds only: release builds are for distribution, not for install-built.
if (-not $Release) {
    [ordered]@{ app = $App; package = $pkg; sha256 = $h; built = (Get-Date -Format "yyyy-MM-dd HH:mm:ss") } |
        ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $Here "build\$App.json")
}
Write-Host "Built: $Apk"
Write-Host "Package: $pkg"
Write-Host "SHA-256: $h"
