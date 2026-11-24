$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd "C:\Users\Saatvika Reddy\NightAgent"
 & ".\gradlew.bat" "app:compileDebugKotlin" 2>&1 | Out-File -FilePath "compile_out.txt" -Encoding UTF8