<#
.SYNOPSIS
  The tablet's SIM mode on this PC: starts the perception server in sim mode (unless one already answers on -Port),
  builds the desktop viewer (frontend/desktop) and plays the clip with the app's AR, route text and voice rules.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\desktop_sim.ps1
  powershell -ExecutionPolicy Bypass -File scripts\desktop_sim.ps1 -Video real_010 -DebugView
  powershell -ExecutionPolicy Bypass -File scripts\desktop_sim.ps1 -NoServer -Port 8765            # reuse a running server
  powershell -ExecutionPolicy Bypass -File scripts\desktop_sim.ps1 -ServerArgs "--set","slow.max_hz=6"
  powershell -ExecutionPolicy Bypass -File scripts\desktop_sim.ps1 -- --snapshots 5,25,70           # PNGs, no window

  Needs JAVA_HOME (JDK 17+) or -JavaHome, and the perception venv (perception_engine/.venv, PERCEPTION_PYTHON or
  -Python). Everything after the named parameters goes to the viewer (desktop-sim --help).
#>
param(
    [string]$Video = "real_009",
    [int]$Port = 8766,
    [string]$Python = "",
    [string]$JavaHome = "",
    [switch]$DebugView,
    [switch]$NoServer,
    [switch]$NoBuild,
    [string[]]$ServerArgs = @(),
    [int]$ServerTimeoutS = 240,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ViewerArgs = @()
)
$ErrorActionPreference = "Stop"

$pe = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $pe
$frontend = Join-Path $repo "frontend"

if (-not $Python) { $Python = $env:PERCEPTION_PYTHON }
if (-not $Python) { $Python = Join-Path $pe ".venv\Scripts\python.exe" }
if (-not (Test-Path $Python)) { throw "No perception venv Python at '$Python': give -Python or set PERCEPTION_PYTHON." }
$Python = (Resolve-Path $Python).Path

if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "Set JAVA_HOME to a JDK 17+ (or give -JavaHome)."
}

$viewerBat = Join-Path $frontend "desktop\build\install\desktop-sim\bin\desktop-sim.bat"
if (-not $NoBuild -or -not (Test-Path $viewerBat)) {
    Write-Host "[desktop-sim] building the viewer (:desktop:installDist)"
    Push-Location $frontend
    try {
        & .\gradlew.bat :desktop:installDist -q --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle :desktop:installDist failed" }
    } finally { Pop-Location }
}

$health = "http://127.0.0.1:$Port/health"
function Test-Server {
    try { Invoke-RestMethod -Uri $health -TimeoutSec 2 | Out-Null; return $true } catch { return $false }
}

$server = $null
$logDir = Join-Path $pe "outputs\desktop_sim"
if (Test-Server) {
    Write-Host "[desktop-sim] a server already answers on port $Port; using it"
} elseif ($NoServer) {
    throw "No server on port $Port (-NoServer was given)."
} else {
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $log = Join-Path $logDir "server.log"
    $err = Join-Path $logDir "server.err.log"
    $argsList = @("-m", "perception.realtime.server", "--mode", "sim", "--port", "$Port", "--no-tts") + $ServerArgs
    Write-Host "[desktop-sim] starting the server: python $($argsList -join ' ')   (log: $log)"
    $server = Start-Process -FilePath $Python -ArgumentList $argsList -WorkingDirectory $pe -PassThru -NoNewWindow `
        -RedirectStandardOutput $log -RedirectStandardError $err
    $deadline = (Get-Date).AddSeconds($ServerTimeoutS)
    while (-not (Test-Server)) {
        if ($server.HasExited) {
            Get-Content $log, $err -Tail 30 -ErrorAction SilentlyContinue | Write-Host
            throw "The server exited (code $($server.ExitCode)); see $log and $err"
        }
        if ((Get-Date) -gt $deadline) {
            taskkill /PID $server.Id /T /F | Out-Null
            throw "The server did not answer $health within $ServerTimeoutS s; see $log"
        }
        Start-Sleep -Seconds 1
    }
    Write-Host "[desktop-sim] server ready on port $Port"
}

try {
    $viewerCmd = @("--video", $Video, "--port", "$Port", "--python", $Python)
    if ($DebugView) { $viewerCmd += "--debug" }
    $viewerCmd += $ViewerArgs
    Push-Location $repo
    try {
        & $viewerBat @viewerCmd
    } finally { Pop-Location }
} finally {
    if ($server -and -not $server.HasExited) {
        Write-Host "[desktop-sim] stopping the server"
        taskkill /PID $server.Id /T /F | Out-Null
    }
}
