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
  & $adb pull /sdcard/d.xml "$dir\$name.xml" 2>$null | Out-Null
  return Get-Content "$dir\$name.xml" -Raw
}

& $adb shell am force-stop com.cartify.app
& $adb logcat -c
Start-Sleep -Seconds 2
& $adb shell am start -n com.cartify.app/.MainActivity | Out-Null
Start-Sleep -Seconds 14

$x = Dump 'e1'
$p = Bounds $x '<node[^>]*resource-id="com.cartify.app:id/searchInput"[^>]*>'
if (-not $p) { Write-Output 'NO SEARCH BOX'; exit }
& $adb shell input tap $p[0] $p[1]
& $adb shell input text 'keane somewhere only we know'
& $adb shell input keyevent 66
Start-Sleep -Seconds 16

$y = Dump 'e2'
Write-Output ('STATUS: ' + [regex]::Match($y, '<node[^>]*statusText[^>]*>').Value.Substring(0,140))

# Tap the first Download button on a result row
$d = Bounds $y '<node[^>]*resource-id="com.cartify.app:id/action"[^>]*>'
if (-not $d) { $d = Bounds $y '<node[^>]*text="Download"[^>]*>' }
if (-not $d) { Write-Output 'NO DOWNLOAD BUTTON'; exit }
& $adb shell input tap $d[0] $d[1]
Write-Output 'download tapped'
Start-Sleep -Seconds 75

Write-Output '--- match log ---'
(& $adb logcat -d) | Select-String 'Cartify.*(matched |rejected|track failed|took )' | ForEach-Object { $_.Line.Trim() } | Select-Object -Last 6
Write-Output '--- crash ---'
(((& $adb logcat -d) | Select-String 'FATAL EXCEPTION').Count)
Write-Output '--- mp3 count ---'
& $adb shell "ls /sdcard/Music/Cartify/*.mp3 | wc -l"
