$env:JAVA_HOME = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\tools\jdk\jdk-17.0.20.1+1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$gradle = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\tools\gradle\gradle-8.5\bin\gradle.bat'
$out = 'C:\Users\HP\Downloads\cartify\build-last.txt'
cmd /c "`"$gradle`" --no-daemon --console=plain -p C:\Users\HP\Downloads\cartify :app:assembleDebug > $out 2>&1"
Select-String -Path $out -Pattern 'BUILD (SUCCESSFUL|FAILED)|error:' | Select-Object -First 10 | ForEach-Object { $_.Line.Trim() }
