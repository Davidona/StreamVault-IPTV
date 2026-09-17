[CmdletBinding()]
param(
    [string]$EvidenceRoot = "validation/settings-redesign",
    [string]$DeviceSerial = "",
    [switch]$ProductionOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$appId = "com.streamvault.app.debug"
$activity = "com.streamvault.app.MainActivity"
$appTestId = "com.streamvault.app.debug.test"
$settingsTestId = "com.streamvault.feature.settings.test"
$runner = "androidx.test.runner.AndroidJUnitRunner"
$routeExtra = "com.streamvault.app.extra.EXTERNAL_ROUTE"
$appApk = Join-Path $repoRoot "app/build/outputs/apk/debug/app-debug.apk"
$appTestApk = Join-Path $repoRoot "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$settingsTestApk = Join-Path $repoRoot "feature/settings/build/outputs/apk/androidTest/debug/settings-debug-androidTest.apk"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceDir = Join-Path $repoRoot (Join-Path $EvidenceRoot $timestamp)
$script:uiDumpNumber = 0

function Resolve-Adb {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe"
    if (Test-Path $sdkAdb) { return $sdkAdb }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw "adb was not found in the Android SDK or PATH." }
    return $command.Source
}

$adb = Resolve-Adb

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $ErrorActionPreference = "Continue"
    $output = & $adb -s $script:serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): adb -s $script:serial $($Arguments -join ' ')`n$output"
    }
    return ($output | Out-String).TrimEnd()
}

function Save-AdbOutput {
    param([string]$Path, [string[]]$Arguments)
    $output = Invoke-Adb @Arguments
    $output | Set-Content -LiteralPath $Path -Encoding utf8
    return $output
}

function Get-UiDump {
    $script:uiDumpNumber++
    $remote = "/sdcard/streamvault-settings-window.xml"
    [void](Invoke-Adb shell uiautomator dump $remote)
    $local = Join-Path $evidenceDir ("ui-{0:D3}.xml" -f $script:uiDumpNumber)
    [void](Invoke-Adb pull $remote $local)
    return [xml](Get-Content -LiteralPath $local -Raw)
}

function Get-NodeLabel {
    param($Node)
    $text = [string]$Node.GetAttribute("text")
    $description = [string]$Node.GetAttribute("content-desc")
    if ($text) { return $text }
    if ($description) { return $description }
    foreach ($child in $Node.SelectNodes(".//node")) {
        $childText = [string]$child.GetAttribute("text")
        $childDescription = [string]$child.GetAttribute("content-desc")
        if ($childText) { return $childText }
        if ($childDescription) { return $childDescription }
    }
    return "<unlabelled>"
}

function Find-UiNode {
    param([xml]$Dump, [string]$Label)
    $matches = @($Dump.SelectNodes("//node") | Where-Object {
        $_.GetAttribute("text") -eq $Label -or $_.GetAttribute("content-desc") -eq $Label
    })
    if ($matches.Count -eq 0) {
        $matches = @($Dump.SelectNodes("//node") | Where-Object {
            $_.GetAttribute("text") -like "*$Label*" -or $_.GetAttribute("content-desc") -like "*$Label*"
        })
    }
    if ($matches.Count -eq 0) { return $null }
    $actionable = @($matches | Where-Object {
        $_.GetAttribute("clickable") -eq "true" -or $_.GetAttribute("focusable") -eq "true"
    })
    if ($actionable.Count -gt 0) { return $actionable[0] }
    return $matches[0]
}

function Wait-UiLabel {
    param([string]$Label, [int]$TimeoutSeconds = 12)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $dump = Get-UiDump
        $node = Find-UiNode $dump $Label
        if ($null -ne $node) { return @{ Dump = $dump; Node = $node } }
        Start-Sleep -Milliseconds 350
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for UI label '$Label'."
}

function Tap-UiLabel {
    param([string]$Label)
    $match = Wait-UiLabel $Label
    $bounds = [string]$match.Node.GetAttribute("bounds")
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw "Unable to parse bounds '$bounds' for '$Label'."
    }
    $x = [int](($matches[1] + $matches[3]) / 2)
    $y = [int](($matches[2] + $matches[4]) / 2)
    [void](Invoke-Adb shell input tap $x $y)
    Start-Sleep -Milliseconds 700
}

function Assert-Focus {
    param([string]$ExpectedLabel = "", [string[]]$ForbiddenLabels = @("Home"))
    $dump = Get-UiDump
    $focused = @($dump.SelectNodes("//node[@focused='true']"))
    if ($focused.Count -ne 1) {
        throw "Expected one focused node; found $($focused.Count)."
    }
    $label = Get-NodeLabel $focused[0]
    if ($ForbiddenLabels -contains $label) {
        throw "Focus incorrectly landed on '$label'."
    }
    if ($ExpectedLabel -and $focused[0].OuterXml -notlike "*$ExpectedLabel*") {
        throw "Expected focus on '$ExpectedLabel'; focused node was '$label'."
    }
    return $label
}

function Save-State {
    param([string]$Name)
    $safeName = $Name -replace '[^A-Za-z0-9._-]', '_'
    $remote = "/sdcard/$safeName.png"
    [void](Invoke-Adb shell screencap $remote)
    [void](Invoke-Adb pull $remote (Join-Path $evidenceDir "$safeName.png"))
    $dump = Get-UiDump
    $dump.Save((Join-Path $evidenceDir "$safeName.xml"))
    $focused = @($dump.SelectNodes("//node[@focused='true']"))
    $focusLabel = if ($focused.Count -eq 1) { Get-NodeLabel $focused[0] } else { "count=$($focused.Count)" }
    "$safeName`t$focusLabel" | Add-Content -LiteralPath (Join-Path $evidenceDir "focus.tsv") -Encoding utf8
}

function Run-Instrumentation {
    param([string]$Target, [string]$ClassName, [string]$OutputName)
    $outputPath = Join-Path $evidenceDir $OutputName
    $output = ""
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $output = Save-AdbOutput -Path $outputPath -Arguments @(
            "shell", "am", "instrument", "-w", "-r", "-e", "class", $ClassName, "$Target/$runner"
        )
        $frameworkRace = $output -match "Activity client record must not be null to execute transaction item"
        if (-not $frameworkRace) { break }
        Move-Item -LiteralPath $outputPath -Destination "$outputPath.framework-retry-$attempt.txt" -Force
        [void](Invoke-Adb shell am force-stop $Target)
        Start-Sleep -Seconds 3
    }
    if ($output -match "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=") {
        throw "Instrumentation failed for $ClassName. See $OutputName."
    }
    if ($output -notmatch "OK \(") {
        throw "Instrumentation did not report a passing JUnit result for $ClassName. See $OutputName."
    }
}

function Run-InstrumentationMethods {
    param([string]$Target, [string]$ClassName, [string[]]$Methods, [string]$OutputPrefix)
    foreach ($method in $Methods) {
        [void](Invoke-Adb shell am force-stop $Target)
        Start-Sleep -Seconds 1
        Run-Instrumentation $Target "$ClassName#$method" "$OutputPrefix-$method.txt"
        Start-Sleep -Seconds 1
    }
}

function Open-ProductionSettings {
    [void](Invoke-Adb shell am force-stop $settingsTestId)
    [void](Invoke-Adb shell am force-stop $appTestId)
    [void](Invoke-Adb shell am force-stop $appId)
    Start-Sleep -Seconds 2
    [void](Invoke-Adb -Arguments @(
        "shell", "am", "start", "-W", "-n", "$appId/$activity"
    ))
    [void](Wait-UiLabel "Settings" 25)
    [void](Invoke-Adb shell input keyevent KEYCODE_DPAD_UP)
    1..8 | ForEach-Object {
        [void](Invoke-Adb shell input keyevent KEYCODE_DPAD_RIGHT)
        Start-Sleep -Milliseconds 120
    }
    [void](Invoke-Adb shell input keyevent KEYCODE_ENTER)
    Start-Sleep -Milliseconds 700
    [void](Wait-UiLabel "Providers" 25)
}

function Exercise-Category {
    param([string]$Category, [string]$FirstPage = "", [int]$StepsFromProviders = 0)
    if ($StepsFromProviders -gt 0) {
        [void](Invoke-Adb shell input keyevent KEYCODE_DPAD_LEFT)
        Start-Sleep -Milliseconds 350
        1..$StepsFromProviders | ForEach-Object {
            [void](Invoke-Adb shell input keyevent KEYCODE_DPAD_DOWN)
            Start-Sleep -Milliseconds 350
        }
        [void](Invoke-Adb shell input keyevent KEYCODE_ENTER)
        Start-Sleep -Milliseconds 700
    }
    [void](Wait-UiLabel $Category)
    if ($FirstPage) {
        [void](Wait-UiLabel $FirstPage)
    }
    Save-State ("overview-" + $Category)
    if (-not $FirstPage) { return }

    [void](Invoke-Adb shell input keyevent KEYCODE_ENTER)
    Start-Sleep -Milliseconds 700
    [void](Wait-UiLabel ("Back to " + $Category))
    Save-State ("detail-" + $Category + "-" + $FirstPage)
    [void](Invoke-Adb shell input keyevent KEYCODE_BACK)
    Start-Sleep -Milliseconds 700
    [void](Wait-UiLabel $FirstPage)
}

New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null
@($appApk, $appTestApk, $settingsTestApk) | ForEach-Object {
    if (-not (Test-Path $_)) { throw "Required APK is missing: $_" }
}

$deviceLines = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
if ($DeviceSerial) {
    if ($deviceLines -notmatch "^$([regex]::Escape($DeviceSerial))\t") {
        throw "Requested device '$DeviceSerial' is not online."
    }
    $script:serial = $DeviceSerial
} else {
    if (@($deviceLines).Count -ne 1) {
        throw "Expected exactly one online device; found $(@($deviceLines).Count). Pass -DeviceSerial when intentional."
    }
    $script:serial = ($deviceLines[0] -split "\t")[0]
}

$identity = [ordered]@{
    timestampUtc = (Get-Date).ToUniversalTime().ToString("o")
    gitHead = (& git -C $repoRoot rev-parse HEAD).Trim()
    gitBranch = (& git -C $repoRoot branch --show-current).Trim()
    trackedDiffHash = ((& git -C $repoRoot diff --binary | & git hash-object --stdin).Trim())
    serial = $script:serial
    device = (Invoke-Adb shell getprop ro.product.model)
    api = (Invoke-Adb shell getprop ro.build.version.sdk)
    uiMode = (Invoke-Adb shell getprop ro.build.characteristics)
    appApkSha256 = (Get-FileHash $appApk -Algorithm SHA256).Hash
    appTestApkSha256 = (Get-FileHash $appTestApk -Algorithm SHA256).Hash
    settingsTestApkSha256 = (Get-FileHash $settingsTestApk -Algorithm SHA256).Hash
}
$changedSourceFiles = @(
    & git -C $repoRoot diff --name-only -- app core feature
    & git -C $repoRoot ls-files --others --exclude-standard -- app core feature
) | Sort-Object -Unique
$sourceManifest = foreach ($relativePath in $changedSourceFiles) {
    $absolutePath = Join-Path $repoRoot $relativePath
    if (Test-Path $absolutePath -PathType Leaf) {
        "$relativePath`t$((Get-FileHash $absolutePath -Algorithm SHA256).Hash)"
    }
}
$manifestPath = Join-Path $evidenceDir "changed-source-manifest.tsv"
$sourceManifest | Set-Content -LiteralPath $manifestPath -Encoding utf8
$identity.changedSourceManifestSha256 = (Get-FileHash $manifestPath -Algorithm SHA256).Hash
$identity.changedSourceFileCount = @($sourceManifest).Count
$identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidenceDir "identity.json") -Encoding utf8
(& git -C $repoRoot status --short) | Set-Content -LiteralPath (Join-Path $evidenceDir "git-status.txt") -Encoding utf8

[void](Invoke-Adb logcat -c)
if (-not $ProductionOnly) {
    [void](Invoke-Adb install -r $appApk)
    [void](Invoke-Adb install -r $settingsTestApk)
    Run-InstrumentationMethods $settingsTestId "com.streamvault.feature.settings.presentation.SettingsConnectedBehaviorTest" @(
    "settingsSections_areSelectableWithDpad_andRestoreFocusToSelectedSection",
    "settingsNavigation_inRtl_keepsSectionsAccessible",
    "settingsSearch_exposesAccessibleLabelAndCurrentValue",
    "settingsSearch_onTvBackLeavesEditingBeforeDismissingSearch",
    "settingsSearch_returnRestoresTheExactResultFocus",
    "nestedHeader_visibleBackIsSelectableAndInvokesItsParentReturn",
    "backupPreview_forwardsStrategyToggleAndConfirmCallbacks",
    "backupSelection_selectingItemForwardsUri_andUpdatesDialogState",
    "backupSelection_backDismissesOpenDialog",
    "parentalControlCard_reflectsPinState_andForwardsChangeAction",
    "pinDialog_acceptsFourDigitEntry_andPreservesErrorSemantics"
    ) "instrumentation-settings-behavior"
    Run-InstrumentationMethods $settingsTestId "com.streamvault.feature.settings.presentation.SettingsRowBehaviorTest" @(
    "switchHasOneActivationTargetAndChangesExactlyOnce",
    "disabledChoiceCannotInvokeItsCallback",
    "longChoiceValuesRemainReadableInANarrowColumn",
    "constrainedSettingsWidthKeepsTheContentVisible"
    ) "instrumentation-settings-rows"
    Run-InstrumentationMethods $settingsTestId "com.streamvault.feature.settings.navigation.SettingsRouteGraphBehaviorTest" @(
    "settingsRoute_decodesEncodedBackupUriThroughFeatureGraph",
    "parentalRoute_preservesLongProviderIdArgument",
    "parentalRoute_passesTopNavigationDestinationsToParentalContent",
    "parentalBack_returnsToSettingsInsteadOfHome"
    ) "instrumentation-settings-routes"
    [void](Invoke-Adb install -r $appTestApk)
    Run-InstrumentationMethods $appTestId "com.streamvault.app.ui.AppNavigationContractTest" @(
    "externalSearchCommandNavigatesOnceAndAcknowledges",
    "playerReturnCommandUsesTypedGuideDestination",
    "missingDetailReturnTargetRemovesDetailBeforeNavigating"
    ) "instrumentation-app-routes"

    $remoteEvidence = "/sdcard/Android/media/$settingsTestId/settings-validation"
    try { [void](Invoke-Adb pull $remoteEvidence (Join-Path $evidenceDir "connected-screenshots")) } catch {
        "No connected-test screenshot directory was available: $($_.Exception.Message)" |
            Set-Content -LiteralPath (Join-Path $evidenceDir "connected-screenshots-missing.txt") -Encoding utf8
    }
}

Open-ProductionSettings
Save-State "production-settings-root"

$matrix = @(
    @{ Category = "Providers"; Page = "Providers" },
    @{ Category = "Playback"; Page = "General" },
    @{ Category = "Live TV"; Page = "Channel list & guide" },
    @{ Category = "Movies & Series"; Page = "Library browsing" },
    @{ Category = "App & Remote"; Page = "Appearance & language" },
    @{ Category = "Privacy"; Page = "" },
    @{ Category = "Recording"; Page = "Recordings & schedule" },
    @{ Category = "Backup & Restore"; Page = "Device & USB backups" },
    @{ Category = "TV Guide"; Page = "" },
    @{ Category = "About"; Page = "Updates" }
)
for ($index = 0; $index -lt $matrix.Count; $index++) {
    if ($index -gt 0) { Open-ProductionSettings }
    $entry = $matrix[$index]
    Exercise-Category -Category $entry.Category -FirstPage $entry.Page -StepsFromProviders $index
}

Open-ProductionSettings
[void](Wait-UiLabel "Search settings")
Tap-UiLabel "Search settings"
[void](Wait-UiLabel "Search settings")
[void](Invoke-Adb shell input text "player")
[void](Wait-UiLabel "Default playback app")
Save-State "search-typed-results"
Tap-UiLabel "Default playback app"
[void](Wait-UiLabel "Back to Search settings")
Save-State "search-result-destination"
[void](Invoke-Adb shell input keyevent KEYCODE_BACK)
Start-Sleep -Milliseconds 700
[void](Assert-Focus -ExpectedLabel "Default playback app")
Save-State "search-result-restored"

Save-AdbOutput -Path (Join-Path $evidenceDir "logcat.txt") -Arguments @("logcat", "-d", "-v", "time") | Out-Null
$fatalMatches = Select-String -Path (Join-Path $evidenceDir "logcat.txt") -Pattern "FATAL EXCEPTION|ANR in com.streamvault.app.debug|Process com.streamvault.app.debug .* died"
if ($fatalMatches) {
    $fatalMatches | Set-Content -LiteralPath (Join-Path $evidenceDir "fatal-log-matches.txt") -Encoding utf8
    throw "Fatal app evidence was found in logcat."
}

"PASS" | Set-Content -LiteralPath (Join-Path $evidenceDir "RESULT.txt") -Encoding ascii
Write-Host "Settings redesign device verification passed. Evidence: $evidenceDir"
