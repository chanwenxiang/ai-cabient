/** 小程序环境配置：按微信 envVersion 切换 API 地址 */
let envVersion = 'develop';
try {
  envVersion = wx.getAccountInfoSync().miniProgram.envVersion || 'develop';
} catch (e) {
  /* 开发者工具外可能不可用 */
}

const CONFIGS = {
  develop: {
    baseUrl: 'http://localhost:8080',
    supportPhone: '400-888-0001'
  },
  trial: {
    baseUrl: 'https://staging-api.example.com',
    supportPhone: '400-888-0001'
  },
  release: {
    baseUrl: 'https://api.example.com',
    supportPhone: '400-888-0001'
  }
};

const cfg = CONFIGS[envVersion] || CONFIGS.develop;

module.exports = {
  ENV: envVersion,
  BASE_URL: cfg.baseUrl,
  SUPPORT_PHONE: cfg.supportPhone
};
