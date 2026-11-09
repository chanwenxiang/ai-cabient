#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
芝麻先享（zhima.credit.payafteruse.*）联调探针。

用途：
  zm_service_id 到手后，在生产服务器（真凭证）实跑全链路验证：
    sign → schemeUrl（唤起开通页）→ order（免确认下单）→ pay（扣款）→ query
  每个命令独立可跑，也支持 --all 串跑。

前置（生产服务器 /data/apps/ai-cabinet/infra/.env.production）：
  ALIPAY_APP_ID       ← 🔴 待定：过审后问 BD——zm_service_id 挂在壳小程序
                         2021007118617439 上，接口调用大概率要用壳 APPID+密钥签名。
                         可用 --app-id/--private-key 覆盖 env 值。
  ALIPAY_PRIVATE_KEY  （PKCS8）
  ALIPAY_PUBLIC_KEY   （支付宝公钥，验签用）
  ZM_SERVICE_ID       ← 28 位，信用服务配置过审后分配（状态须「●已上线」）
                        或命令行 --zm-service-id 传入

签名算法与仓库 AlipaySignUtil.signRsa2 完全一致：
  TreeMap 排序 + 保留 sign_type + 不做 URL 转义 + SHA256withRSA(RSA2) + UTF-8 字节。
  🔴 不要对参数做 URL 转义（2026-10-28 探针踩坑：多加转义 ⇒ isv.invalid-signature）。

官方接口序列（opendocs 03vtf0 / 官方 Skill 文档）：
  sign            zhima.credit.payafteruse.creditagreement.sign    → credit_agreement_id
  order           zhima.credit.payafteruse.creditbizorder.order    → credit_biz_order_id（免确认）
  pay             alipay.trade.pay + scene=ZHIMA_CREDIT_CODE       → 扣款
  query-agreement zhima.credit.payafteruse.creditagreement.query
  query           zhima.credit.payafteruse.creditbizorder.query
  finish          zhima.credit.payafteruse.creditbizorder.finish
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

DEFAULT_ENV = "/data/apps/ai-cabinet/infra/.env.production"
LOCAL_ENV = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "infra", ".env.production")
GATEWAY_DEFAULT = "https://openapi.alipay.com/gateway.do"

SCHEME_BASE = "alipays://platformapi/startapp?appId=20000067&url="


def load_env(path):
    env = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def sign_rsa2(params, private_key_body):
    """与仓库 AlipaySignUtil.signRsa2 一致：排序拼接、保留 sign_type、不转义。"""
    s = "&".join(f"{k}={params[k]}" for k in sorted(params) if params[k] not in (None, ""))
    pem = (
        "-----BEGIN PRIVATE KEY-----\n"
        + private_key_body.strip()
        + "\n-----END PRIVATE KEY-----\n"
    )
    body = "".join(l for l in pem.splitlines() if "-----" not in l)
    raw = base64.b64decode(body)
    fd, path = tempfile.mkstemp(suffix=".pem")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
        p = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", path],
            input=s.encode("utf-8"),
            capture_output=True,
        )
        if p.returncode != 0:
            raise RuntimeError("openssl sign failed: " + p.stderr.decode(errors="replace")[:300])
        return base64.b64encode(p.stdout).decode()
    finally:
        os.unlink(path)


class AlipayProbe:
    def __init__(self, app_id, private_key, gateway):
        self.app_id = app_id
        self.priv = private_key
        self.gateway = gateway

    def call(self, method, biz, notify_url=None):
        params = {
            "app_id": self.app_id,
            "method": method,
            "format": "JSON",
            "charset": "utf-8",
            "sign_type": "RSA2",
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "version": "1.0",
            "biz_content": json.dumps(biz, ensure_ascii=False, separators=(",", ":")),
        }
        if notify_url:
            params["notify_url"] = notify_url
        params["sign"] = sign_rsa2(params, self.priv)
        data = urllib.parse.urlencode(params).encode()
        req = urllib.request.Request(
            self.gateway,
            data=data,
            headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"},
        )
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read().decode("utf-8", "replace")


def show(tag, raw):
    print(f"--- {tag} ---")
    try:
        j = json.loads(raw)
        key = next(k for k in j if k.endswith("_response"))
        body = j[key]
        print("  code =", body.get("code"), "| msg =", body.get("msg"))
        if body.get("sub_code"):
            print("  sub_code =", body.get("sub_code"), "| sub_msg =", body.get("sub_msg"))
        for k, v in body.items():
            if k not in ("code", "msg", "sub_code", "sub_msg", "sign"):
                print(f"  {k} =", json.dumps(v, ensure_ascii=False)[:600])
    except Exception:
        print("  RAW:", (raw or "")[:500])
    print()


def build_scheme_url(sign_str):
    """官方规则：alipays://platformapi/startapp?appId=20000067&url=<encodeURIComponent(landing)>"""
    landing = "https://render.alipay.com/p/yuyan/180020010000706007/index.html?signStr=" + urllib.parse.quote(
        sign_str, safe=""
    )
    return SCHEME_BASE + urllib.parse.quote(landing, safe="")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default=DEFAULT_ENV if os.path.exists(DEFAULT_ENV) else LOCAL_ENV)
    ap.add_argument("--zm-service-id", default=os.environ.get("ZM_SERVICE_ID", ""))
    ap.add_argument("--app-id", default="")
    ap.add_argument("--private-key", default="")
    ap.add_argument(
        "cmd",
        nargs="?",
        choices=["sign", "order", "pay", "query-agreement", "query", "finish", "all"],
        default="sign",
    )
    ap.add_argument("--out-agreement-no", default="")
    ap.add_argument("--credit-agreement-id", default="")
    ap.add_argument("--out-order-no", default="")
    ap.add_argument("--credit-biz-order-id", default="")
    ap.add_argument("--amount", default="1.00")
    args = ap.parse_args()

    env = load_env(args.env)
    app_id = args.app_id or env.get("ALIPAY_APP_ID", "")
    priv = (args.private_key or env.get("ALIPAY_PRIVATE_KEY", "")).replace("\\n", "\n")
    gateway = env.get("ALIPAY_GATEWAY") or GATEWAY_DEFAULT
    zm = args.zm_service_id or env.get("ZM_SERVICE_ID", "")
    notify = env.get("ALIPAY_NOTIFY_URL", "")

    print("APP_ID =", app_id)
    print("zm_service_id =", zm or "(未提供——sign/order 需要它)")
    print("GATEWAY =", gateway)
    print()

    probe = AlipayProbe(app_id, priv, gateway)
    cmd = args.cmd

    def do_sign():
        if not zm:
            print("[ERR] 缺 zm_service_id（--zm-service-id 或 env ZM_SERVICE_ID）")
            return None
        out_no = args.out_agreement_no or ("AGR" + time.strftime("%Y%m%d%H%M%S"))
        biz = {
            "out_agreement_no": out_no,
            "zm_service_id": zm,
            "product_code": "CREDIT_PAY_AFTER_USE",
            # 官方 2.2.1：返回 response.getBody()（orderStr）作 signStr → 拼 schemeUrl
        }
        raw = probe.call("zhima.credit.payafteruse.creditagreement.sign", biz, notify_url=notify)
        show(f"sign (out_agreement_no={out_no})", raw)
        return raw

    def do_order():
        if not args.credit_agreement_id:
            print("[ERR] 缺 --credit-agreement-id")
            return None
        out_no = args.out_order_no or ("ORD" + time.strftime("%Y%m%d%H%M%S"))
        biz = {
            "out_order_no": out_no,
            "credit_agreement_id": args.credit_agreement_id,
            "subject": "智能开门柜取货",
            # 智能货柜：免用户确认场景，必传 credit_allpass=true（官方下单接口文档）
            "extend_params": json.dumps({"credit_allpass": "true"}),
        }
        raw = probe.call("zhima.credit.payafteruse.creditbizorder.order", biz, notify_url=notify)
        show(f"order (out_order_no={out_no})", raw)
        return raw

    def do_pay():
        if not args.credit_biz_order_id:
            print("[ERR] 缺 --credit-biz-order-id")
            return None
        biz = {
            "out_trade_no": "PAY" + time.strftime("%Y%m%d%H%M%S"),
            "product_code": "GENERAL_WITHHOLDING",
            "subject": "智能开门柜取货结算",
            "total_amount": args.amount,
            "auth_code": args.credit_biz_order_id,
            "scene": "ZHIMA_CREDIT_CODE",
            "extend_params": json.dumps({"creditTradeScene": "CREDIT_PAY_UNCERTAIN_FEE"}),
        }
        raw = probe.call("alipay.trade.pay", biz, notify_url=notify)
        show(f"pay (amount={args.amount})", raw)
        return raw

    def do_query_agreement():
        biz = {"credit_agreement_id": args.credit_agreement_id} if args.credit_agreement_id else {
            "zm_service_id": zm,
        }
        raw = probe.call("zhima.credit.payafteruse.creditagreement.query", biz)
        show("query-agreement", raw)
        return raw

    def do_query():
        biz = {"credit_biz_order_id": args.credit_biz_order_id} if args.credit_biz_order_id else {
            "out_order_no": args.out_order_no,
        }
        raw = probe.call("zhima.credit.payafteruse.creditbizorder.query", biz)
        show("query", raw)
        return raw

    def do_finish():
        if not args.credit_biz_order_id:
            print("[ERR] 缺 --credit-biz-order-id")
            return None
        biz = {"credit_biz_order_id": args.credit_biz_order_id, "is_fulfilled": True}
        raw = probe.call("zhima.credit.payafteruse.creditbizorder.finish", biz)
        show("finish", raw)
        return raw

    steps = {
        "sign": do_sign,
        "order": do_order,
        "pay": do_pay,
        "query-agreement": do_query_agreement,
        "query": do_query,
        "finish": do_finish,
    }
    if cmd == "all":
        for name in ("sign", "order", "pay", "query"):
            steps[name]()
    else:
        steps[cmd]()

    print("提示：schemeUrl 构造规则（拿到 signStr 后）：")
    print("  " + SCHEME_BASE + "<encodeURIComponent(landing)>")


if __name__ == "__main__":
    main()
