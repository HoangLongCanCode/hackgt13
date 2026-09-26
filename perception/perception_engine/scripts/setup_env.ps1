<#
.SYNOPSIS
  One-shot setup of the KSR perception engine on Windows from a fresh clone.

.DESCRIPTION
  Creates perception_engine\.venv (Python 3.13), installs torch 2.14.0+cu130 / torchvision 0.29.0+cu130 from the
  PyTorch CUDA 13.0 index, then requirements.txt, writes a small .pth hook that keeps caches inside the project,
  points the Ultralytics settings at models\, clones the vendored upstream code (scripts\fetch_third_party.py),
  downloads the realtime default weights (scripts\download_models.py --minimal) and runs scripts\verify_env.py.
  Every step is idempotent: re-running skips what is already there.

  Run from anywhere (the script changes to perception_engine\ itself):
    powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1
    powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -AllModels -WithData
    powershell -ExecutionPolicy Bypass -File scripts\setup_env.ps1 -DryRun      # print the steps only

  The first run downloads about 2.6 GB of wheels (torch alone is about 2 GB) and 1.4 GB of weights.
  See SETUP.md for GPU notes (RTX 50xx needs a cu128+ torch build; onnxruntime-gpu needs torch imported first).

.PARAMETER Python
  Base interpreter used to create the venv (default: `py -3.13`, then `python`). Must be CPython 3.13 x64.
.PARAMETER AllModels
  Download every alternative backend's weights too (download_models.py --all, about 3.0 GB in total).
.PARAMETER WithData
  Also fetch the BDD100K sample test set (scripts\fetch_bdd_samples.py, about 0.5 GB; research use only).
.PARAMETER SkipThirdParty
  Do not run fetch_third_party.py.
.PARAMETER SkipModels
  Do not run download_models.py.
.PARAMETER SkipVerify
  Do not run verify_env.py (it needs a CUDA GPU).
.PARAMETER DryRun
  Print what would run; change nothing.
#>
[CmdletBinding()]
param(
    [string]$Python = "",
    [switch]$AllModels,
    [switch]$WithData,
    [switch]$SkipThirdParty,
    [switch]$SkipModels,
    [switch]$SkipVerify,
    [switch]$DryRun
)
$ErrorActionPreference = "Stop"

$TorchIndex = "https://download.pytorch.org/whl/cu130"
$TorchPkgs = @("torch==2.14.0+cu130", "torchvision==0.29.0+cu130")

$Root = Split-Path -Parent $PSScriptRoot          # perception_engine\
Set-Location $Root
$Venv = Join-Path $Root ".venv"
$VPy = Join-Path $Venv "Scripts\python.exe"

function Step([string]$msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }

# Native tools write progress/warnings to stderr; Windows PowerShell 5.1 turns redirected stderr into error
# records, which 'Stop' would make fatal. Run them with 'Continue' and judge success by the exit code only.
function Invoke-Native([string]$exe, [string[]]$argv) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { $out = & $exe @argv 2>$null; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    return @{ Code = $code; Out = (($out | Out-String).Trim()) }
}

function Invoke-Checked([string]$exe, [string[]]$argv) {
    Write-Host ("    " + $exe + " " + ($argv -join " "))
    if ($DryRun) { return }
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $exe @argv; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    if ($code -ne 0) { throw "command failed (exit $code): $exe $($argv -join ' ')" }
}

# ---------------------------------------------------------------- 1. base interpreter + venv
Step "Python 3.13 venv in $Venv"
if (Test-Path $VPy) {
    Write-Host "    venv exists: $VPy"
} else {
    $base = @()
    if ($Python) {
        $base = @($Python)
    } elseif (Get-Command py -ErrorAction SilentlyContinue) {
        $base = @("py", "-3.13")
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        $base = @("python")
    } else {
        throw "No Python found. Install CPython 3.13 x64 (python.org or the Microsoft Store) and re-run, or pass -Python <path>."
    }
    $exe = $base[0]
    $pre = @()
    if ($base.Count -gt 1) { $pre = $base[1..($base.Count - 1)] }
    if (-not $DryRun) {
        $r = Invoke-Native $exe ($pre + @("-c", "import sys, struct; print('%d.%d %d' % (sys.version_info[0], sys.version_info[1], struct.calcsize('P') * 8))"))
        if ($r.Code -ne 0) { throw "could not run $($base -join ' ')" }
        if ($r.Out -ne "3.13 64") { throw "need CPython 3.13 64-bit, got '$($r.Out)' from $($base -join ' ') (use -Python)" }
    }
    Invoke-Checked $exe ($pre + @("-m", "venv", $Venv))
}

# ---------------------------------------------------------------- 2. torch (CUDA 13.0 build) + requirements
Step "torch + torchvision from $TorchIndex (about 2 GB the first time)"
$haveTorch = $false
if ((Test-Path $VPy) -and -not $DryRun) {
    $r = Invoke-Native $VPy @("-c", "import importlib.metadata as m; print(m.version('torch'), m.version('torchvision'))")
    if ($r.Code -eq 0 -and $r.Out -eq "2.14.0+cu130 0.29.0+cu130") { $haveTorch = $true }
}
if ($haveTorch) {
    Write-Host "    already installed: torch 2.14.0+cu130, torchvision 0.29.0+cu130"
} else {
    Invoke-Checked $VPy (@("-m", "pip", "install", "--progress-bar", "off") + $TorchPkgs + @("--index-url", $TorchIndex))
}
Step "requirements.txt"
Invoke-Checked $VPy @("-m", "pip", "install", "--progress-bar", "off", "-r", (Join-Path $Root "requirements.txt"))

# ---------------------------------------------------------------- 3. project-local caches (.pth hook) + Ultralytics settings
Step "project-local cache hook (.venv\Lib\site-packages\ksr_perception_env.pth)"
# Runs at every start of this venv's interpreter; setdefault, so explicitly set variables always win.
$pth = 'import os, sys, pathlib; _r = pathlib.Path(sys.prefix).parent; _m = pathlib.Path(os.environ.get("KSR_MODELS_DIR") or _r / "models"); [os.environ.setdefault(_k, str(_v)) for _k, _v in (("YOLO_CONFIG_DIR", _r / ".cache" / "ultralytics"), ("HF_HUB_CACHE", _m / "huggingface" / "hub"), ("TORCH_HOME", _m / "torch"), ("YOLO_AUTOINSTALL", "False"))]; [pathlib.Path(os.environ[_k]).mkdir(parents=True, exist_ok=True) for _k in ("YOLO_CONFIG_DIR", "HF_HUB_CACHE", "TORCH_HOME")]'
$site = Join-Path $Venv "Lib\site-packages"
Write-Host "    -> $site\ksr_perception_env.pth"
if (-not $DryRun) {
    Set-Content -Encoding ascii -Path (Join-Path $site "ksr_perception_env.pth") -Value $pth
}
Step "Ultralytics settings (weights_dir=models, runs_dir=outputs\runs, sync=False)"
$ulCode = "from pathlib import Path; from ultralytics import settings; r = Path.cwd(); settings.update({'weights_dir': str(r / 'models'), 'runs_dir': str(r / 'outputs' / 'runs'), 'datasets_dir': str(r / 'data'), 'sync': False}); print('    ultralytics settings:', settings.file)"
Invoke-Checked $VPy @("-c", $ulCode)

# ---------------------------------------------------------------- 4. vendored code, weights, data
if (-not $SkipThirdParty) {
    Step "vendored upstream code -> perception\third_party (git, pinned commits)"
    Invoke-Checked $VPy @("scripts\fetch_third_party.py")
}
if (-not $SkipModels) {
    $sel = "--minimal"
    if ($AllModels) { $sel = "--all" }
    Step "model weights -> models\ ($sel)"
    Invoke-Checked $VPy @("scripts\download_models.py", $sel)
}
if ($WithData) {
    Step "BDD100K sample set -> data\bdd100k (research / non-commercial use only)"
    Invoke-Checked $VPy @("scripts\fetch_bdd_samples.py")
}

# ---------------------------------------------------------------- 5. verify
if (-not $SkipVerify) {
    Step "environment check (scripts\verify_env.py: torch CUDA, ORT CUDA EP, Ultralytics, ...)"
    Invoke-Checked $VPy @("scripts\verify_env.py")
}
Write-Host ""
Write-Host "Done. Activate with:  .venv\Scripts\Activate.ps1   (or call .venv\Scripts\python.exe directly)" -ForegroundColor Green
Write-Host "Run the server:       .venv\Scripts\python.exe -m perception.realtime.server --help"
