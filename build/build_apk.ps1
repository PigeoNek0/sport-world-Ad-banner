param(
    [switch]$Sign,
    [string]$Ks,           # 签名用 keystore 路径（-Sign 时必填）
    [string]$KsAlias,      # keystore 别名（-Sign 时必填）
    [string]$KsPass,       # keystore / key 口令（-Sign 时必填）
    [string]$JavaHome = $env:JAVA_HOME,  # 不设则用 PATH 上的 javac/jar
    [string]$Sdk,          # build-tools 与 android.jar 的父目录，默认 <repo>/tools/sdk
    [string]$BuildTools = 'android-15',  # tools/sdk 下的 build-tools 子目录名
    [string]$Platform  = 'android-36'    # tools/sdk 下含 android.jar 的子目录名
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

Write-Host "[0/7] clean"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'classes') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'dex') | Out-Null

Write-Host "[1/7] javac"
$srcs = Get-ChildItem (Join-Path $app 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -source 8 -target 8 -nowarn -encoding UTF-8 -classpath $jar -d (Join-Path $out 'classes') $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[2/7] jar"
& jar cf (Join-Path $out 'classes.jar') -C (Join-Path $out 'classes') .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "[3/7] d8"
& (Join-Path $bt 'd8.bat') --lib $jar --min-api 26 --output (Join-Path $out 'dex') (Join-Path $out 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/7] aapt2 link"
$base = Join-Path $out 'base.apk'
$resDir = Join-Path $app 'res'
$resZip = Join-Path $out 'res.zip'
$resArgs = @()
if (Test-Path $resDir) {
    & (Join-Path $bt 'aapt2.exe') compile --dir $resDir -o $resZip
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
    $resArgs = @($resZip)
}
& (Join-Path $bt 'aapt2.exe') link -o $base -I $jar --manifest (Join-Path $app 'AndroidManifest.xml') -A (Join-Path $app 'assets') --min-sdk-version 26 --target-sdk-version 36 --version-code 1 --version-name 1.0.0 --auto-add-overlay @resArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[5/7] add classes.dex"
$un = Join-Path $out 'unsigned.apk'
Copy-Item $base $un -Force
& python (Join-Path $PSScriptRoot 'adddex.py') $un (Join-Path $out 'dex\classes.dex')

Write-Host "[6/7] zipalign"
$al = Join-Path $out 'aligned.apk'
& (Join-Path $bt 'zipalign.exe') -p -f 4 $un $al
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not $Sign) {
    Write-Host "done (unsigned): $al"
    exit 0
}

Write-Host "[7/7] apksigner"
if (-not $Ks -or -not $KsAlias -or -not $KsPass) {
    throw "签名需要 -Ks <keystore> -KsAlias <alias> -KsPass <password>（请用你自己生成的密钥，不要使用任何仓库自带密钥）"
}
$signed = Join-Path $out 'NetKit-signed.apk'
& (Join-Path $bt 'apksigner.bat') sign --ks $Ks --ks-key-alias $KsAlias --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" --v1-signing-enabled true --v2-signing-enabled true --out $signed $al
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& (Join-Path $bt 'apksigner.bat') verify --verbose $signed
Write-Host "done (signed): $signed"
