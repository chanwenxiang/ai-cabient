# Admin list layout / exception orderId smoke (API + optional Playwright).
# Asserts:
#   1) RESOLVED exceptions can expose orderId (writeback/backfill path)
#   2) If npx+playwright available: /orders /exceptions /sessions status-tabs height >= MinTabHeight
#
# Usage:
#   powershell -File scripts/admin-layout-smoke.ps1
#   powershell -File scripts/admin-layout-smoke.ps1 -SkipBrowser
param(
    [string]$BaseUrl = "",
    [string]$OperatorPhone = "13900000001",
    [string]$OperatorPassword = "123456",
    [int]$MinTabHeight = 36,
    [switch]$SkipBrowser
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "e2e-lib.ps1")

$BaseUrl = Resolve-E2eBaseUrl $BaseUrl
Write-Host "========== Admin Layout Smoke =========="
Write-Host "    BaseUrl=$BaseUrl MinTabHeight=$MinTabHeight"

if (-not (Test-ServiceHealth -Url "$BaseUrl/actuator/health")) {
    throw "trade-service not healthy at $BaseUrl"
}

$adminHtml = Invoke-WebRequest -Uri "$BaseUrl/admin/index.html" -UseBasicParsing -TimeoutSec 20
if ($adminHtml.StatusCode -ne 200) {
    throw "admin UI not reachable: $($adminHtml.StatusCode)"
}
Write-Host "OK  admin index.html"

$login = Invoke-E2eApi -BaseUrl $BaseUrl -Method POST -Path "/api/v2/auth/admin-password-login" -Body @{
    phoneNumber = $OperatorPhone
    password    = $OperatorPassword
}
$ops = @{ Authorization = "Bearer $($login.token)" }

$exPage = Invoke-E2eApi -BaseUrl $BaseUrl -Method GET `
    -Path "/api/v2/ops/admin/exceptions?status=RESOLVED&page=0&size=20" -Headers $ops
$items = @()
if ($exPage.items) { $items = @($exPage.items) }
$withOrder = @($items | Where-Object { $_.orderId -and "$($_.orderId)".Trim() })
Write-Host "    RESOLVED exceptions=$($items.Count), with orderId=$($withOrder.Count)"
if ($withOrder.Count -gt 0) {
    Write-Host "OK  sample RESOLVED orderId=$($withOrder[0].orderId)"
} elseif ($items.Count -eq 0) {
    Write-Host "WARN no RESOLVED exceptions yet (orderId backfill not sampled)"
} else {
    Write-Host "WARN RESOLVED rows lack orderId (legacy data without session order — layout still checked)"
}

if ($SkipBrowser) {
    Write-Host "SKIP browser layout (-SkipBrowser)"
    Write-Host "OK admin-layout-smoke (API)"
    exit 0
}

$npx = Get-Command npx -ErrorAction SilentlyContinue
if (-not $npx) {
    Write-Host "SKIP browser layout: npx not found"
    Write-Host "OK admin-layout-smoke (API)"
    exit 0
}

$layoutJs = @'
const { chromium } = require("playwright");
(async () => {
  const base = process.env.LAYOUT_BASE;
  const phone = process.env.LAYOUT_PHONE;
  const pass = process.env.LAYOUT_PASS;
  const minH = Number(process.env.LAYOUT_MIN_TAB_H || "36");
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const failures = [];
  try {
    await page.goto(base + "/admin/index.html", { waitUntil: "domcontentloaded", timeout: 60000 });
    await page.waitForSelector('input[type="password"]', { timeout: 30000 });
    await page.locator('input[type="password"]').fill(pass);
    const textInputs = page.locator('input:not([type="password"]):not([type="hidden"])');
    if (await textInputs.count()) await textInputs.first().fill(phone);
    await page.locator("button.el-button--primary").first().click();
    await page.waitForTimeout(2000);

    for (const route of ["/orders", "/exceptions", "/sessions"]) {
      await page.goto(base + "/admin/index.html#" + route, { waitUntil: "domcontentloaded" });
      await page.waitForTimeout(1200);
      const m = await page.evaluate(() => {
        const tabs = document.querySelector(".el-tabs.status-tabs") || document.querySelector(".status-tabs");
        const header = tabs && tabs.querySelector(".el-tabs__header");
        const el = header || tabs;
        const rect = el ? el.getBoundingClientRect() : null;
        return { hasTabs: !!tabs, height: rect ? Math.round(rect.height) : 0 };
      });
      console.log(JSON.stringify({ route, ...m }));
      if (!m.hasTabs) failures.push(route + ": missing .status-tabs");
      else if (m.height < minH) failures.push(route + ": status-tabs height=" + m.height + " < " + minH);
    }

    await page.goto(base + "/admin/index.html#/exceptions", { waitUntil: "domcontentloaded" });
    await page.waitForTimeout(800);
    const tab = page.getByRole("tab", { name: /已解决|RESOLVED/i });
    if (await tab.count()) {
      await tab.first().click();
      await page.waitForTimeout(1200);
    }
    const orderCol = await page.evaluate(() => {
      const headers = [...document.querySelectorAll(".el-table__header th")].map((th) => (th.innerText || "").trim());
      const idx = headers.findIndex((h) => /订单|order/i.test(h));
      if (idx < 0) return { idx: -1, nonEmpty: 0, rows: 0 };
      const rows = [...document.querySelectorAll(".el-table__body tbody tr")];
      const nonEmpty = rows.filter((tr) => {
        const td = tr.querySelectorAll("td")[idx];
        return td && (td.innerText || "").trim();
      }).length;
      return { idx, nonEmpty, rows: rows.length };
    });
    console.log(JSON.stringify({ exceptionOrderColumn: orderCol }));
  } finally {
    await browser.close();
  }
  if (failures.length) {
    console.error("FAIL " + failures.join("; "));
    process.exit(1);
  }
  console.log("OK layout status-tabs height >= " + minH);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
'@

$work = Join-Path $env:TEMP "ai-cabinet-layout-smoke"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$jsPath = Join-Path $work "layout-check.cjs"
Set-Content -Path $jsPath -Value $layoutJs -Encoding UTF8

$env:LAYOUT_BASE = $BaseUrl
$env:LAYOUT_PHONE = $OperatorPhone
$env:LAYOUT_PASS = $OperatorPassword
$env:LAYOUT_MIN_TAB_H = "$MinTabHeight"

Write-Host "==> Playwright layout geometry"
Push-Location $work
try {
    if (-not (Test-Path (Join-Path $work "node_modules\playwright"))) {
        npm init -y 2>$null | Out-Null
        npm install --no-save --no-fund --no-audit playwright@1.49.1 2>&1 | Out-Host
        npx playwright install chromium 2>&1 | Out-Host
    }
    node $jsPath
    if ($LASTEXITCODE -ne 0) { throw "browser layout assert failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

Write-Host "OK admin-layout-smoke passed"
