async (page) => {
  const paths = [
    '/reports',
    '/finance',
    '/devices',
    '/devices/CAB-001',
    '/sessions',
    '/upload-queue',
    '/orders',
    '/skus',
    '/recognition-demo',
    '/disputes',
    '/exceptions',
    '/replenishment',
    '/merchants',
    '/reconciliation',
    '/warehouse',
    '/recharges',
    '/users',
    '/risk',
    '/promotions',
    '/coupons',
    '/feedback',
    '/operators',
    '/roles',
    '/menus',
    '/dicts',
    '/system-configs',
    '/announcements',
    '/audit',
    '/profile'
  ];
  const results = [];
  for (const path of paths) {
    const errors = [];
    const failedResponses = [];
    const onConsole = (message) => {
      if (message.type() === 'error') errors.push(message.text());
    };
    const onResponse = (response) => {
      if (response.status() >= 400) {
        failedResponses.push(`${response.status()} ${new URL(response.url()).pathname}`);
      }
    };
    page.on('console', onConsole);
    page.on('response', onResponse);
    await page.goto(`http://127.0.0.1:3001/#${path}`);
    await page.waitForTimeout(800);
    const pageState = await page.evaluate(() => ({
      heading:
        document
          .querySelector('h1,h2,.page-card-head__title,.card-head .title,.el-card__header')
          ?.textContent?.trim()
          .slice(0, 80) || '',
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      buttonCount: [...document.querySelectorAll('button')].filter(
        (button) => button instanceof HTMLElement && button.offsetParent !== null
      ).length,
      visibleError:
        [...document.querySelectorAll('[role="alert"],.el-alert--error,.el-result__subtitle')]
          .map((node) => node.textContent?.trim())
          .filter(Boolean)
          .join(' | ')
          .slice(0, 200)
    }));
    page.off('console', onConsole);
    page.off('response', onResponse);
    results.push({
      path,
      ...pageState,
      errors: [...new Set(errors)],
      failedResponses: [...new Set(failedResponses)]
    });
  }
  return results;
}
