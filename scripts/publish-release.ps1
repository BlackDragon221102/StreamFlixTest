param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$CommitMessage = "",

    [string]$Branch = "main"
)

$ErrorActionPreference = "Stop"
$buildGradlePath = "app/build.gradle"

function Fail($Message) {
    Write-Error $Message
    exit 1
}

function Invoke-GitOrFail {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Description,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    Write-Host $Description
    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        Fail "Comando git fallito: git $($Arguments -join ' ')"
    }
}

function Update-AndroidVersioning {
    param(
        [string]$GradleFilePath,
        [string]$ReleaseVersion
    )

    if (-not (Test-Path $GradleFilePath)) {
        Fail "File non trovato: $GradleFilePath"
    }

    $content = Get-Content $GradleFilePath -Raw

    $versionName = $ReleaseVersion.TrimStart("v")
    $currentVersionNameMatch = [regex]::Match($content, 'versionName\s+"([^"]+)"')
    if (-not $currentVersionNameMatch.Success) {
        Fail "Impossibile trovare versionName in $GradleFilePath"
    }

    $currentVersionName = $currentVersionNameMatch.Groups[1].Value

    $versionCodeMatch = [regex]::Match($content, 'versionCode\s+(\d+)')
    if (-not $versionCodeMatch.Success) {
        Fail "Impossibile trovare versionCode in $GradleFilePath"
    }

    if ((Compare-Version $versionName $currentVersionName) -lt 0) {
        Fail "La versione richiesta ($versionName) e piu bassa di quella attuale ($currentVersionName)."
    }

    $currentVersionCode = [int]$versionCodeMatch.Groups[1].Value
    $nextVersionCode = $currentVersionCode + 1

    $updatedContent = [regex]::Replace($content, 'versionCode\s+\d+', "versionCode $nextVersionCode", 1)
    $updatedContent = [regex]::Replace($updatedContent, 'versionName\s+"[^"]+"', "versionName ""$versionName""", 1)

    Set-Content -Path $GradleFilePath -Value $updatedContent -NoNewline

    Write-Host "Versionamento aggiornato:"
    Write-Host " - versionCode: $currentVersionCode -> $nextVersionCode"
    Write-Host " - versionName: $currentVersionName -> $versionName"

    return @{
        CurrentVersionCode = $currentVersionCode
        NextVersionCode = $nextVersionCode
        CurrentVersionName = $currentVersionName
        NextVersionName = $versionName
    }
}

function Compare-Version {
    param(
        [string]$First,
        [string]$Second
    )

    $firstParts = $First.Split('.') | ForEach-Object { [int]$_ }
    $secondParts = $Second.Split('.') | ForEach-Object { [int]$_ }
    $maxLength = [Math]::Max($firstParts.Length, $secondParts.Length)

    for ($i = 0; $i -lt $maxLength; $i++) {
        $firstPart = if ($i -lt $firstParts.Length) { $firstParts[$i] } else { 0 }
        $secondPart = if ($i -lt $secondParts.Length) { $secondParts[$i] } else { 0 }

        if ($firstPart -gt $secondPart) { return 1 }
        if ($firstPart -lt $secondPart) { return -1 }
    }

    return 0
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    Fail "La versione non puo essere vuota. Esempio: v1.7.104"
}

if (-not ($Version -match "^v\d+(\.\d+)*$")) {
    Fail "La versione deve avere formato tipo v1.7.104"
}

$currentBranch = (git branch --show-current).Trim()
if ($currentBranch -ne $Branch) {
    Fail "Sei sul branch '$currentBranch'. Passa a '$Branch' prima di pubblicare."
}

$existingLocalTag = git tag --list $Version
if (-not [string]::IsNullOrWhiteSpace($existingLocalTag)) {
    Fail "Il tag locale '$Version' esiste gia."
}

$existingRemoteTag = git ls-remote --tags origin $Version
if (-not [string]::IsNullOrWhiteSpace($existingRemoteTag)) {
    Fail "Il tag remoto '$Version' esiste gia su origin."
}

$statusOutput = git status --porcelain
$hasChanges = -not [string]::IsNullOrWhiteSpace($statusOutput)

$versioningInfo = Update-AndroidVersioning -GradleFilePath $buildGradlePath -ReleaseVersion $Version

$statusOutput = git status --porcelain
$hasChanges = -not [string]::IsNullOrWhiteSpace($statusOutput)

if ($hasChanges) {
    if ([string]::IsNullOrWhiteSpace($CommitMessage)) {
        $CommitMessage = "Release $Version"
    }

    Invoke-GitOrFail -Description "Stage delle modifiche..." -Arguments @("add", "-A")

    Invoke-GitOrFail -Description "Commit delle modifiche..." -Arguments @("commit", "-m", $CommitMessage)
} else {
    Write-Host "Nessuna modifica da committare. Continuo con push e tag."
}

Write-Host ""
Write-Host "Riepilogo pubblicazione:"
Write-Host " - Branch: $Branch"
Write-Host " - Tag: $Version"
Write-Host " - versionCode: $($versioningInfo.CurrentVersionCode) -> $($versioningInfo.NextVersionCode)"
Write-Host " - versionName: $($versioningInfo.CurrentVersionName) -> $($versioningInfo.NextVersionName)"
Write-Host " - Commit message: $CommitMessage"
Write-Host ""

$confirmation = Read-Host "Confermi push e creazione release? (s/N)"
if ($confirmation -notin @("s", "S", "si", "SI", "Si")) {
    Fail "Pubblicazione annullata."
}

Invoke-GitOrFail -Description "Push del branch '$Branch'..." -Arguments @("push", "origin", $Branch)

Invoke-GitOrFail -Description "Creazione tag $Version..." -Arguments @("tag", "-a", $Version, "-m", "Release $Version")

Invoke-GitOrFail -Description "Push del tag $Version..." -Arguments @("push", "origin", $Version)

Write-Host ""
Write-Host "Pubblicazione avviata."
Write-Host "GitHub Actions creera la release per il tag $Version."
