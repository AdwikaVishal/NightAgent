$javaHome = "C:\Program Files\Android\Android Studio\jbr"
$gradleCmd = "C:\Users\Saatvika Reddy\NightAgent\gradlew.bat"
$projectArg = "app:compileDebugKotlin"

$env:JAVA_HOME = $javaHome
Write-Host "JAVA_HOME set to: $javaHome"
Write-Host "Running: $gradleCmd $projectArg"
& $gradleCmd $projectArg