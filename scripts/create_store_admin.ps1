param(
    [Parameter(Mandatory = $true)]
    [string]$Username,

    [Parameter(Mandatory = $true)]
    [string]$Pin,

    [string]$FullName = "Store Admin",
    [string]$StoreId,
    [string]$StoreName
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localM2 = Join-Path $repoRoot ".m2\repository"
$defaultM2 = Join-Path $HOME ".m2\repository"
$mavenRepo = if (Test-Path $localM2) { $localM2 } else { $defaultM2 }

$env:MAVEN_OPTS = "-Dmaven.repo.local=$mavenRepo"

Push-Location $repoRoot
try {
    mvn -q -DskipTests compile
    if ($LASTEXITCODE -ne 0) {
        throw "Maven compile failed."
    }

    $runtimeJars = @(
        (Join-Path $mavenRepo "org\mindrot\jbcrypt\0.4\jbcrypt-0.4.jar"),
        (Join-Path $mavenRepo "com\h2database\h2\2.2.224\h2-2.2.224.jar"),
        (Join-Path $mavenRepo "org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"),
        (Join-Path $mavenRepo "ch\qos\logback\logback-classic\1.4.14\logback-classic-1.4.14.jar"),
        (Join-Path $mavenRepo "ch\qos\logback\logback-core\1.4.14\logback-core-1.4.14.jar")
    )

    $missingJars = $runtimeJars | Where-Object { -not (Test-Path $_) }
    if ($missingJars.Count -gt 0) {
        throw "Missing runtime jars:`n$($missingJars -join "`n")"
    }

    $cpEntries = @((Join-Path $repoRoot "target\classes")) + $runtimeJars
    $classpath = [string]::Join([System.IO.Path]::PathSeparator, $cpEntries)

    $javaArgs = @(
        "-cp", $classpath,
        "com.pos.util.StoreAdminBootstrap",
        "--username", $Username,
        "--pin", $Pin,
        "--full-name", $FullName
    )

    if ($StoreId) {
        $javaArgs += @("--store-id", $StoreId)
    }

    if ($StoreName) {
        $javaArgs += @("--store-name", $StoreName)
    }

    & java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Admin bootstrap failed."
    }
}
finally {
    Pop-Location
}
