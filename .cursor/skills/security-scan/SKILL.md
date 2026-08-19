---
name: security-scan
description: >-
  代码安全/漏洞扫描路由：优先 security-best-practices，可选 NVIDIA SkillSpector。
  Use when security review, SQLi/XSS/密钥泄露检查, or 代码语法错误扫描/安全扫描.
---

# Security Scan

## 首选（已安装，免费）

**Skill:** `security-best-practices`（`~/.codex/skills/security-best-practices`）

1. 识别语言/框架（本仓：Java/Spring、Vue3、uni-app、TypeScript）
2. 读取 skill 内 `references/` 对应文档（java、javascript、typescript）
3. 按 passive / report 模式扫描改动文件
4. 输出分级报告（Critical / High / Medium）

## 本仓重点

- JWT / session / 运营权限 `@PreAuthorize` / 商户数据隔离
- SQL：MyBatis 参数化；禁止拼接
- 内部 API：`X-Internal-Api-Key`；勿暴露 `/internal/v1` 到公网
- 前端：admin token 存储、XSS、敏感信息进日志
- **禁止**提交 `.env`、JWT、API Key、智谱 Key

## 可选：NVIDIA SkillSpector（网络可用时）

```powershell
git clone https://github.com/NVIDIA/SkillSpector.git tools/SkillSpector
# 按 upstream README 运行静态扫描；结果与 security-best-practices 交叉验证
```

克隆失败时用 `security-best-practices` 即可，勿阻塞开发。

## 输出

- 发现写入 `security_best_practices_report.md` 或直接在回复中列 P0/P1
- 每项含文件:行与修复建议
