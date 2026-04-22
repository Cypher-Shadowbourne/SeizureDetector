param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot,

    [string]$AssetRoot = "C:\Users\pbess\Desktop\Asset Creator\App_Brand_Kit"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-WarnMsg {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[ OK ] $Message" -ForegroundColor Green
}

function Ensure-Directory {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Get-AndroidModules {
    param([string]$Root)

    $manifests = Get-ChildItem -Path $Root -Recurse -File -Filter "AndroidManifest.xml" |
        Where-Object {
            $_.FullName -match [regex]::Escape("\src\main\AndroidManifest.xml")
        }

    $modules = @()

    foreach ($manifest in $manifests) {
        $moduleMainDir = Split-Path -Parent $manifest.FullName
        $srcDir = Split-Path -Parent $moduleMainDir
        $moduleDir = Split-Path -Parent $srcDir
        $moduleName = Split-Path -Leaf $moduleDir
        $resDir = Join-Path $moduleMainDir "res"

        $isWear = $false
        $manifestText = Get-Content -LiteralPath $manifest.FullName -Raw

        if ($moduleName -match '(?i)(wear|watch)') {
            $isWear = $true
        }
        elseif ($manifestText -match '(?i)com\.google\.android\.wearable') {
            $isWear = $true
        }
        elseif ($manifestText -match '(?i)android\.hardware\.type\.watch') {
            $isWear = $true
        }

        $modules += [PSCustomObject]@{
            ModuleName    = $moduleName
            ModuleDir     = $moduleDir
            MainDir       = $moduleMainDir
            ResDir        = $resDir
            ManifestPath  = $manifest.FullName
            IsWear        = $isWear
        }
    }

    return $modules | Sort-Object ModuleDir -Unique
}

function Get-AssetCandidates {
    param([string]$Root)

    $allFiles = Get-ChildItem -Path $Root -Recurse -File |
        Where-Object { $_.Extension -match '^\.(png|webp|xml)$' }

    $candidates = foreach ($file in $allFiles) {
        $full = $file.FullName
        $name = $file.Name.ToLowerInvariant()
        $dir  = $file.DirectoryName.ToLowerInvariant()

        $density = $null
        if ($full -match '(?i)\\(mipmap|drawable)-(mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)\\') {
            $density = $Matches[2].ToLowerInvariant()
        }
        elseif ($full -match '(?i)(^|[^a-z])(mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)([^a-z]|$)') {
            $density = $Matches[2].ToLowerInvariant()
        }

        $resourceType = $null
        if ($full -match '(?i)\\(mipmap|drawable)-(?:mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)\\') {
            $resourceType = $Matches[1].ToLowerInvariant()
        }
        elseif ($dir -match '(?i)\\(mipmap|drawable)\\') {
            $resourceType = $Matches[1].ToLowerInvariant()
        }

        $logicalName = $null
        if ($name -match '^ic_launcher_foreground\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher_foreground'
        }
        elseif ($name -match '^ic_launcher_monochrome\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher_monochrome'
        }
        elseif ($name -match '^ic_launcher_round\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher_round'
        }
        elseif ($name -match '^ic_launcher\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher'
        }
        elseif ($name -match '^launcher_foreground\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher_foreground'
        }
        elseif ($name -match '^launcher_monochrome\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher_monochrome'
        }
        elseif ($name -match '^launcher_round\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher_round'
        }
        elseif ($name -match '^launcher\.(png|webp|xml)$') {
            $logicalName = 'ic_launcher'
        }

        $assetTarget = "generic"
        if ($full -match '(?i)(wear|watch)') {
            $assetTarget = "wear"
        }
        elseif ($full -match '(?i)(phone|mobile|handset)') {
            $assetTarget = "phone"
        }

        [PSCustomObject]@{
            FullPath      = $file.FullName
            FileName      = $file.Name
            Extension     = $file.Extension.ToLowerInvariant()
            Density       = $density
            ResourceType  = $resourceType
            LogicalName   = $logicalName
            AssetTarget   = $assetTarget
        }
    }

    return $candidates
}

function Select-BestAsset {
    param(
        [array]$Candidates,
        [string]$LogicalName,
        [string]$Density,
        [string]$ModuleType
    )

    $matches = $Candidates | Where-Object {
        $_.LogicalName -eq $LogicalName -and $_.Density -eq $Density
    }

    if (-not $matches -or $matches.Count -eq 0) {
        return $null
    }

    $preferred = @()

    if ($ModuleType -eq "wear") {
        $preferred = $matches | Where-Object { $_.AssetTarget -eq "wear" }
        if (-not $preferred -or $preferred.Count -eq 0) {
            $preferred = $matches | Where-Object { $_.AssetTarget -eq "generic" }
        }
        if (-not $preferred -or $preferred.Count -eq 0) {
            $preferred = $matches | Where-Object { $_.AssetTarget -eq "phone" }
        }
    }
    else {
        $preferred = $matches | Where-Object { $_.AssetTarget -eq "phone" }
        if (-not $preferred -or $preferred.Count -eq 0) {
            $preferred = $matches | Where-Object { $_.AssetTarget -eq "generic" }
        }
        if (-not $preferred -or $preferred.Count -eq 0) {
            $preferred = $matches | Where-Object { $_.AssetTarget -eq "wear" }
        }
    }

    if (-not $preferred -or $preferred.Count -eq 0) {
        $preferred = $matches
    }

    # Prefer mipmap-tagged sources first, then png/webp/xml in that order for raster icons.
    $preferred = $preferred | Sort-Object `
        @{ Expression = { if ($_.ResourceType -eq 'mipmap') { 0 } elseif ($_.ResourceType -eq 'drawable') { 1 } else { 2 } } }, `
        @{ Expression = { if ($_.Extension -eq '.png') { 0 } elseif ($_.Extension -eq '.webp') { 1 } else { 2 } } }, `
        FullPath

    return $preferred | Select-Object -First 1
}

function Select-AnyDensitylessAsset {
    param(
        [array]$Candidates,
        [string]$LogicalName,
        [string]$ModuleType
    )

    $matches = $Candidates | Where-Object {
        $_.LogicalName -eq $LogicalName -and -not $_.Density
    }

    if (-not $matches -or $matches.Count -eq 0) {
        return $null
    }

    $preferred = @()

    if ($ModuleType -eq "wear") {
        $preferred = $matches | Where-Object { $_.AssetTarget -eq "wear" }
        if (-not $preferred -or $preferred.Count -eq 0) {
            $preferred = $matches | Where-Object { $_.AssetTarget -eq "generic" }
        }
    }
    else {
        $preferred = $matches | Where-Object { $_.AssetTarget -eq "phone" }
        if (-not $preferred -or $preferred.Count -eq 0) {
            $preferred = $matches | Where-Object { $_.AssetTarget -eq "generic" }
        }
    }

    if (-not $preferred -or $preferred.Count -eq 0) {
        $preferred = $matches
    }

    $preferred = $preferred | Sort-Object FullPath
    return $preferred | Select-Object -First 1
}

function Copy-IconSetToModule {
    param(
        [pscustomobject]$Module,
        [array]$Candidates
    )

    $moduleType = if ($Module.IsWear) { "wear" } else { "phone" }
    $densities = @("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    $copied = 0

    Write-Info "Processing module '$($Module.ModuleName)' ($moduleType)"
    Ensure-Directory -Path $Module.ResDir

    foreach ($density in $densities) {
        $mipmapDir = Join-Path $Module.ResDir "mipmap-$density"
        Ensure-Directory -Path $mipmapDir

        $baseIcon = Select-BestAsset -Candidates $Candidates -LogicalName "ic_launcher" -Density $density -ModuleType $moduleType
        if ($baseIcon) {
            $dest = Join-Path $mipmapDir "ic_launcher$($baseIcon.Extension)"
            Copy-Item -LiteralPath $baseIcon.FullPath -Destination $dest -Force
            Write-Ok "Copied $($baseIcon.FileName) -> $dest"
            $copied++
        }

        $roundIcon = Select-BestAsset -Candidates $Candidates -LogicalName "ic_launcher_round" -Density $density -ModuleType $moduleType
        if ($roundIcon) {
            $dest = Join-Path $mipmapDir "ic_launcher_round$($roundIcon.Extension)"
            Copy-Item -LiteralPath $roundIcon.FullPath -Destination $dest -Force
            Write-Ok "Copied $($roundIcon.FileName) -> $dest"
            $copied++
        }
    }

    # Adaptive icon support: these commonly live in mipmap-anydpi-v26
    $anydpiV26 = Join-Path $Module.ResDir "mipmap-anydpi-v26"
    Ensure-Directory -Path $anydpiV26

    $foreground = Select-AnyDensitylessAsset -Candidates $Candidates -LogicalName "ic_launcher_foreground" -ModuleType $moduleType
    if ($foreground) {
        $dest = Join-Path $anydpiV26 "ic_launcher_foreground$($foreground.Extension)"
        Copy-Item -LiteralPath $foreground.FullPath -Destination $dest -Force
        Write-Ok "Copied $($foreground.FileName) -> $dest"
        $copied++
    }

    $monochrome = Select-AnyDensitylessAsset -Candidates $Candidates -LogicalName "ic_launcher_monochrome" -ModuleType $moduleType
    if ($monochrome) {
        $dest = Join-Path $anydpiV26 "ic_launcher_monochrome$($monochrome.Extension)"
        Copy-Item -LiteralPath $monochrome.FullPath -Destination $dest -Force
        Write-Ok "Copied $($monochrome.FileName) -> $dest"
        $copied++
    }

    # Some kits contain XML adaptive icon descriptors already
    $launcherXml = $Candidates | Where-Object {
        $_.LogicalName -eq "ic_launcher" -and $_.Extension -eq ".xml" -and -not $_.Density
    } | Select-Object -First 1

    if ($launcherXml) {
        $dest = Join-Path $anydpiV26 "ic_launcher.xml"
        Copy-Item -LiteralPath $launcherXml.FullPath -Destination $dest -Force
        Write-Ok "Copied $($launcherXml.FileName) -> $dest"
        $copied++
    }

    $launcherRoundXml = $Candidates | Where-Object {
        $_.LogicalName -eq "ic_launcher_round" -and $_.Extension -eq ".xml" -and -not $_.Density
    } | Select-Object -First 1

    if ($launcherRoundXml) {
        $dest = Join-Path $anydpiV26 "ic_launcher_round.xml"
        Copy-Item -LiteralPath $launcherRoundXml.FullPath -Destination $dest -Force
        Write-Ok "Copied $($launcherRoundXml.FileName) -> $dest"
        $copied++
    }

    if ($copied -eq 0) {
        Write-WarnMsg "No matching icon assets found for module '$($Module.ModuleName)'."
    }
    else {
        Write-Info "Finished module '$($Module.ModuleName)' with $copied copied file(s)."
    }
}

# ---------------------------
# Main
# ---------------------------

if (-not (Test-Path -LiteralPath $ProjectRoot)) {
    throw "ProjectRoot does not exist: $ProjectRoot"
}

if (-not (Test-Path -LiteralPath $AssetRoot)) {
    throw "AssetRoot does not exist: $AssetRoot"
}

Write-Info "Scanning Android project: $ProjectRoot"
$modules = Get-AndroidModules -Root $ProjectRoot

if (-not $modules -or $modules.Count -eq 0) {
    throw "No Android modules with src\main\AndroidManifest.xml were found under: $ProjectRoot"
}

Write-Info "Found $($modules.Count) module(s):"
$modules | ForEach-Object {
    $kind = if ($_.IsWear) { "Wear OS" } else { "Android phone/tablet" }
    Write-Host " - $($_.ModuleName) [$kind]" -ForegroundColor Gray
}

Write-Info "Scanning asset source folder: $AssetRoot"
$candidates = Get-AssetCandidates -Root $AssetRoot

if (-not $candidates -or $candidates.Count -eq 0) {
    throw "No candidate icon assets (.png, .webp, .xml) were found under: $AssetRoot"
}

$namedCandidates = $candidates | Where-Object { $_.LogicalName }
if (-not $namedCandidates -or $namedCandidates.Count -eq 0) {
    Write-WarnMsg "No files matched expected Android launcher icon names."
    Write-WarnMsg "Expected names include ic_launcher, ic_launcher_round, ic_launcher_foreground, ic_launcher_monochrome."
    Write-WarnMsg "The script will stop here because it cannot safely infer the mapping."
    exit 1
}

Write-Info "Found $($namedCandidates.Count) candidate launcher asset(s)."

foreach ($module in $modules) {
    Copy-IconSetToModule -Module $module -Candidates $namedCandidates
}

Write-Ok "Done."