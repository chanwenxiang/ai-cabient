# D1 A/B: inject drift to prove MemberServiceDepositLevelTest can actually go red.
# Injection point: the OR condition in MemberService.calculateMemberLevel().
#
# ASCII-ONLY BY DESIGN -- do not add non-ASCII text here.
# Reason: Windows PowerShell 5.1 parses a BOM-less .ps1 as ANSI(GBK); UTF-8 CJK bytes
# then corrupt string quoting (observed: 3 parse errors -> the whole script silently
# never runs, and the `*>` redirection file is never even created).

$root = 'C:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet'
Set-Location $root
$ErrorActionPreference = 'Continue'

$ms  = Join-Path $root 'services\trade-service\src\main\java\com\aicabinet\trade\service\MemberService.java'
$bak = Join-Path $root '.tmp\MemberService.java.ab.bak'

$orig  = 'if (matchesSpentTier(rule, totalSpent) || matchesRechargeTier(rule, netRecharge)) {'
$drift = 'if (matchesSpentTier(rule, totalSpent)) {'

$text = [System.IO.File]::ReadAllText($ms)
if (-not $text.Contains($orig)) {
  'ANCHOR-MISSING: injection anchor not found, A/B is void' |
    Out-File -FilePath (Join-Path $root '.tmp\ab-anchor-missing.txt') -Encoding UTF8
  exit 2
}
[System.IO.File]::WriteAllText($bak, $text)

function Invoke-DepositTest {
  param([string]$Tag)
  $rep = Join-Path $root 'services\trade-service\target\surefire-reports'
  $log = Join-Path $root ".tmp\ab-$Tag.log"
  $a = @('-B', '-pl', 'services/trade-service', '-am', 'clean', 'test',
         '-Dtest=MemberServiceDepositLevelTest',
         '-Dsurefire.failIfNoSpecifiedTests=false')
  & mvn @a *> $log
  $code = $LASTEXITCODE
  $tc = 0; $fa = 0; $er = 0
  if ([System.IO.Directory]::Exists($rep)) {
    Get-ChildItem -Path $rep -Filter 'TEST-*MemberServiceDepositLevelTest*.xml' | ForEach-Object {
      $t = [System.IO.File]::ReadAllText($_.FullName)
      $tc += ([regex]::Matches($t, '<testcase ')).Count
      $fa += ([regex]::Matches($t, '<failure')).Count
      $er += ([regex]::Matches($t, '<error')).Count
    }
  }
  return [pscustomobject]@{ code = $code; tc = $tc; fa = $fa; er = $er }
}

$lines = @()

# Arm A: inject drift
[System.IO.File]::WriteAllText($ms, $text.Replace($orig, $drift))
$driftText = [System.IO.File]::ReadAllText($ms)
$lines += ("A_INJECT   applied={0}" -f $driftText.Contains($drift))
$a1 = Invoke-DepositTest 'drift'
$lines += ("A_DRIFT    exit={0} testcase={1} failure={2} error={3}" -f $a1.code, $a1.tc, $a1.fa, $a1.er)

# Restore
[System.IO.File]::WriteAllText($ms, $text)
$back = [System.IO.File]::ReadAllText($ms)
$lines += ("RESTORE    anchor_present={0}" -f $back.Contains($orig))

# Arm B: restored must be green again
$b1 = Invoke-DepositTest 'restored'
$lines += ("B_RESTORED exit={0} testcase={1} failure={2} error={3}" -f $b1.code, $b1.tc, $b1.fa, $b1.er)

$driftRed = (($a1.code -ne 0) -or (($a1.fa + $a1.er) -gt 0))
$restoredGreen = ($b1.code -eq 0) -and (($b1.fa + $b1.er) -eq 0)
$lines += ("VERDICT    drift_red={0} restored_green={1}" -f $driftRed, $restoredGreen)

$lines | Out-File -FilePath (Join-Path $root '.tmp\ab-deposit-result.txt') -Encoding UTF8
$lines | Write-Output
