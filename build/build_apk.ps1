param(
    [switch]$Sign,
    [string]$Ks,           # keystore path (required with -Sign)
    [string]$KsAlias,      # keystore key alias (required with -Sign)
    [string]$KsPass,       # keystore / key password (required with -Sign)
    [string]$JavaHome = $env:JAVA_HOME,  # falls back to javac/jar on PATH if unset
    [string]$Sdk,          # parent dir of build-tools and android.jar; default <repo>/tools/sdk
    [string]$BuildTools = 'android-15',  # build-tools subdir name under tools/sdk
    [string]$Platform  = 'android-36',   # platform subdir (android.jar) name under tools/sdk
    [int]$VersionCode = 2,
    [string]$VersionName = '1.1.0'
)
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Sdk) { $Sdk = Join-Path $root 'tools\sdk' }
$bt   = Join-Path $Sdk $BuildTools
$jar  = Join-Path $Sdk (Join-Path $Platform 'android.jar')
$app  = Join-Path $root 'app'
$out  = Join-Path $root 'build\netkit'
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
}

Write-Host "[0/8] clean"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'classes') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'dex') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'gen') | Out-Null

# [1/8] aapt2 compile: resources -> res.zip
Write-Host "[1/8] aapt2 compile"
$base = Join-Path $out 'base.apk'
$resDir = Join-Path $app 'res'
$resZip = Join-Path $out 'res.zip'
$resArgs = @()
if (Test-Path $resDir) {
    & (Join-Path $bt 'aapt2.exe') compile --dir $resDir -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
    $resArgs = @($resZip)
}

# [2/8] aapt2 link: res.zip + manifest -> base.apk AND generated R.java under out/gen
# NOTE: --java is required; without it javac cannot see R.* symbols.
Write-Host "[2/8] aapt2 link (--java out/gen)"
& (Join-Path $bt 'aapt2.exe') link -o $base -I $jar --manifest (Join-Path $app 'AndroidManifest.xml') -A (Join-Path $app 'assets') --java (Join-Path $out 'gen') --min-sdk-version 26 --target-sdk-version 36 --version-code $VersionCode --version-name $VersionName --auto-add-overlay @resArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# [3/8] javac: app sources + generated R.java
Write-Host "[3/8] javac"
$srcs = @()
$srcs += (Get-ChildItem (Join-Path $app 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$genDir = Join-Path $out 'gen'
if (Test-Path $genDir) {
    $srcs += (Get-ChildItem $genDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
}
if ($srcs.Count -eq 0) { throw "no java sources found" }
& javac -source 8 -target 8 -nowarn -encoding UTF-8 -classpath $jar -d (Join-Path $out 'classes') $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# [4/8] jar
Write-Host "[4/8] jar"
& jar cf (Join-Path $out 'classes.jar') -C (Join-Path $out 'classes') .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

# [5/8] d8
Write-Host "[5/8] d8"
& (Join-Path $bt 'd8.bat') --lib $jar --min-api 26 --output (Join-Path $out 'dex') (Join-Path $out 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# [6/8] add classes.dex into base.apk
Write-Host "[6/8] add classes.dex"
$un = Join-Path $out 'unsigned.apk'
Copy-Item $base $un -Force
& python (Join-Path $PSScriptRoot 'adddex.py') $un (Join-Path $out 'dex\classes.dex')
if ($LASTEXITCODE -ne 0) { throw "adddex failed" }

# [7/8] zipalign
Write-Host "[7/8] zipalign"
$al = Join-Path $out 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -p -f 4 $un $al
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not $Sign) {
    Write-Host "done (unsigned): $al"
    exit 0
}

# [8/8] apksigner
Write-Host "[8/8] apksigner"
if (-not $Ks -or -not $KsAlias -or -not $KsPass) {
    throw "Signing requires -Ks <keystore> -KsAlias <alias> -KsPass <password> (use your own generated key; do not ship any key bundled in the repo)"
}
$signed = Join-Path $out 'NetKit-signed.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $Ks --ks-key-alias $KsAlias --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" --out $signed $al
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& (Join-Path $bt 'apksigner.bat') verify --verbose $signed
Write-Host "done (signed): $signed"
