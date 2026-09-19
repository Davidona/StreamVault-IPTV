param(
    [string]$FeatureResRoot = (Join-Path $PSScriptRoot '..\..\feature\catalog\src\main\res'),
    [string]$AppResRoot = (Join-Path $PSScriptRoot '..\..\app\src\main\res'),
    [string]$InventoryPath = (Join-Path $PSScriptRoot 'resource-inventory.txt')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Xml.Linq

function Get-ResourceMap {
    param([string[]]$Paths)
    $map = @{}
    foreach ($path in ($Paths | Where-Object { Test-Path -LiteralPath $_ } | Sort-Object)) {
        $document = [System.Xml.Linq.XDocument]::Load($path)
        foreach ($element in $document.Root.Elements()) {
            if ($element.Name.LocalName -notin @('string', 'plurals')) { continue }
            $nameAttribute = $element.Attribute('name')
            $name = if ($null -eq $nameAttribute) { '' } else { $nameAttribute.Value }
            if ([string]::IsNullOrWhiteSpace($name)) { continue }
            # Compare the effective text, not XML serialization (which may differ in
            # indentation, declaration, or the translatable fallback attribute).
            $null = $map[$name] = $element.Value
        }
    }
    return ,$map
}

function Get-FormatTokens {
    param([string]$Value)
    # Android's positional and non-positional printf placeholders. Escaped %% is ignored.
    return @([regex]::Matches($Value, '(?<!%)%(?:\d+\$)?[-+#0 (]*\d*(?:\.\d+)?[a-zA-Z]') | ForEach-Object { $_.Value })
}

function Get-LocaleMap {
    param([string]$Root, [string]$LocaleDirectory)
    if ($LocaleDirectory -eq 'values') {
        $paths = @((Join-Path $Root 'values/strings.xml'), (Join-Path $Root 'values/plurals.xml'))
    } else {
        $dir = Join-Path $Root $LocaleDirectory
        $paths = @(Get-ChildItem -LiteralPath $dir -Filter '*.xml' -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -in @('strings.xml', 'strings_missing.xml', 'plurals.xml') } | Select-Object -ExpandProperty FullName)
    }
    $map = Get-ResourceMap -Paths $paths
    return ,$map
}

$appDefault = Get-LocaleMap -Root $AppResRoot -LocaleDirectory 'values'
$featureDefaultPath = Join-Path $FeatureResRoot 'values/strings.xml'
$featureDefault = Get-LocaleMap -Root $FeatureResRoot -LocaleDirectory 'values'

# Before resources are created, use the inventory as the expected Catalog key set so
# this audit intentionally fails with actionable missing-key output.
if ($featureDefault.Count -eq 0 -and (Test-Path -LiteralPath $InventoryPath)) {
    $inventoryPattern = [regex]'R\.(string|plurals)\.([A-Za-z0-9_]+)'
    $inventoryKeys = @(Get-Content -LiteralPath $InventoryPath | ForEach-Object { $inventoryPattern.Matches($_) | ForEach-Object { $_.Groups[2].Value } } | Sort-Object -Unique)
    foreach ($key in $inventoryKeys) {
        if ($appDefault.ContainsKey($key)) { $featureDefault[$key] = $appDefault[$key] }
    }
}

$expectedKeys = @($featureDefault.Keys | Sort-Object)
$localeDirectories = @(Get-ChildItem -LiteralPath $AppResRoot -Directory -Filter 'values-*' | Sort-Object Name | Select-Object -ExpandProperty Name)
$missing = [System.Collections.Generic.List[string]]::new()
$valueMismatch = [System.Collections.Generic.List[string]]::new()
$formatMismatch = [System.Collections.Generic.List[string]]::new()
$unexpected = [System.Collections.Generic.List[string]]::new()
$sourceFallback = [System.Collections.Generic.List[string]]::new()

foreach ($localeDirectory in $localeDirectories) {
    $featureMap = Get-LocaleMap -Root $FeatureResRoot -LocaleDirectory $localeDirectory
    $appMap = Get-LocaleMap -Root $AppResRoot -LocaleDirectory $localeDirectory

    foreach ($key in $expectedKeys) {
        if (-not $featureMap.ContainsKey($key)) {
            $missing.Add("${localeDirectory}:$key")
            continue
        }

        $featureValue = $featureMap[$key]
        $defaultValue = $featureDefault[$key]
        if (-not $appMap.ContainsKey($key)) {
            if ($featureValue -eq $defaultValue) {
                $sourceFallback.Add("${localeDirectory}:$key")
            } else {
                $valueMismatch.Add("${localeDirectory}:$key (source locale falls back to default)")
            }
        } elseif ($featureValue -ne $appMap[$key]) {
            $valueMismatch.Add("${localeDirectory}:$key")
        }

        $defaultTokens = ((Get-FormatTokens $defaultValue) -join "`u{1f}")
        $featureTokens = ((Get-FormatTokens $featureValue) -join "`u{1f}")
        if ($defaultTokens -ne $featureTokens) {
            $formatMismatch.Add("${localeDirectory}:$key")
        }
    }

    foreach ($key in @($featureMap.Keys | Where-Object { $_ -notin $expectedKeys } | Sort-Object)) {
        $unexpected.Add("${localeDirectory}:$key")
    }
}

$report = @(
    "Catalog locale audit",
    "featureRoot=$FeatureResRoot",
    "appRoot=$AppResRoot",
    "locales=$($localeDirectories -join ',')",
    "expectedKeys=$($expectedKeys.Count)",
    "missing=$($missing.Count)",
    "valueMismatch=$($valueMismatch.Count)",
    "formatMismatch=$($formatMismatch.Count)",
    "unexpected=$($unexpected.Count)",
    "sourceFallbackAllowed=$($sourceFallback.Count)"
)
if ($missing.Count) { $report += "-- missing --"; $report += $missing }
if ($valueMismatch.Count) { $report += "-- valueMismatch --"; $report += $valueMismatch }
if ($formatMismatch.Count) { $report += "-- formatMismatch --"; $report += $formatMismatch }
if ($unexpected.Count) { $report += "-- unexpected --"; $report += $unexpected }
if ($sourceFallback.Count) { $report += "-- sourceFallbackAllowed --"; $report += $sourceFallback }
$report | Write-Output

if ($missing.Count -or $valueMismatch.Count -or $formatMismatch.Count -or $unexpected.Count) { exit 1 }
exit 0
