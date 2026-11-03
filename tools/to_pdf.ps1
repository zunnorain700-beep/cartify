$docx = 'C:\Users\HP\Desktop\Cartify_Changes.docx'
$pdf  = 'C:\Users\HP\Desktop\Cartify_Changes.pdf'
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $doc = $word.Documents.Open($docx, $false, $true)
    $doc.SaveAs([ref]$pdf, [ref]17)
    $doc.Close($false)
    $word.Quit()
    [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null
    Write-Output 'converted'
} catch {
    Write-Output ('ERROR: ' + $_.Exception.Message)
}
Get-ChildItem 'C:\Users\HP\Desktop\Cartify_Changes.*' | Select-Object Name, Length | Format-Table -AutoSize
