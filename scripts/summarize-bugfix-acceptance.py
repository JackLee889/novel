"""Summarize recorded checks; never infer production acceptance from a build."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'doc' / (ROOT / 'doc/bugfix-acceptance-current.txt').read_text(encoding='utf-8').strip()
names = {
    'HTTP': 'http-results.json',
    '认证与历史': 'auth-history-results.json',
    '刷新与焦点竞态': 'auth-refresh-live-results.json',
    '后台权限': 'admin-permissions/admin-permission-acceptance.json',
    '后台页面': 'browser-built-acceptance.json',
    '组件': 'components-built-acceptance.json',
    '前台页面': 'front-built-acceptance.json',
    '共享播放': 'reader-global-results.json',
    '首次OOM重建': 'tts-recovery-live-results.json',
    '运行中模型中止': 'tts-runtime-abort-live-results.json',
    '长队列PCM': 'tts-stress-results.json',
    '三独立浏览器冷启动': 'parallel-cold-browser-results.json'
}
checks = {}
for label, name in names.items():
    file = OUT / name
    value = json.loads(file.read_text(encoding='utf-8'))
    assert value.get('passed') is True, f'Incomplete or failed evidence: {name}'
    rows = value.get('checks', [])
    assert rows and all(row['passed'] for row in rows), name
    checks[label] = dict(passed=len(rows), total=len(rows), evidence=name,
                         sha256=hashlib.sha256(file.read_bytes()).hexdigest())
java = {}
for module in ('front', 'admin', 'crawl'):
    entries = [ET.parse(file).getroot() for file in (OUT / 'candidate-source' / ('novel-' + module) / 'target/surefire-reports').glob('TEST-*.xml')]
    java[module] = {key: sum(int(float(entry.get(key, 0))) for entry in entries) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert java[module]['tests'] > 0 and not any(java[module][key] for key in ('failures', 'errors', 'skipped'))
node = (OUT / 'node-tests.log').read_text(encoding='utf-8')
node_total = int(re.search(r'^# tests (\d+)$', node, re.M)[1])
assert int(re.search(r'^# pass (\d+)$', node, re.M)[1]) == node_total
assert int(re.search(r'^# fail (\d+)$', node, re.M)[1]) == 0
permission_tool_log = (OUT / 'permission-tool-tests-final.log').read_text(encoding='utf-8-sig')
permission_tool_total = int(re.search(r'Ran (\d+) tests', permission_tool_log)[1])
assert re.search(r'^OK\s*$', permission_tool_log, re.M)
cleanup = json.loads((OUT / 'cleanup.json').read_text(encoding='utf-8'))
assert cleanup['originalRuntimeUnchanged'] is True
sync = json.loads((OUT / 'validation-input-sync.json').read_text(encoding='utf-8'))
assert sync['businessSourcesMatchFrozenCandidate'] is True
packages = json.loads((OUT / 'package-verification.json').read_text(encoding='utf-8'))
summary = dict(scope='SOURCE_AND_ISOLATED_CANDIDATE_PASS', completed=datetime.now().astimezone().isoformat(),
               java=java, node=dict(passed=node_total, total=node_total), permissionTool=dict(passed=permission_tool_total, total=permission_tool_total), checks=checks, packages=packages,
               productionDeployment=False, originalDatabaseAccessed=False, originalPermissionMigrationApplied=False,
               originalRuntimeUnchanged=True, volumesDeleted=False, cleanup=cleanup)
(OUT / 'bugfix-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
java_total = sum(entry['tests'] for entry in java.values())
lines = [
    '按功能模块实施及验收记录（2026-10-08）',
    '结论：源码与隔离候选修复通过。本记录不等同于目标生产环境、实体手机或低内存设备全面验收。',
    '',
    '一、登录、身份与接口异常',
    'JWT续期复制不可变Claims后签发；新旧Token均限制HS512、有效期限和有效身份。REST异常明确JSON/状态码，HTML错误明确500/404。头部身份区分核验、续期失败与匿名；真实userInfo回退和显式重试保留身份；切号/退出拒绝旧回包，focus不抢占首轮refresh并可恢复私有页初始化。',
    '身份/历史17项真实注册与业务验收保留；刷新/focus10项已通过。HTTP500注入和空Accept边界在报告中明确，未将纯HTML Accept成功响应当作承诺。',
    '',
    '二、后台内容与权限',
    '修复13个详情按钮权限名；授权缓存以稳定用户ID和独立命名空间存储，角色/菜单/用户角色变化在事务提交后精确失效。批量删除角色清理关联并失效缓存，删除行为只以mock回归验证，未删除真实角色。',
    '35项缺失权限定义提供幂等增量SQL/工具；默认不授予既有角色，内容编辑12项/只读6项按明确roleId选择。新ID避开历史定义和孤立引用，NULL旧关联保留。',
    '仅专用测试角色进行全权限页面覆盖和最小权限正负向验证。原100菜单/413关联逐ID一致，原角色有效权限89项前后不变。测试副本曾发生fixture角色ID冲突，已按原主键恢复6条关联，失败和恢复证据保留；原部署未访问。',
    '原库尚未执行权限迁移。交付scripts/migrate-admin-permissions.py及报告目录中的预览SQL/矩阵供实际角色方案使用。',
    '',
    '三、浏览器听书与资源生命周期',
    '流式下载单缓冲、完整大小/SHA校验、取消和失败释放；可用时broker管理真实DedicatedWorker，Chrome缺嵌套Worker能力时保留共享作用域并在fatal/空闲时真正self.close。运行代次隔离旧消息/排队任务；运行中onAbort指向当前请求，当前代次fatal无需pending也会关闭。',
    '活动reader/首页会话收到引擎错误立即停音频、旧请求和队列，避免自动重新加载模型；用户显式重试最多两次。章节15秒期限只包围权限请求，模型故障保留原始错误。',
    '首次OOM及运行中模型中止为明确故障注入；恢复播放使用真实模型、实际16kHz非零PCM及前进的AudioContext时钟。CDP目标消失和新runtime engineId证明线程实际关闭/重建，调试targetId可能由Chromium复用。正常共享刷新、换章、首页和标签交接也已实测。',
    '模型没有缩小或替换；真实WASM线性内存仍536870912字节。队列16MiB上限不代表总模型内存。三浏览器冷启动结果仅覆盖本次Windows/Chrome与记录的主机内存环境，不证明原先低可用内存条件下已获得3/3或所有设备容量。',
    'Chrome共享作用域不能立即抢占同步WASM死循环；本次覆盖真实fatal通知、异步实例化失败和回收，未声称同步硬挂死抢占。实体iOS/Safari、目标HTTPS及持续长时间运行仍需部署前验收。',
    '',
    '四、缓存、打包与保留',
    '最终缓存链以package-source及模块manifest为准。逐文件核对2061项业务静态资源/模板/MyBatis映射；排除Maven默认忽略的OS/Git元数据。验证后的green生成模板已同步回工作源码，原文件备份和SHA清单保留。',
    '全部新测试账号、历史、反馈、角色与独立卷保留；只停止本次候选容器和测试helper。原8个容器的ID、镜像、挂载、环境与State逐项相同，未启动原数据库，未删除卷、未清空或格式化存储。',
    '',
    f'验证汇总：Java {java_total}/{java_total}；Node {node_total}/{node_total}；权限工具 {permission_tool_total}/{permission_tool_total}。'
]
for label, result in checks.items():
    lines.append(f"{label}：{result['passed']}/{result['total']}，{result['evidence']}")
lines.extend(['', '最终包：'])
for module, package in packages.items():
    lines.append(f"{module}: {package['image']} SHA256={package['sha256']}")
lines.extend(['', '完整证据与边界：bugfix-summary.json、source-frozen-final.json、generated-green-sync.json、cleanup.json及各模块汇总。'])
(OUT / 'bugfix-result.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
(OUT / 'task-ledger.txt').write_text('Complete: source fixes, candidate build, recorded isolated live acceptance, generated-template synchronization and non-destructive cleanup. Production deployment and original role migration were not performed.\n', encoding='utf-8')
print(json.dumps(dict(java=java_total, node=node_total, checks={label: entry['total'] for label, entry in checks.items()}, scopedPass=True), ensure_ascii=False))
