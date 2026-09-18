"""ip_guard（H52 来源 CIDR 校验）单元测试——仅标准库依赖。"""

from ipaddress import ip_address, ip_network

from app.ip_guard import addr_in_networks, normalize_mapped, parse_cidrs, parse_ip


def test_parse_cidrs_basic_and_garbage():
    nets = parse_cidrs("10.0.0.0/8, 172.16.0.0/12 ,, not-a-cidr")
    assert ip_network("10.0.0.0/8") in nets
    assert ip_network("172.16.0.0/12") in nets
    assert len(nets) == 2
    assert parse_cidrs("") == []
    assert parse_cidrs(None) == []


def test_parse_ip_rejects_garbage():
    assert parse_ip("10.1.2.3") == ip_address("10.1.2.3")
    assert parse_ip(" 192.168.1.9 ") == ip_address("192.168.1.9")
    assert parse_ip("bad; drop table") is None
    assert parse_ip("") is None
    assert parse_ip(None) is None


def test_addr_in_networks_match_and_mismatch():
    nets = parse_cidrs("10.0.0.0/8,172.16.0.0/12")
    assert addr_in_networks(ip_address("10.1.2.3"), nets) is True
    assert addr_in_networks(ip_address("172.20.0.1"), nets) is True
    assert addr_in_networks(ip_address("8.8.8.8"), nets) is False


def test_ipv4_mapped_ipv6_matches_ipv4_network():
    nets = parse_cidrs("172.16.0.0/12")
    mapped = ip_address("::ffff:172.20.0.1")
    assert normalize_mapped(mapped) == ip_address("172.20.0.1")
    assert addr_in_networks(mapped, nets) is True


def test_unresolvable_addr_is_denied():
    assert addr_in_networks(None, parse_cidrs("0.0.0.0/0")) is False
