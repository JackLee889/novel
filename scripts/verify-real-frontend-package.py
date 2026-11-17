"""Verify the final live-test image context and release packages byte for byte."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parent.parent
run = (root / 'doc/frontend-realtest-current.txt').read_text(encoding='utf-8-sig').strip()
evidence = root / 'doc' / run
runtime = json.loads((evidence / 'test-runtime.json').read_text(encoding='utf-8-sig'))
baseline_root = root / (root / 'doc/frontend-evidence/baseline-path.txt').read_text(encoding='utf-8-sig').strip()
baseline = {entry['path'].replace('\\', '/'): entry['sha256'] for entry in json.loads((baseline_root / 'baseline-sha256.json').read_text(encoding='utf-8-sig'))}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


for relative, original in baseline.items():
    if relative.startswith('novel-front/src/main/java/'):
        assert digest(root / relative) == original, relative
for module in ['engine.js', 'scheduler.js', 'audio-player.js', 'sherpa.worker.js', 'controller.js', 'segmenter.js', 'chapter-repository.js']:
    relative = 'novel-front/src/main/resources/static/javascript/reader/' + module
    assert digest(root / relative) == baseline[relative], module
resources = root / 'novel-front/src/main/resources'
selected = list((resources / 'templates').rglob('*.html')) + list((resources / 'static/javascript/site').glob('*.js'))
selected += list((resources / 'static/reader/icons').glob('*'))
selected += [resources / 'static/css/site.css', resources / 'static/css/reader.css', resources / 'static/javascript/reader/storage.js']
selected = sorted({item for item in selected if item.is_file()})
jar_path = root / 'novel-front/target/novel-front-5.3.3.jar'
zip_path = root / 'novel-front/target/build/novel-front.zip'
jar_hash = digest(jar_path)
assert digest(Path(runtime['candidateContext']) / 'app.jar') == jar_hash
with zipfile.ZipFile(jar_path) as jar, zipfile.ZipFile(zip_path) as release:
    assert not any('FixtureApplication' in name or '/frontend-fixture/' in name or name.endswith('.onnx') for name in jar.namelist())
    with release.open('novel-front.jar') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == jar_hash
    for item in selected:
        relative = item.relative_to(resources).as_posix()
        external = 'html/' + relative[10:] if relative.startswith('templates/') else relative
        expected = digest(item)
        assert digest(root / 'templates/green' / external) == expected
        assert digest(Path(runtime['candidateContext']) / 'templates/green' / external) == expected
        with jar.open('BOOT-INF/classes/' + relative) as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected
        with release.open('templates/green/' + external) as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected
log = (evidence / 'maven-final.log').read_text(encoding='utf-8', errors='replace')
assert 'BUILD SUCCESS' in log and 'Tests run: 11, Failures: 0, Errors: 0, Skipped: 0' in log
report = dict(time=datetime.now(timezone.utc).isoformat(), image=runtime['image'], resourcesVerified=len(selected),
              jarZipAndImageContextMatch=True, fixtureExcluded=True, productionJavaUnchanged=True,
              sherpaCoreUnchanged=True, javaTests=11,
              packages=[dict(path=str(p), bytes=p.stat().st_size, sha256=digest(p)) for p in (jar_path,zip_path)])
(evidence / 'final-package-verification.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='packages'}))
