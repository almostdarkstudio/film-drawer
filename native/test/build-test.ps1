# Builds film-app\build\test\filmlut_test.exe: filmlut.c + libjpeg-turbo (plain C) as 32-bit, like the camera.
$ErrorActionPreference = "Stop"
$Native = Split-Path -Parent $PSScriptRoot
$Jt = Join-Path $Native "..\vendor\libjpeg-turbo"
$Out = Join-Path $Native "..\build\test"
$Zig = (Get-ChildItem "$env:LOCALAPPDATA\Programs" -Directory -Filter "zig-x86_64-windows-*" | Sort-Object Name | Select-Object -Last 1).FullName + "\zig.exe"
New-Item -ItemType Directory -Force $Out | Out-Null
$jpeg = "jcapimin jcapistd jccoefct jccolor jcdctmgr jchuff jcicc jcinit jcmainct jcmarker jcmaster jcomapi jcparam " +
        "jcphuff jcprepct jcsample jctrans jdapimin jdapistd jdatadst jdatasrc jdcoefct jdcolor jddctmgr jdhuff jdicc " +
        "jdinput jdmainct jdmarker jdmaster jdmerge jdphuff jdpostct jdsample jdtrans jerror jfdctflt jfdctfst jfdctint " +
        "jidctflt jidctfst jidctint jidctred jquant1 jquant2 jutils jmemmgr jmemnobs jsimd_none"
$srcs = @($jpeg -split ' ' | ForEach-Object { Join-Path $Jt "$_.c" }) + @("$Native\filmlut.c", "$PSScriptRoot\test_main.c")
& $Zig cc -target x86-windows-gnu -O2 -Wall -Wno-unused-but-set-variable -DNO_GETENV -DNO_PUTENV `
    -I "$Native\jpeg-config" -I $Jt -I $Native @srcs -o "$Out\filmlut_test.exe"
if ($LASTEXITCODE -ne 0) { throw "zig cc failed" }
Write-Host "Built $Out\filmlut_test.exe"
