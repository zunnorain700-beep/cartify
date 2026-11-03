$adb = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe'
Write-Output '--- devices ---'
& $adb devices
Write-Output '--- prefs ---'
& $adb shell run-as com.cartify.app cat shared_prefs/cartify_prefs.xml
Write-Output '--- files ---'
& $adb shell "ls /sdcard/Music/Cartify/*.mp3 | wc -l"
Write-Output '--- crash ---'
$c = (& $adb logcat -d) | Select-String 'FATAL EXCEPTION'
Write-Output ("fatal lines: " + $c.Count)
