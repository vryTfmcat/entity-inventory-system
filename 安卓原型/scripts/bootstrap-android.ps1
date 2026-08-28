param(
    [string]$AndroidApi = "35",
    [string]$BuildTools = "35.0.0"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolingRoot = Join-Path $projectRoot ".tooling"
$jdkRoot = Join-Path $toolingRoot "jdk-17"
$sdkRoot = Join-Path $toolingRoot "android-sdk"
$downloads = Join-Path $toolingRoot "downloads"
New-Item -ItemType Directory -Force -Path $downloads, $sdkRoot | Out-Null

function Get-ToolArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    & curl.exe --fail --location --retry 5 --retry-delay 2 --connect-timeout 30 --output $Destination $Uri
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Destination) -or (Get-Item $Destination).Length -eq 0) {
        throw "下载失败或得到空文件：$Uri"
    }
}

if (-not (Test-Path (Join-Path $jdkRoot "bin\java.exe"))) {
    $jdkZip = Join-Path $downloads "jdk17.zip"
    Get-ToolArchive -Uri "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -Destination $jdkZip
    $jdkExtract = Join-Path $toolingRoot "jdk-extract"
    if (Test-Path $jdkExtract) { Remove-Item -LiteralPath $jdkExtract -Recurse -Force }
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkExtract
    $expandedJdk = Get-ChildItem -LiteralPath $jdkExtract -Directory | Select-Object -First 1
    Move-Item -LiteralPath $expandedJdk.FullName -Destination $jdkRoot
    Remove-Item -LiteralPath $jdkExtract -Recurse -Force
}

$env:JAVA_HOME = $jdkRoot
$env:Path = "$(Join-Path $jdkRoot 'bin');$env:Path"

$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkManager)) {
    $toolsZip = Join-Path $downloads "commandlinetools.zip"
    Get-ToolArchive -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -Destination $toolsZip
    $extractRoot = Join-Path $toolingRoot "android-tools-extract"
    if (Test-Path $extractRoot) { Remove-Item -LiteralPath $extractRoot -Recurse -Force }
    Expand-Archive -LiteralPath $toolsZip -DestinationPath $extractRoot
    $latestRoot = Join-Path $sdkRoot "cmdline-tools\latest"
    New-Item -ItemType Directory -Force -Path (Split-Path $latestRoot -Parent) | Out-Null
    Move-Item -LiteralPath (Join-Path $extractRoot "cmdline-tools") -Destination $latestRoot
    Remove-Item -LiteralPath $extractRoot -Recurse -Force
}

$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$yes = (1..30 | ForEach-Object { "y" }) -join "`n"
$yes | & $sdkManager --licenses | Out-Null
& $sdkManager "platform-tools" "platforms;android-$AndroidApi" "build-tools;$BuildTools"

$propertiesSdk = $sdkRoot.Replace("\", "/")
$propertiesSdk = [Regex]::Replace($propertiesSdk, '[^\x00-\x7F]', {
    param($match)
    "\u{0:x4}" -f [int][char]$match.Value
})
Set-Content -LiteralPath (Join-Path $projectRoot "local.properties") -Value "sdk.dir=$propertiesSdk" -Encoding ASCII

$wrapperJar = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $wrapperJar)) {
    Get-ToolArchive -Uri "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" -Destination $wrapperJar
}

Write-Host "Android toolchain ready."
Write-Host "JAVA_HOME=$jdkRoot"
Write-Host "ANDROID_SDK_ROOT=$sdkRoot"
Write-Host "Run: .\gradlew.bat test assembleDebug"
