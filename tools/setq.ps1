$adb = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe'
$dir = 'C:\Users\HP\Downloads\cartify\shots'

function Bounds($xml, $pattern) {
  $m = [regex]::Match($xml, $pattern)
  if (-not $m.Success) { return $null }
  $b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $b.Success) { return $null }
  return @([int]((([int]$b.Groups[1].Value)+([int]$b.Groups[3].Value))/2), [int]((([int]$b.Groups[2].Value)+([int]$b.Groups[4].Value))/2))
}

function Dump($name) {
  & $adb shell uiautomator dump /sdcard/d.xml | Out-Null
  & $adb pull /sdcard/d.xml "$dir\$name.xml" | Out-Null
  return Get-Content "$dir\$name.xml" -Raw
}

& $adb shell am force-stop com.cartify.app
Start-Sleep -Seconds 2
& $adb shell am start -n com.cartify.app/.MainActivity | Out-Null
Start-Sleep -Seconds 14

& $adb shell input tap 200 2170
Start-Sleep -Seconds 3
$x = Dump 'q1'
$p = Bounds $x '<node[^>]*resource-id="com.cartify.app:id/appMenu"[^>]*>'
if ($p) { & $adb shell input tap $p[0] $p[1]; Start-Sleep -Seconds 2 }
$y = Dump 'q2'
$p = Bounds $y '<node[^>]*text="Download quality[^"]*"[^>]*>'
if ($p) { & $adb shell input tap $p[0] $p[1]; Start-Sleep -Seconds 2 }
$z = Dump 'q3'
$p = Bounds $z '<node[^>]*text="Best \(V0\)[^"]*"[^>]*>'
if ($p) { & $adb shell input tap $p[0] $p[1]; Write-Output 'picked Best (V0)'; Start-Sleep -Seconds 2 }
else { Write-Output 'V0 option not found' }

Write-Output '--- prefs ---'
& $adb shell run-as com.cartify.app cat shared_prefs/cartify_prefs.xml | Select-String 'mp3_quality'
