"""Verify the global reader release and keep evidence separate from prior acceptance."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parent.parent
run = (root / 'doc/reader-global-current.txt').read_text(encoding='utf-8-sig').strip()
evidence = root / 'doc' / run
runtime = json.loads((evidence / 'test-runtime.json').read_text(encoding='utf-8-sig'))
resources = root / 'novel-front/src/main/resources'
jar_path = root / 'novel-front/target/novel-front-5.3.3.jar'
zip_path = root / 'novel-front/target/build/novel-front.zip'
old_jar = evidence / 'before/novel-front/target/novel-front-5.3.3.jar'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


selected = sorted(p for folder in ('templates', 'static') for p in (resources / folder).rglob('*') if p.is_file())
jar_hash = digest(jar_path)
assert digest(Path(runtime['candidateContext']) / 'app.jar') == jar_hash
intentional = {'templates/book/reader.html', 'templates/common/site.html',
               'static/css/global-player.css', 'static/javascript/site/site.js',
               'static/javascript/reader/engine.js', 'static/javascript/reader/controller.js',
               'static/javascript/reader/sherpa.worker.js', 'static/javascript/reader/global.worker.js',
               'static/javascript/reader/global-player.js'}
changed = []
changed_classes = []
with zipfile.ZipFile(jar_path) as jar, zipfile.ZipFile(zip_path) as release, zipfile.ZipFile(old_jar) as previous:
    assert not any('FixtureApplication' in n or '/frontend-fixture/' in n or n.endswith(('.onnx', '.wasm', '.data')) for n in jar.namelist())
    with release.open('novel-front.jar') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == jar_hash
    for item in selected:
        relative = item.relative_to(resources).as_posix()
        external = 'html/' + relative[10:] if relative.startswith('templates/') else relative
        expected = digest(item)
        assert digest(root / 'templates/green' / external) == expected, relative
        assert digest(Path(runtime['candidateContext']) / 'templates/green' / external) == expected, relative
        packaged = 'BOOT-INF/classes/' + relative
        with jar.open(packaged) as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected, relative
        with release.open('templates/green/' + external) as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected, relative
        prior = hashlib.sha256(previous.read(packaged)).hexdigest() if packaged in previous.namelist() else None
        if prior != expected:
            assert relative in intentional, 'Unexpected resource change: ' + relative
            changed.append(relative)
    assert set(changed) == intentional
    classes = [name for name in previous.namelist() if name.startswith('BOOT-INF/classes/') and name.endswith('.class')]
    for name in classes:
        if jar.read(name) != previous.read(name):
            changed_classes.append(name)
    assert changed_classes == ['BOOT-INF/classes/com/java2nb/novel/controller/page/PageController.class']
log = (evidence / 'maven.log').read_text(encoding='utf-8', errors='replace')
assert 'BUILD SUCCESS' in log and 'Tests run: 11, Failures: 0, Errors: 0, Skipped: 0' in log
report = dict(time=datetime.now(timezone.utc).isoformat(), image=runtime['image'], resourcesVerified=len(selected),
              changedResources=changed, javaClassesUnchanged=len(classes) - len(changed_classes),
              changedJavaClasses=changed_classes, fixtureAndModelBinariesExcluded=True,
              jarZipAndImageContextMatch=True, javaTests=11,
              packages=[dict(path=str(p), bytes=p.stat().st_size, sha256=digest(p)) for p in (jar_path, zip_path)])
(evidence / 'package-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in report.items() if k != 'packages'}))
