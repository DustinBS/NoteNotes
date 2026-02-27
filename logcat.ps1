# logcat.ps1 - Stream NoteNotes app logs in real-time
# Usage: .\logcat.ps1
# Optional: .\logcat.ps1 -Filter "crash"   (grep for specific text)
# Optional: .\logcat.ps1 -Save              (also save to logcat_output.txt)

param(
    [string]$Filter = "",
    [switch]$Save,
    [switch]$CrashOnly
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    Write-Host "ERROR: adb not found at $adb" -ForegroundColor Red
    exit 1
}

# Check device
$devices = & $adb devices 2>&1 | Select-String "device$"
if (-not $devices) {
    Write-Host "ERROR: No device connected. Connect your phone via USB." -ForegroundColor Red
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  NoteNotes Log Viewer" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Clear old logs
& $adb logcat -c 2>$null
Write-Host "[*] Cleared old logs" -ForegroundColor DarkGray

# Get the PID of the app if running
$pid = & $adb shell "pidof com.notenotes" 2>$null
if ($pid) {
    Write-Host "[*] App is running (PID: $($pid.Trim()))" -ForegroundColor Green
} else {
    Write-Host "[*] App is not running. Logs will appear when you launch it." -ForegroundColor Yellow
}

Write-Host "[*] Streaming logs... Press Ctrl+C to stop." -ForegroundColor DarkGray
Write-Host ""

# Build logcat filter
if ($CrashOnly) {
    $logcatArgs = @("logcat", "-v", "time", "-s", "AndroidRuntime:E", "System.err:W")
    Write-Host "[*] Mode: CRASH ONLY" -ForegroundColor Yellow
} else {
    # Show our app's logs + crashes + system errors
    $logcatArgs = @("logcat", "-v", "time", 
        "-s", 
        "NoteNotes:V",
        "NNRecord:V", 
        "NNPreview:V",
        "NNLibrary:V",
        "NNAudio:V",
        "NNPipeline:V",
        "NNExport:V",
        "NNDatabase:V",
        "NNNav:V",
        "AndroidRuntime:E",
        "System.err:W",
        "ActivityManager:I")
    Write-Host "[*] Mode: ALL APP LOGS" -ForegroundColor Green
}

$logFile = $null
if ($Save) {
    $logFile = Join-Path $PSScriptRoot "logcat_output.txt"
    Write-Host "[*] Saving to: $logFile" -ForegroundColor DarkGray
    "" | Out-File $logFile -Encoding utf8
}

Write-Host "----------------------------------------" -ForegroundColor DarkGray

# Stream logs with color coding
$process = Start-Process -FilePath $adb -ArgumentList $logcatArgs -NoNewWindow -PassThru -RedirectStandardOutput "NUL" 2>$null

# Use .NET process for better control
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $adb
$psi.Arguments = $logcatArgs -join " "
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

# Kill the previous process if started
if ($process) { 
    try { $process.Kill() } catch {} 
}

$proc = [System.Diagnostics.Process]::Start($psi)

try {
    while (-not $proc.HasExited) {
        $line = $proc.StandardOutput.ReadLine()
        if ($null -eq $line) { break }

        # Apply user filter if specified
        if ($Filter -and $line -notmatch $Filter) { continue }

        # Color code by severity
        $color = "White"
        if ($line -match " E/|E AndroidRuntime|FATAL") {
            $color = "Red"
        } elseif ($line -match " W/|System.err") {
            $color = "Yellow"  
        } elseif ($line -match " I/") {
            $color = "Green"
        } elseif ($line -match " D/") {
            $color = "Cyan"
        } elseif ($line -match " V/") {
            $color = "DarkGray"
        }

        # Highlight crash-related lines
        if ($line -match "Exception|Error|FATAL|crash|NullPointer|NoSuchMethod") {
            $color = "Red"
        }

        Write-Host $line -ForegroundColor $color

        # Save to file if requested
        if ($logFile) {
            $line | Out-File $logFile -Append -Encoding utf8
        }
    }
} finally {
    if (-not $proc.HasExited) {
        $proc.Kill()
    }
    $proc.Dispose()
    Write-Host ""
    Write-Host "[*] Log streaming stopped." -ForegroundColor DarkGray
}
