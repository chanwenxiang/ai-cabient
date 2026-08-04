# 生产运行时模型挂载目录

将训练导出的 `cabinet-skus-v*.pt` 放在此目录，供 `docker-compose.production.yml` 挂载到 vision-service `/app/models`。

```powershell
copy ..\..\vision-service\models\cabinet-skus-v1.0.0.pt .
```

勿提交 `.pt` 到 Git。
