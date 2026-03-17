# NoteNotes Deploy Script
# Builds the debug APK and installs it on a connected Android device.
# Usage: .\deploy.ps1

$ErrorActionPreference = "Stop"

# --- Config ---
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$packageName = "com.notenotes"

Write-Host "`n=== NoteNotes Deploy ===" -ForegroundColor Cyan

# --- Step 1: Verify JDK ---
$javaVer = cmd /c "java -version 2>&1" | Select-Object -First 1
Write-Host "[1/5] JDK: $javaVer" -ForegroundColor Green

# --- Step 2: Check device ---
Write-Host "[2/5] Checking connected devices..." -ForegroundColor Green
$devices = & $adb devices 2>&1 | Where-Object { $_ -match "device$" }
$deviceCount = @($devices).Count

if ($deviceCount -eq 0) {
    Write-Host "`nERROR: No authorized device found!" -ForegroundColor Red
    Write-Host "  - Is USB debugging enabled on your phone?"
    Write-Host "  - Did you accept the USB debugging prompt on your phone?"
    Write-Host "  - Try: & '$adb' kill-server; & '$adb' start-server; & '$adb' devices"
    exit 1
}

if ($deviceCount -gt 1) {
    Write-Host "`nMultiple devices found:" -ForegroundColor Yellow
    $i = 0
    $deviceList = @()
    foreach ($d in $devices) {
        $serial = ($d -split "\s+")[0]
        $deviceList += $serial
        Write-Host "  [$i] $serial"
        $i++
    }
    $choice = Read-Host "Pick a device number (0-$($deviceCount-1))"
    $targetDevice = $deviceList[[int]$choice]
    Write-Host "Using device: $targetDevice" -ForegroundColor Green
    $deviceArg = "-s", $targetDevice
} else {
    $targetDevice = ($devices -split "\s+")[0]
    Write-Host "  Device: $targetDevice" -ForegroundColor Green
    $deviceArg = "-s", $targetDevice
}

# --- Step 3: Build ---
Write-Host "[3/5] Building debug APK..." -ForegroundColor Green
& .\gradlew.bat assembleDebug --daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host "`nERROR: Build failed! See errors above." -ForegroundColor Red
    exit 1
}
Write-Host "  Build successful." -ForegroundColor Green

# --- Step 4: Install ---
$apk = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    Write-Host "`nERROR: APK not found at $apk" -ForegroundColor Red
    exit 1
}

# Re-verify device is still connected before installing
Write-Host "[4/5] Verifying device connection..." -ForegroundColor Green
$devicesCheck = & $adb devices 2>&1 | Where-Object { $_ -match "$targetDevice.*device$" }
if (-not $devicesCheck) {
    Write-Host "  Device lost connection. Restarting ADB server..." -ForegroundColor Yellow
    & $adb kill-server | Out-Null
    Start-Sleep -Milliseconds 500
    & $adb start-server | Out-Null
    Start-Sleep -Milliseconds 1000
}

Write-Host "  Installing APK..." -ForegroundColor Green
& $adb @deviceArg install -r $apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "`nERROR: Install failed!" -ForegroundColor Red
    exit 1
}
Write-Host "  Install successful." -ForegroundColor Green

# --- Step 5: Launch ---
Write-Host "[5/5] Launching NoteNotes..." -ForegroundColor Green
& $adb @deviceArg shell am start -n "$packageName/.MainActivity"
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Warning: Could not auto-launch. Open NoteNotes from your app drawer." -ForegroundColor Yellow
} else {
    Write-Host "  App launched!" -ForegroundColor Green
}

Write-Host "`n=== Done! Check your phone. ===" -ForegroundColor Cyan
