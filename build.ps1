#!/usr/bin/env pwsh
param(
    [string]$OutDir,
    [string]$SrcDir
)
$ErrorActionPreference = "Stop"

$PluginDir = Split-Path -Parent $PSCommandPath
$PluginId = "com.nzt.edt.extdumper"
$PluginVersion = "1.0.0"
$FeatureId = "$PluginId.feature"
$FeatureVersion = "1.0.0"

if (-not $SrcDir) { $SrcDir = Join-Path $PluginDir "src" }
if (-not $OutDir) { $OutDir = Join-Path $PluginDir "dist" }
$TargetDir = Join-Path $PluginDir "target"

# ---------- 1. Find EDT ----------
$edtHomeCandidates = @(
    "C:\Program Files\1C\1CE\components",
    "C:\Program Files (x86)\1C\1CE\components"
)
$edtHome = $null
foreach ($base in $edtHomeCandidates) {
    if (Test-Path $base) {
        $dirs = Get-ChildItem $base -Directory -Name | Where-Object { $_ -match "1c-edt" }
        if ($dirs) { $edtHome = Join-Path $base $dirs[0]; break }
    }
}
if (-not $edtHome) { $edtHome = $env:EDT_HOME }
if (-not $edtHome -or -not (Test-Path $edtHome)) { Write-Error "EDT not found"; exit 1 }
$pluginsDir = Join-Path $edtHome "plugins"

# ---------- 2. Classpath (wildcard — javac expands * to all jars) ----------
$classpath = "$pluginsDir\*"

# ---------- 3. Compile ----------
if (Test-Path $TargetDir) { Remove-Item $TargetDir -Recurse -Force }
$classesOut = Join-Path $TargetDir "classes"
New-Item -ItemType Directory -Path $classesOut -Force | Out-Null

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "javac" }
$javac = Join-Path $javaHome "bin\javac"
$javaFiles = Get-ChildItem $SrcDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
Write-Output "Compiling $($javaFiles.Count) source files..."
& $javac --release 17 -cp $classpath -d $classesOut -sourcepath $SrcDir $javaFiles 2>&1
if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed"; exit 1 }
Write-Output "Compilation OK"

# ---------- 4. Plugin JAR ----------
$jarStage = Join-Path $TargetDir "jar-stage"
New-Item -ItemType Directory -Path $jarStage -Force | Out-Null
Copy-Item "$classesOut\*" $jarStage -Recurse -Force

# Copy META-INF/MANIFEST.MF
$metaInfStage = Join-Path $jarStage "META-INF"
New-Item -ItemType Directory -Path $metaInfStage -Force | Out-Null
Copy-Item (Join-Path $PluginDir "META-INF\MANIFEST.MF") $metaInfStage -Force

# Copy plugin.xml and plugin.properties
Copy-Item (Join-Path $PluginDir "plugin.xml") $jarStage -Force
Copy-Item (Join-Path $PluginDir "plugin.properties") $jarStage -Force

# Copy icons
$iconsSource = Join-Path $PluginDir "icons"
$iconsDest = Join-Path $jarStage "icons"
if (Test-Path $iconsSource) { Copy-Item $iconsSource $iconsDest -Recurse -Force }

$jarFile = Join-Path $TargetDir "${PluginId}_${PluginVersion}.jar"
Push-Location $jarStage
& "$($javaHome)\bin\jar" cfm $jarFile "META-INF\MANIFEST.MF" .
Pop-Location
Write-Output "Plugin JAR: $jarFile ($((Get-Item $jarFile).Length) bytes)"

# ---------- 5. Feature JAR ----------
$featureDir = Join-Path $TargetDir "feature"
New-Item -ItemType Directory -Path $featureDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $featureDir "META-INF") -Force | Out-Null
@"
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-Name: EDT ExtDumper Feature
Bundle-SymbolicName: $FeatureId;singleton:=true
Bundle-Version: $FeatureVersion
Bundle-Vendor: NZT
"@ | Set-Content (Join-Path $featureDir "META-INF\MANIFEST.MF") -Encoding Ascii
@"
<?xml version="1.0" encoding="UTF-8"?>
<feature id="$FeatureId" label="EDT ExtDumper" version="$FeatureVersion" provider-name="NZT">
<description>
    Генерация .epf/.erf для внешних обработок и отчётов, в обход настроек автоматической выгрузки.
</description>
<plugin id="$PluginId" download-size="18" install-size="36" version="$PluginVersion" unpack="false"/>
</feature>
"@ | Set-Content (Join-Path $featureDir "feature.xml") -Encoding Utf8
$featureJar = Join-Path $TargetDir "${FeatureId}_${FeatureVersion}.jar"
Push-Location $featureDir
& "$($javaHome)\bin\jar" cfm $featureJar "META-INF\MANIFEST.MF" feature.xml
Pop-Location
Write-Output "Feature JAR: $featureJar ($((Get-Item $featureJar).Length) bytes)"

# ---------- 6. P2 repo using EDT publisher ----------
$p2repoDir = Join-Path $OutDir "p2repo"
if (Test-Path $p2repoDir) { Remove-Item $p2repoDir -Recurse -Force }
$1cedtc = Get-ChildItem $edtHome -Recurse -Filter "1cedtc.exe" | Select-Object -First 1 -ExpandProperty FullName
if (-not $1cedtc) { Write-Error "1cedtc.exe not found"; exit 1 }
$p2PluginsDir = Join-Path $p2repoDir "plugins"
$p2FeaturesDir = Join-Path $p2repoDir "features"
New-Item -ItemType Directory -Path $p2PluginsDir -Force | Out-Null
New-Item -ItemType Directory -Path $p2FeaturesDir -Force | Out-Null
Copy-Item $jarFile $p2PluginsDir
Copy-Item $featureJar $p2FeaturesDir
$p2repoUri = "file:/$($p2repoDir -replace '\\', '/')"
Write-Output "Running FeaturesAndBundlesPublisher..."
& $1cedtc -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -source "$p2repoDir" -metadataRepository "$p2repoUri" -artifactRepository "$p2repoUri" -publishArtifacts -compress
if ($LASTEXITCODE -ne 0) { Write-Warning "Publisher returned $LASTEXITCODE" }
# Publisher leaves junk dirs; clean them up
Remove-Item -LiteralPath (Join-Path $p2repoDir "content_xml") -Recurse -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $p2repoDir "artifacts_xml") -Recurse -ErrorAction SilentlyContinue

# ---------- 7. Inject category into content.xml ----------
$contentJar = Join-Path $p2repoDir "content.jar"
if (Test-Path $contentJar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $tmp = Join-Path $TargetDir "content_extract"
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    $z = [System.IO.Compression.ZipFile]::OpenRead($contentJar)
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($z.Entries[0], (Join-Path $tmp "content.xml"), $true)
    $z.Dispose()
    $contentXml = Get-Content (Join-Path $tmp "content.xml") -Raw
    $catId = "$PluginId.category"
    $catPattern = [regex]::Escape("<unit id='$catId'")
    if ($contentXml -notmatch $catPattern) {
        $categoryUnit = @"
    <unit id='$PluginId.category' version='1.0.0' singleton='false'>
      <properties size='2'>
        <property name='org.eclipse.equinox.p2.name' value='NZT Tools'/>
        <property name='org.eclipse.equinox.p2.type.category' value='true'/>
      </properties>
      <provides size='1'>
        <provided namespace='org.eclipse.equinox.p2.iu' name='$PluginId.category' version='1.0.0'/>
      </provides>
      <requires size='1'>
        <required namespace='org.eclipse.equinox.p2.iu' name='${PluginId}.feature.feature.group' range='[1.0.0,1.0.0]'/>
      </requires>
      <touchpoint id='null' version='0.0.0'/>
    </unit>

"@
        $sizeMatch = [regex]::Match($contentXml, "<units size='(\d+)'")
        if ($sizeMatch.Success) {
            $oldSize = [int]$sizeMatch.Groups[1].Value
            $contentXml = $contentXml -replace "<units size='$oldSize'>", "<units size='$($oldSize + 1)'>"
        }
        $contentXml = $contentXml -replace '</units>', "$categoryUnit</units>"
    }
    Set-Content (Join-Path $tmp "content.xml") $contentXml -NoNewline
    Remove-Item $contentJar -Force
    Set-Location $tmp
    & "$($javaHome)\bin\jar" cfM $contentJar "content.xml"
    Set-Location $PluginDir
    Remove-Item $tmp -Recurse -Force
}

# ---------- 8. p2.index ----------
$idx = @" 
version=1
metadata.repository.factory.order= content.jar
artifact.repository.factory.order= artifacts.jar
"@
[System.IO.File]::WriteAllText((Join-Path $p2repoDir "p2.index"), $idx, [System.Text.UTF8Encoding]::new($false))

# ---------- 9. ZIP of P2 repo (for Install New Software → Archive) ----------
$zipFile = Join-Path $OutDir "edt_extdumper_${PluginVersion}.zip"
if (Test-Path $zipFile) { Remove-Item $zipFile -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($zipFile, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $p2repoDir -Recurse -File | ForEach-Object {
        $entryName = $_.FullName.Substring($p2repoDir.Length + 1) -replace '\\', '/'
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $entryName) | Out-Null
    }
} finally {
    $archive.Dispose()
}
Write-Output "Plugin ZIP (P2 archive): $zipFile ($((Get-Item $zipFile).Length) bytes)"

Write-Output ""
Write-Output "=== BUILD COMPLETE ==="
Write-Output "Plugin JAR: $jarFile"
Write-Output "Plugin ZIP: $zipFile"
Write-Output "P2 repo: $p2repoDir"

