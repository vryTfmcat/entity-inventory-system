param(
    [ValidateSet("test", "assembleDebug", "verify")]
    [string]$Target = "verify",
    [string]$DriveLetter = "R",
    [string]$ToolchainDriveLetter = "T"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$drive = "${DriveLetter}:"
$toolDrive = "${ToolchainDriveLetter}:"
$toolRoot = Join-Path $env:USERPROFILE "Documents/Codex/codex资料/项目/实体物品盘点系统/Android SDK/.tooling"
$buildRoot = if ($env:ENTITY_INVENTORY_BUILD_ROOT) { $env:ENTITY_INVENTORY_BUILD_ROOT } else { Join-Path $env:USERPROFILE "Documents/Codex/build-cache/entity-inventory" }
if ($drive -eq $toolDrive -or (Test-Path "${drive}\") -or (Test-Path "${toolDrive}\")) { throw "Build drive letters must be distinct and unused." }
if (!(Test-Path (Join-Path $toolRoot "jdk-17/bin/java.exe")) -or !(Test-Path (Join-Path $toolRoot "android-sdk"))) { throw "External Android toolchain is missing: $toolRoot" }
$localProperties = Join-Path $projectRoot "local.properties"
$hadLocalProperties = Test-Path $localProperties
$originalLocalProperties = if ($hadLocalProperties) { [IO.File]::ReadAllBytes($localProperties) } else { $null }
$oldJava = $env:JAVA_HOME
$oldAndroid = $env:ANDROID_HOME
$oldSdk = $env:ANDROID_SDK_ROOT
$mappedProject = $false
$mappedToolchain = $false
try {
    & subst $drive $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Could not map project drive." }
    $mappedProject = $true
    & subst $toolDrive $toolRoot
    if ($LASTEXITCODE -ne 0) { throw "Could not map external toolchain drive." }
    $mappedToolchain = $true
    [IO.File]::WriteAllText($localProperties, "sdk.dir=$toolDrive/android-sdk`r`n", [Text.Encoding]::ASCII)
    $env:JAVA_HOME = "${toolDrive}\jdk-17"
    $env:ANDROID_HOME = "${toolDrive}\android-sdk"
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    Push-Location "${drive}\"
    try {
        $tasks = switch ($Target) { "test" { @("test") }; "assembleDebug" { @("assembleDebug") }; default { @("test", "assembleDebug") } }
        & .\gradlew.bat @tasks "--project-cache-dir" (Join-Path $buildRoot "gradle") "--no-daemon" "--console=plain"
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    if ($hadLocalProperties) { [IO.File]::WriteAllBytes($localProperties, $originalLocalProperties) }
    elseif (Test-Path $localProperties) { Remove-Item -LiteralPath $localProperties -Force }
    $env:JAVA_HOME = $oldJava
    $env:ANDROID_HOME = $oldAndroid
    $env:ANDROID_SDK_ROOT = $oldSdk
    if ($mappedToolchain) { & subst $toolDrive /D }
    if ($mappedProject) { & subst $drive /D }
    foreach ($cacheRelative in @(".gradle/kotlin/errors", ".gradle/kotlin", ".gradle")) {
        $cachePath = [IO.Path]::GetFullPath((Join-Path $projectRoot $cacheRelative))
        $checkedRoot = [IO.Path]::GetFullPath($projectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (!$cachePath.StartsWith($checkedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "Cache path escaped project root." }
        if ((Test-Path -LiteralPath $cachePath) -and !((Get-Item -LiteralPath $cachePath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            if (!(Get-ChildItem -LiteralPath $cachePath -Force | Select-Object -First 1)) { Remove-Item -LiteralPath $cachePath -Force }
        }
    }

}
