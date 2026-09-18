"""来源 IP / CIDR 判定（H52）——纯标准库实现，便于无 fastapi 依赖的单测。"""

from __future__ import annotations

import ipaddress
from typing import Iterable, Optional, Union

IPAddress = Union[ipaddress.IPv4Address, ipaddress.IPv6Address]


def parse_cidrs(raw: str | None) -> list:
    """逗号分隔 CIDR 文本 → ip_network 列表；非法项忽略（返回空 = 不限制）。"""
    networks = []
    for part in (raw or "").split(","):
        part = part.strip()
        if not part:
            continue
        try:
            networks.append(ipaddress.ip_network(part, strict=False))
        except ValueError:
            continue
    return networks


def parse_ip(value: str | None) -> Optional[IPAddress]:
    """解析单个 IP 文本；非法格式返回 None（不盲信代理头里的任意字符串）。"""
    if not value:
        return None
    try:
        return ipaddress.ip_address(value.strip())
    except ValueError:
        return None


def normalize_mapped(addr):
    """IPv4-mapped IPv6（如 ::ffff:10.0.0.1）按其内嵌 IPv4 参与匹配。"""
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
        return addr.ipv4_mapped
    return addr


def addr_in_networks(addr, networks: Iterable) -> bool:
    """None（无法解析）一律拒绝；网络列表为空调用方应先行短路（=不限制）。"""
    if addr is None:
        return False
    return any(normalize_mapped(addr) in net for net in networks)
