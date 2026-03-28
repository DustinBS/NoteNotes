# NoteNotes Wireless Deploy Setup
# Guides through Samsung S24 Wireless Debugging

$ErrorActionPreference = "Stop"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

Write-Host "`n=== NoteNotes Wireless Debugging Setup ===" -ForegroundColor Cyan
Write-Host "This script will help you connect your Samsung S24 over Wi-Fi.`n"

Write-Host "Step 1: Ensure phone and PC are on the SAME Wi-Fi network."
Write-Host "Step 2: On your phone, go to Settings > Developer options."
Write-Host "Step 3: Turn on 'Wireless debugging'."
Write-Host "Step 4: Tap the text 'Wireless debugging' to open its specific menu."
Write-Host "Step 5: Tap 'Pair device with pairing code'.`n"

$pairAddress = Read-Host "Enter the IP address & Port shown in the popup (e.g., 192.168.1.47:40109 | pairing code 491307)"
if ([string]::IsNullOrWhiteSpace($pairAddress)) {
    Write-Host "No address entered. Exiting..." -ForegroundColor Red
    exit 1
}

Write-Host "`nPairing with $pairAddress..." -ForegroundColor Yellow
# This will prompt the user for the code in the terminal
& $adb pair $pairAddress

Write-Host "`nStep 6: Close the pairing popup on your phone."
Write-Host "Step 7: Look at the main Wireless debugging screen under 'IP address & Port'."
Write-Host "        Notice that the PORT has changed from the pairing port.`n"

$connectAddress = Read-Host "Enter the NEW IP address & Port for connection (e.g., 192.168.1.47:43061) [Press Enter if it is already connected]"
if (-not [string]::IsNullOrWhiteSpace($connectAddress)) {
    Write-Host "`nConnecting to $connectAddress..." -ForegroundColor Yellow
    & $adb connect $connectAddress
}

Write-Host "`nVerifying connection..." -ForegroundColor Yellow
& $adb devices

$runDeploy = Read-Host "`nWould you like to run the deployment script now? (y/n)"
if ($runDeploy -eq 'y' -or $runDeploy -eq 'Y') {
    Write-Host "`nRunning deploy.ps1..." -ForegroundColor Cyan
    .\deploy.ps1
} else {
    Write-Host "`nWireless setup complete! You can run .\deploy.ps1 anytime as long as you stay on the same Wi-Fi network and keep Wireless Debugging enabled." -ForegroundColor Green
}
