param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = "Stop"

function Fail($Message) {
    Write-Error $Message
    exit 1
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    Fail "La versione non puo essere vuota. Esempio: v1.7.104"
}

if (-not ($Version -match "^v\d+(\.\d+)*$")) {
    Fail "La versione deve avere formato tipo v1.7.104"
}

$localTagExists = -not [string]::IsNullOrWhiteSpace((git tag --list $Version))
$remoteTagExists = -not [string]::IsNullOrWhiteSpace((git ls-remote --tags origin $Version))

if (-not $localTagExists -and -not $remoteTagExists) {
    Fail "Il tag '$Version' non esiste ne locale ne remoto."
}

Write-Host "Rimozione release/tag:"
Write-Host " - Tag: $Version"
Write-Host " - Tag locale presente: $localTagExists"
Write-Host " - Tag remoto presente: $remoteTagExists"
Write-Host ""

$confirmation = Read-Host "Confermi eliminazione del tag locale/remoto? (s/N)"
if ($confirmation -notin @("s", "S", "si", "SI", "Si")) {
    Fail "Operazione annullata."
}

if ($localTagExists) {
    Write-Host "Elimino tag locale $Version..."
    git tag -d $Version
}

if ($remoteTagExists) {
    Write-Host "Elimino tag remoto $Version..."
    git push origin :refs/tags/$Version
}

Write-Host ""
Write-Host "Tag rimosso."
Write-Host "Se la GitHub Release esiste ancora come pagina, eliminala anche da GitHub web."
