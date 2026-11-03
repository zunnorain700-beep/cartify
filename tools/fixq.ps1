$adb = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe'
& $adb shell am force-stop com.cartify.app
Start-Sleep -Seconds 2
& $adb shell run-as com.cartify.app sh -c "sed -i 's/name=\"mp3_quality\">2</name=\"mp3_quality\">0</' shared_prefs/cartify_prefs.xml"
Write-Output '--- after ---'
& $adb shell run-as com.cartify.app cat shared_prefs/cartify_prefs.xml
