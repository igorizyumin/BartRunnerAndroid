param(
    [string] $OutputRoot = "app\src\test\resources",
    [string] $ApiKey = "MW9S-E7SL-26DU-VV8V"
)

$ErrorActionPreference = "Stop"
$captureStamp = Get-Date -Format "yyyyMMdd_HHmmss"
$fixtureDir = Join-Path $OutputRoot ("bart_live_" + $captureStamp)
$etdDir = Join-Path $fixtureDir "etd"
New-Item -ItemType Directory -Path $etdDir -Force | Out-Null

Invoke-WebRequest `
    -Uri "https://api.bart.gov/gtfsrt/tripupdate.aspx" `
    -OutFile (Join-Path $fixtureDir "trip_updates.pb") `
    -TimeoutSec 60
Invoke-WebRequest `
    -Uri "https://api.bart.gov/gtfsrt/alerts.aspx" `
    -OutFile (Join-Path $fixtureDir "alerts.pb") `
    -TimeoutSec 60

$stations = @(
    "12th", "16th", "19th", "24th", "antc", "ashb", "balb", "bayf",
    "bery", "cast", "civc", "cols", "colm", "conc", "daly", "dbrk",
    "dubl", "deln", "plza", "embr", "frmt", "ftvl", "glen", "hayw",
    "lafy", "lake", "mcar", "mlpt", "mlbr", "mont", "nbrk", "ncon",
    "orin", "pctr", "pitt", "phil", "powl", "rich", "rock", "sbrn",
    "sanl", "sfia", "shay", "ssan", "ucty", "warm", "wcrk", "wdub",
    "woak"
)

foreach ($station in $stations) {
    Invoke-WebRequest `
        -Uri "https://api.bart.gov/api/etd.aspx?cmd=etd&orig=$station&key=$ApiKey" `
        -OutFile (Join-Path $etdDir ($station + ".xml")) `
        -TimeoutSec 60
    Start-Sleep -Milliseconds 100
}

Set-Content -Path (Join-Path $OutputRoot "bart_live_fixture.txt") `
    -Value (Split-Path $fixtureDir -Leaf) -NoNewline
Write-Output $fixtureDir
