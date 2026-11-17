# Creates the virtualenv on first run, then serves the OCR API on http://localhost:8001
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$py = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
    python -m venv (Join-Path $here ".venv")
    & $py -m pip install -r (Join-Path $here "requirements.txt")
}
& $py -m uvicorn app:app --app-dir $here --port 8001
