#!/usr/bin/env node
/**
 * `check-test-mockito-pitfalls.mjs` 的自测：三类写法各自红、合规写法绿、
 * 签名歧义时宁可漏报，以及锚点与真仓。
 *
 *   node scripts/check-test-mockito-pitfalls.test.mjs
 */
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  buildMethodIndex,
  checkProject,
  matcherName,
  paramType,
  splitTopLevel,
  stripJavaComments,
  uniqueSignature
} from './check-test-mockito-pitfalls.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[check-test-mockito-pitfalls.test]';
const MIN = { minTestFiles: 1, minStubSites: 1, minResolvedCalls: 1 };
let checks = 0;

function assert(cond, message) {
  checks++;
  if (!cond) throw new Error(`断言失败：${message}`);
}

const MAIN_SERVICE = `package demo;

public class FooService {
    public long compute(long id) {
        return id;
    }

    public Long total(Long id, String key) {
        return id;
    }

    public void fire(String reason) {
    }

    public int take(List<Foo> items, Object extra) {
        return 0;
    }
}
`;

const MAIN_MAPPER = `package demo;

public interface FooMapper {
    int delete(Wrapper wrapper);

    int delete(Foo entity);
}
`;

const MAIN_AMBIGUOUS = `package demo;

public class Ambiguous {
    public void save(Foo foo) {
    }

    public void save(String name) {
    }
}
`;

const FOO = `package demo;

public class Foo {
    private Long value = 0L;

    public static Foo builder() {
        return new Foo();
    }

    public Foo build() {
        return this;
    }
}

class Wrapper {
}
`;

function testSource(body) {
  return `package demo;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;

class FooServiceTest {
    private FooService service;
    private FooMapper fooMapper;
    private Ambiguous ambiguous;

    @Test
    void probe() {
${body}
    }
}
`;
}

function writeFixture(root, body) {
  rmSync(root, { recursive: true, force: true });
  const mainDir = join(root, 'services', 'demo', 'src', 'main', 'java', 'demo');
  const testDir = join(root, 'services', 'demo', 'src', 'test', 'java', 'demo');
  mkdirSync(mainDir, { recursive: true });
  mkdirSync(testDir, { recursive: true });
  writeFileSync(join(mainDir, 'FooService.java'), MAIN_SERVICE);
  writeFileSync(join(mainDir, 'FooMapper.java'), MAIN_MAPPER);
  writeFileSync(join(mainDir, 'Ambiguous.java'), MAIN_AMBIGUOUS);
  writeFileSync(join(mainDir, 'Foo.java'), FOO);
  writeFileSync(join(testDir, 'FooServiceTest.java'), testSource(body));
}

function run(root) {
  return checkProject(root, MIN);
}

function expectOk(root, message) {
  const r = run(root);
  assert(r.ok, `${message}：期望绿，实际 ${JSON.stringify(r.findings)}`);
  return r;
}

function expectFinding(root, needle, message) {
  const r = run(root);
  assert(!r.ok, `${message}：期望红，实际绿`);
  assert(
    r.findings.some((f) => f.includes(needle)),
    `${message}：期望命中「${needle}」，实际 ${JSON.stringify(r.findings)}`
  );
  return r;
}

function parserChecks() {
  assert(paramType('long id') === 'long', '基本类型形参');
  assert(paramType('final List<Foo> items') === 'List', '去 final/泛型');
  assert(paramType('java.time.Instant at') === 'Instant', 'FQN 取简单名');
  assert(paramType('String... names') === 'String', 'varargs 去 []');
  assert(paramType('@Nullable Foo foo') === 'Foo', '去注解');
  assert(splitTopLevel('a, b(c, d), e<f, g>').length === 3, '顶层逗号切分');
  assert(matcherName('anyInt()') === 'anyInt', 'matcher 识别');
  assert(matcherName('Mockito.anyLong()') === 'anyLong', '限定名 matcher');
  assert(matcherName('eq(1)') === null, '非无参 matcher 不识别');
  assert(stripJavaComments('int a; // when(x)\nint b;').includes('when') === false, '注释被掩掉');

  const masked = stripJavaComments(MAIN_SERVICE + MAIN_AMBIGUOUS);
  const index = buildMethodIndex([masked]);
  assert(uniqueSignature(index, 'compute', 1).params[0] === 'long', '签名索引 long');
  assert(uniqueSignature(index, 'compute', 1).returnsVoid === false, '签名索引返回值');
  assert(uniqueSignature(index, 'save', 1) === null, '同名同参多签名 → 不唯一（宁可漏报）');
  assert(uniqueSignature(index, 'fire', 0) === null, '参数个数不符 → 不唯一');
}

function main() {
  parserChecks();

  const tmpBase = join(ROOT, '.tmp');
  mkdirSync(tmpBase, { recursive: true });
  const fixture = mkdtempSync(join(tmpBase, 'mockito-pitfalls-'));

  try {
    // 1) 合规：anyLong 对 long、any 对 long、anyString 对 String、verify void、delete 带类型
    writeFixture(
      fixture,
      `when(service.compute(anyLong())).thenReturn(1L);
        when(service.compute(any())).thenReturn(1L);
        when(service.total(anyLong(), anyString())).thenReturn(1L);
        when(service.take(anyList(), any())).thenReturn(null);
        verify(service).fire(anyString());
        verify(fooMapper).delete(any(Wrapper.class));
        verify(fooMapper).delete((Foo) any());`
    );
    const green = expectOk(fixture, '合规 fixture');
    assert(green.stubSites >= 7, `合规 fixture 应识别 ≥7 处 matcher 调用，实际 ${green.stubSites}`);

    // 2) matcher 与形参不符（anyInt 桩 long）
    writeFixture(fixture, `when(service.compute(anyInt())).thenReturn(1L);`);
    expectFinding(fixture, 'anyInt() 匹配不上形参 #1「long」', 'anyInt 桩 long');

    // 3) anyLong 桩 Long 合法、桩 int 不合法
    writeFixture(fixture, `when(service.total(anyLong(), anyString())).thenReturn(1L);`);
    expectOk(fixture, 'anyLong 桩 Long');
    writeFixture(fixture, `when(service.compute(anyString())).thenReturn(1L);`);
    expectFinding(fixture, 'anyString() 匹配不上形参 #1「long」', 'anyString 桩 long');

    // 4) when(void)
    writeFixture(fixture, `when(service.fire(anyString()));`);
    expectFinding(fixture, '目标方法返回 void', 'when(void)');

    // 5) delete(any()) 重载歧义
    writeFixture(fixture, `verify(fooMapper).delete(any());`);
    expectFinding(fixture, '重载歧义', 'delete(any()) 歧义');

    // 6) 非 Mapper 接收者的 delete(any()) 不误报（歧义规则只在 Mapper 上生效）
    writeFixture(fixture, `verify(ambiguous).save(any());`);
    const nonMapper = run(fixture);
    assert(
      !nonMapper.findings.some((f) => f.includes('重载歧义')),
      `非 Mapper 的 delete/save 不应报歧义，实际 ${JSON.stringify(nonMapper.findings)}`
    );

    // 7) 同名方法签名不一致（save(Foo)/save(String)）→ 跳过 matcher 比对，不误报
    const ambiguous = run(fixture);
    assert(
      !ambiguous.findings.some((f) => f.includes('匹配不上')),
      `签名不唯一时应跳过，实际 ${JSON.stringify(ambiguous.findings)}`
    );

    // 8) 锚点
    writeFixture(fixture, `when(service.compute(anyLong())).thenReturn(1L);`);
    const anchorTestFiles = checkProject(fixture, { ...MIN, minTestFiles: 99 });
    assert(
      anchorTestFiles.findings.some((f) => f.includes('锚点失效') && f.includes('测试源文件')),
      `测试文件锚点应红，实际 ${JSON.stringify(anchorTestFiles.findings)}`
    );
    const anchorStubs = checkProject(fixture, { ...MIN, minStubSites: 99 });
    assert(
      anchorStubs.findings.some((f) => f.includes('锚点失效') && f.includes('matcher')),
      `matcher 调用锚点应红，实际 ${JSON.stringify(anchorStubs.findings)}`
    );
    const anchorResolved = checkProject(fixture, { ...MIN, minResolvedCalls: 99 });
    assert(
      anchorResolved.findings.some((f) => f.includes('锚点失效') && f.includes('签名')),
      `签名解析锚点应红，实际 ${JSON.stringify(anchorResolved.findings)}`
    );

    // 9) 真仓：零命中且解析量在锚点之上
    const live = checkProject(ROOT);
    assert(live.ok, `真仓应绿，实际 ${JSON.stringify(live.findings)}`);
    assert(live.testFiles >= 350, `真仓测试源文件数 ${live.testFiles} 低于锚点`);
    assert(live.stubSites >= 800, `真仓 matcher 调用数 ${live.stubSites} 低于锚点`);
    assert(live.resolvedCalls >= 350, `真仓可唯一定位签名的调用数 ${live.resolvedCalls} 低于锚点`);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }

  const leftovers = existsSync(tmpBase)
    ? readdirSync(tmpBase).filter((n) => n.startsWith('mockito-pitfalls-'))
    : [];
  assert(leftovers.length === 0, `临时目录未清理：${leftovers.join(',')}`);

  console.log(
    `${TAG} OK（${checks} 项：三类写法红 / 合规绿 / 歧义与不唯一跳过 / 锚点 / 真仓，且不残留临时目录）`
  );
}

main();
