# -*- coding: utf-8 -*-
"""A6 整改：VISION CIDRS fail-fast（parse_cidrs_strict）与信任代理网段语义测试。

fail-fast 抽在 ip_guard 纯函数（main.py import 期调用），避免拉起整个 app 的重依赖 reload。
"""

from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.ip_guard import addr_in_networks, parse_cidrs_strict, parse_ip  # noqa: E402


def test_strict_all_invalid_raises():
    """配置了 CIDRS 但全非法 → raise（不得静默降级为不限制）。"""
    with pytest.raises(ValueError, match="no valid CIDR"):
        parse_cidrs_strict("not-a-cidr,also-bad")


def test_strict_empty_ok():
    """未配置 = 不限制，合法。"""
    assert parse_cidrs_strict("") == []
    assert parse_cidrs_strict(None) == []


def test_strict_partial_invalid_skips_bad():
    """部分非法部分合法 → 跳过非法项。"""
    nets = parse_cidrs_strict("10.0.0.0/8, bad-entry, 172.16.0.0/12")
    assert len(nets) == 2


def test_strict_valid_single():
    assert len(parse_cidrs_strict("10.0.0.0/8")) == 1


def test_untrusted_proxy_remote_addr_only():
    """无信任网段（默认）→ _client_addr 的语义 = 恒用直连地址：
    addr_in_networks 只对直连地址判定，转接头不再参与（组合行为由 main._client_addr 保证）。"""
    nets = parse_cidrs_strict("10.0.0.0/8")
    remote = parse_ip("10.1.2.3")
    assert addr_in_networks(remote, nets) is True
    # 直连不在网段 → 拒绝，即便攻击者带 XFF 头声称自己是 10.x（语义上 main 层根本不读 XFF）
    outside = parse_ip("8.8.8.8")
    assert addr_in_networks(outside, nets) is False
