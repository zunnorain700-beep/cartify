$adb = 'C:\Users\HP\AppData\Local\AndroidBuildToolchain\android-sdk\platform-tools\adb.exe'
$dir = 'C:\Users\HP\Downloads\cartify\shots'

function GetDump($name) {
  for ($i = 0; $i -lt 5; $i++) {
    & $adb shell uiautomator dump /sdcard/d.xml 2>&1 | Out-Null
    & $adb pull /sdcard/d.xml "$dir\$name.xml" 2>&1 | Out-Null
    if ((Test-Path "$dir\$name.xml") -and ((Get-Item "$dir\$name.xml").Length -gt 500)) {
      return Get-Content "$dir\$name.xml" -Raw
    }
    Start-Sleep -Seconds 2
  }
  return $null
}

function Bounds($xml, $pattern) {
  if (-not $xml) { return $null }
  $m = [regex]::Match($xml, $pattern)
  if (-not $m.Success) { return $null }
  $b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $b.Success) { return $null }
  return @([int]((([int]$b.Groups[1].Value)+([int]$b.Groups[3].Value))/2), [int]((([int]$b.Groups[2].Value)+([int]$b.Groups[4].Value))/2))
}

& $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
& $adb shell am force-stop com.cartify.app
& $adb logcat -c
Start-Sleep -Seconds 2
& $adb shell am start -n com.cartify.app/.MainActivity | Out-Null
Start-Sleep -Seconds 18

$x = GetDump 'f1'
if (-not $x) { Write-Output 'DUMP FAILED'; exit }
Write-Output ('SCREEN: ' + ((([regex]::Matches($x,'text="([^"]{2,40})"') | ForEach-Object { $_.Groups[1].Value }) | Select-Object -First 8) -join ' | '))

$p = Bounds $x '<node[^>]*resource-id="com.cartify.app:id/searchInput"[^>]*>'
if (-not $p) { Write-Output 'NO SEARCH BOX'; exit }
& $adb shell input tap $p[0] $p[1]
Start-Sleep -Seconds 1
& $adb shell input text 'keane'
& $adb shell input keyevent 66
Start-Sleep -Seconds 18

$y = GetDump 'f2'
if (-not $y) { Write-Output 'SEARCH DUMP FAILED'; exit }
$st = [regex]::Match($y, '<node[^>]*statusText[^>]*>')
if ($st.Success) { Write-Output ('STATUS: ' + $st.Value.Substring(0, [Math]::Min(140, $st.Value.Length))) }

$d = Bounds $y '<node[^>]*resource-id="com.cartify.app:id/action"[^>]*>'
if (-not $d) { $d = Bounds $y '<node[^>]*text="Download"[^>]*>' }
if (-not $d) { Write-Output 'NO DOWNLOAD BUTTON'; exit }
& $adb shell input tap $d[0] $d[1]
Write-Output 'download tapped'
Start-Sleep -Seconds 80

Write-Output '--- match log ---'
(& $adb logcat -d) | Select-String 'Cartify.*(matched |rejected|track failed|took )' | ForEach-Object { $_.Line.Trim() } | Select-Object -Last 6
Write-Output ('--- crash count: ' + (((& $adb logcat -d) | Select-String 'FATAL EXCEPTION').Count))
& $adb shell "ls /sdcard/Music/Cartify/*.mp3 | wc -l"
