$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe'
Write-Output ("DEVICES: " + ((& $adb devices) -join ' | '))
Write-Output ("PREF: " + ((& $adb shell run-as com.cartify.app cat shared_prefs/cartify_prefs.xml) -join ' '))
Write-Output ("FILES: " + (& $adb shell "ls /sdcard/Music/Cartify/*.mp3 | wc -l"))
