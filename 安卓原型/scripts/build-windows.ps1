param(
    [ValidateSet("test", "assembleDebug", "verify")]
    [string]$Target = "verify",
    [string]$DriveLetter = "R"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$drive = "${DriveLetter}:"
$driveRoot = "${drive}\"
if (Test-Path $driveRoot) {
    throw "$drive is already in use. Pass a free letter with -DriveLetter."
}

$localProperties = Join-Path $projectRoot "local.properties"
$hadLocalProperties = Test-Path $localProperties
$originalLocalProperties = if ($hadLocalProperties) {
    [IO.File]::ReadAllBytes($localProperties)
} else {
    $null
}

& subst $drive $projectRoot
if ($LASTEXITCODE -ne 0) { throw "Could not create temporary build drive $drive" }

try {
    [IO.File]::WriteAllText($localProperties, "sdk.dir=$drive/.tooling/android-sdk`r`n", [Text.Encoding]::ASCII)
    Push-Location $driveRoot
    try {
        $env:JAVA_HOME = "$driveRoot.tooling\jdk-17"
        $env:ANDROID_HOME = "$driveRoot.tooling\android-sdk"
        $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
        $tasks = switch ($Target) {
            "test" { @("test") }
            "assembleDebug" { @("assembleDebug") }
            default { @("test", "assembleDebug") }
        }
        & .\gradlew.bat @tasks "--console=plain"
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} finally {
    if ($hadLocalProperties) {
        [IO.File]::WriteAllBytes($localProperties, $originalLocalProperties)
    } elseif (Test-Path $localProperties) {
        Remove-Item -LiteralPath $localProperties -Force
    }
    & subst $drive /D
}
