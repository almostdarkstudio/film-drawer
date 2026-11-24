# Builds a public release of Film LUT into film-app\release\FilmLUT-<version>\ :
#   FilmLUT-<version>.apk (release-signed), README.md (user guide), LICENSE, NOTICE,
#   LOOKS-LICENSE.txt, SHA256SUMS.txt and FilmLUT-<version>-source.zip.
# The source zip is built from an EXPLICIT list of files, never by excluding: personal material
# (samples\, luts\report\, luts\out*\ previews, build\ test outputs, keys\) can never slip in.
# Nothing is uploaded anywhere.
# Usage: powershell -ExecutionPolicy Bypass -File film-app\make-release.ps1 [-DonationLink <url>]
param([string]$DonationLink = "")
$ErrorActionPreference = "Stop"
$Here = $PSScriptRoot
$Py = Join-Path $Here ".venv-tools\Scripts\python.exe"

$ver = ([xml](Get-Content "$Here\filmlut\AndroidManifest.xml" -Raw)).manifest.versionName
$name = "FilmLUT-$ver"
$Rel = Join-Path $Here "release\$name"
if (Test-Path $Rel) { Remove-Item -Recurse -Force $Rel }
New-Item -ItemType Directory -Force $Rel | Out-Null

# 1. Release-signed APK (build\filmlut-release.apk; the debug build and its install record are untouched).
& powershell -ExecutionPolicy Bypass -File "$Here\build.ps1" filmlut -Release
if ($LASTEXITCODE -ne 0) { throw "release build failed" }
Copy-Item "$Here\build\filmlut-release.apk" "$Rel\$name.apk"

# 2. Documents.
$guide = Get-Content "$Here\docs\USER-GUIDE.md" -Raw
$guide = $guide.Replace("DONATION_LINK", $(if ($DonationLink) { $DonationLink } else { "(link coming soon)" }))
[IO.File]::WriteAllText("$Rel\README.md", $guide, (New-Object Text.UTF8Encoding $false))
Copy-Item "$Here\LICENSE" "$Rel\LICENSE.txt"
Copy-Item "$Here\NOTICE" "$Rel\NOTICE.txt"
Copy-Item "$Here\filmlut\assets\luts\LICENSE.txt" "$Rel\LOOKS-LICENSE.txt"

# 3. Source zip from an explicit list (relative to film-app\).
$include = @(
    "LICENSE", "NOTICE", "README.md", "build.ps1", "make-release.ps1", "docs\USER-GUIDE.md",
    "filmlut\AndroidManifest.xml", "filmlut\jni\Android.mk", "filmlut\jni\Application.mk",
    "native\filmlut.c", "native\filmlut.h", "native\filmlut_jni.c", "native\libjpeg.mk", "native\app.mk",
    "native\test\test_main.c", "native\test\build-test.ps1",
    "luts\app-looks.txt", "luts\looks.txt", "luts\emulsion-trial.txt", "luts\candidates-rt.txt", "luts\candidates-ep.txt",
    "luts\assemble.py", "luts\convert.py", "luts\xmp2lut.py", "luts\lookreport.py", "luts\looksheet.py",
    "luts\aerochrome.py", "luts\original.txt", "luts\tweaks.txt"
)
$includeDirs = @("filmlut\src", "filmlut\res", "filmlut\stubs", "filmlut\assets", "native\jpeg-config")
$files = New-Object System.Collections.Generic.List[string]
foreach ($f in $include) {
    if (-not (Test-Path "$Here\$f")) { throw "release list: missing $f" }
    $files.Add($f)
}
foreach ($d in $includeDirs) {
    Get-ChildItem "$Here\$d" -Recurse -File | ForEach-Object { $files.Add($_.FullName.Substring($Here.Length + 1)) }
}
# Safety net: refuse photos/raw files and anything from the personal folders.
$bad = $files | Where-Object { $_ -match '\.(jpe?g|arw|ppm|tif+|dng)$' -or $_ -match '^(samples|build|keys|release|hello)\\' -or $_ -match '\\(report|out[^\\]*|download)\\' }
if ($bad) { throw "release list contains personal/build files:`n$($bad -join "`n")" }
$zip = "$Rel\$name-source.zip"
# (single quotes only: PowerShell 5.1 drops double quotes inside native-command arguments)
$zipPy = @'
import os, sys, zipfile
root, out, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
names = sorted(set(l.strip().lstrip('﻿') for l in sys.stdin if l.strip().lstrip('﻿')))   # PS pipes a BOM
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for n in names:
        z.write(os.path.join(root, n), prefix + '/' + n.replace(os.sep, '/'))
print('source zip: %d files' % len(names))
'@
$files | & $Py -I -c $zipPy $Here $zip "film-lut-$ver"
if ($LASTEXITCODE -ne 0) { throw "zip failed" }

# 4. Checksums.
$sums = Get-ChildItem $Rel -File | Where-Object { $_.Name -ne "SHA256SUMS.txt" } | Sort-Object Name |
    ForEach-Object { "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLower(), $_.Name }
[IO.File]::WriteAllText("$Rel\SHA256SUMS.txt", ($sums -join "`n") + "`n", (New-Object Text.UTF8Encoding $false))

Write-Host "`nRelease $name ready in $Rel (nothing uploaded):"
Get-ChildItem $Rel | ForEach-Object { "  {0,-32} {1,10:N0} bytes" -f $_.Name, $_.Length }
Write-Host "Third-party source (not redistributed): libjpeg-turbo 2.1.5.1 -> film-app\vendor\libjpeg-turbo;"
Write-Host "EmulsionPresets @ 4312c97 and the RawTherapee HaldCLUT collection for rebuilding the looks (see README)."
