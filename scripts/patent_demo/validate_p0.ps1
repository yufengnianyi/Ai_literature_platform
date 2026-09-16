param(
    [Parameter(Mandatory)][string]$ArtifactPath,
    [Parameter(Mandatory)][string]$RecordsPath,
    [string]$GoldPath = "$PSScriptRoot/../../src/test/resources/patent/CN106857590B-table1-gold.json"
)
$ErrorActionPreference = 'Stop'
$artifact = (Resolve-Path -LiteralPath $ArtifactPath).Path
$gold = Get-Content -LiteralPath $GoldPath -Raw | ConvertFrom-Json
$response = Get-Content -LiteralPath $RecordsPath -Raw | ConvertFrom-Json
$records = @($response.data.items)
$manifest = Get-Content -LiteralPath (Join-Path $artifact 'patent-table-manifest.json') -Raw | ConvertFrom-Json
$tables = @(Get-Content -LiteralPath (Join-Path $artifact 'tables.jsonl') | Where-Object { $_.Trim() } | ConvertFrom-Json)
$table = $tables[0]
$errors = [System.Collections.Generic.List[string]]::new()
$checks = [System.Collections.Generic.List[object]]::new()
if ($records.Count -ne $gold.rows.Count) { $errors.Add('Record count differs from independent annotation') }
if (!$manifest.complete -or $manifest.tables[0].readStatus -ne 'VERIFIED') { $errors.Add('Table is not verified') }
if ($table.rows.Count -ne $gold.rows.Count) { $errors.Add('Native table row count differs') }
$ecColumn = @(0..($table.headers.Count - 1) | Where-Object { $table.headers[$_].Normalize([Text.NormalizationForm]::FormKC) -match 'EC\s*50' })
$ctcColumn = @(0..($table.headers.Count - 1) | Where-Object { $table.headers[$_] -match 'CTC' })
if ($ecColumn.Count -ne 1 -or $ctcColumn.Count -ne 1) { throw 'Missing or ambiguous metric headers' }
for ($i = 0; $i -lt $gold.rows.Count; $i++) {
    $expected = $gold.rows[$i]
    $matched = @($records | Where-Object {
        $name = $_.cells[0].Normalize([Text.NormalizationForm]::FormKC) -replace '\s', ''
        if ($null -eq $expected.ratio) { $name -ceq $expected.treatment }
        else { $name.Contains($expected.treatment) -and $name -match ('(?<![0-9])' + [regex]::Escape($expected.ratio) + '(?![0-9])') }
    })
    $rowErrors = [System.Collections.Generic.List[string]]::new()
    if ($table.rows[$i][$ecColumn[0]] -cne $expected.ec50) { $rowErrors.Add('Native EC50 mismatch') }
    if ($null -ne $expected.ctc -and $table.rows[$i][$ctcColumn[0]] -cne $expected.ctc) { $rowErrors.Add('Native CTC mismatch') }
    if ($matched.Count -ne 1) { $rowErrors.Add('Treatment missing or duplicated') }
    else {
        $record = $matched[0]
        $activity = $record.cells[7].Normalize([Text.NormalizationForm]::FormKC)
        $ec = [regex]::Match($activity, 'EC\s*50\s*[:=]?\s*([0-9]+\.[0-9]+)')
        $ctc = [regex]::Match(($activity + ';' + $record.cells[13]), 'CTC\s*[:=]?\s*([0-9]+\.[0-9]+)')
        if (!$ec.Success -or $ec.Groups[1].Value -cne $expected.ec50 -or !$activity.Contains($gold.unit)) { $rowErrors.Add('Record EC50/unit mismatch') }
        if ($null -ne $expected.ctc -and (!$ctc.Success -or $ctc.Groups[1].Value -cne $expected.ctc)) { $rowErrors.Add('Record CTC mismatch') }
        if ($null -eq $expected.ctc -and ($ctc.Success -or $record.cells[13])) { $rowErrors.Add('Single agent has mixture synergy') }
        if ($record.cells.Count -ne 16 -or $record.cells[5] -or $record.validationStatus -ne 'INVALID') { $rowErrors.Add('Schema or missing-Latin status mismatch') }
        if (!$activity.Contains($gold.pathogenOriginal)) { $rowErrors.Add('Original target absent') }
        $compact = $activity -replace '\s', ''
        if (!$compact.Contains($gold.methodTime) -or !$compact.Contains($gold.tableTime)) { $rowErrors.Add('Source duration lost') }
        if ($activity -notmatch 'conflict|inconsisten|contradict|冲突|不一致') { $rowErrors.Add('Time conflict not explicit') }
        if ($record.cells[8] -or @($record.cells[9..12] | Where-Object { $_ }).Count) { $rowErrors.Add('Unsupported positive control or demonstrated field') }
        $anchors = @($record.anchors | Where-Object { $_.chunkId -match ':patent-table:T1$' -and $_.paragraphIndex -eq $gold.sourcePage })
        $quote = '| ' + ($table.rows[$i] -join ' | ') + ' |'
        if ($anchors.Count -ne 1 -or $anchors[0].exactQuote -cne $quote) { $rowErrors.Add('Original table-row anchor mismatch') }
    }
    foreach ($error in $rowErrors) { $errors.Add("Row $($i + 1): $error") }
    $checks.Add([pscustomobject]@{row=$i+1;treatment=$expected.treatment;ratio=$expected.ratio;ec50=$expected.ec50;ctc=$expected.ctc;passed=($rowErrors.Count -eq 0);errors=@($rowErrors)})
}
$result = [pscustomobject]@{publicationNumber=$gold.publicationNumber;passed=($errors.Count -eq 0);expectedRows=$gold.rows.Count;actualRows=$records.Count;goldPath=(Resolve-Path $GoldPath).Path;sourcePage=$gold.sourcePage;checks=@($checks);errors=@($errors)}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $artifact 'patent-p0-acceptance.json') -Encoding utf8
$checks | Format-Table row,treatment,ratio,ec50,ctc,passed -AutoSize
if ($errors.Count) { throw ($errors -join '; ') }
